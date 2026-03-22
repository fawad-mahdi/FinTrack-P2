import calendar
import math
import os
import sqlite3
from datetime import datetime, timedelta
from datetime import date as date_type
from typing import Optional, List, Dict, Tuple, Any

DB_PATH = os.path.join(os.path.dirname(__file__), "fintrack.db")


def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


# ─── DATE HELPERS ────────────────────────────────────────────────

def _period_to_date_from(period: str) -> Optional[str]:
    """Convert a period name to a start-date ISO string. Returns None for 'all'."""
    today = date_type.today()
    if period == "month":
        return today.replace(day=1).isoformat()
    elif period == "30d":
        return (today - timedelta(days=30)).isoformat()
    elif period == "3m":
        return (today - timedelta(days=90)).isoformat()
    return None  # "all" → no date filter


def _month_range(month: str) -> Tuple[str, str]:
    """Return (start_date_inclusive, end_date_exclusive) for a YYYY-MM string."""
    year, mo = int(month[:4]), int(month[5:7])
    start = f"{month}-01"
    if mo == 12:
        end = f"{year + 1}-01-01"
    else:
        end = f"{year}-{mo + 1:02d}-01"
    return start, end


# ─── FILTER BUILDER ──────────────────────────────────────────────

def _build_filters(
    period: Optional[str] = None,
    bank: Optional[str] = None,
    category: Optional[str] = None,
    amount_min: Optional[float] = None,
    amount_max: Optional[float] = None,
    date_from: Optional[str] = None,
    date_to: Optional[str] = None,
    search: Optional[str] = None,
) -> Tuple[List[str], list]:
    """
    Build shared WHERE clause conditions (excluding status).
    Explicit date_from/date_to override period when both provided.
    Returns (conditions_list, params_list).
    """
    conditions: List[str] = []
    params: list = []

    # Date range: explicit dates take precedence over period
    effective_from = date_from if date_from else (_period_to_date_from(period) if period else None)
    if effective_from:
        conditions.append("tx_date >= ?")
        params.append(effective_from)
    if date_to:
        conditions.append("tx_date <= ?")
        params.append(date_to + "T23:59:59")  # inclusive end date

    if bank:
        conditions.append("bank = ?")
        params.append(bank)

    if category:
        conditions.append("category = ?")
        params.append(category)

    if amount_min is not None:
        conditions.append("amount >= ?")
        params.append(float(amount_min))

    if amount_max is not None:
        conditions.append("amount <= ?")
        params.append(float(amount_max))

    if search:
        s = f"%{search.lower()}%"
        conditions.append(
            "(LOWER(merchant) LIKE ? OR LOWER(category) LIKE ? OR LOWER(bank) LIKE ? OR CAST(amount AS TEXT) LIKE ?)"
        )
        params.extend([s, s, s, s])

    return conditions, params


# ─── SCHEMA ──────────────────────────────────────────────────────

def init_db():
    conn = get_db()
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS transactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            gmail_id TEXT UNIQUE,
            amount REAL NOT NULL,
            tx_type TEXT NOT NULL,
            merchant TEXT,
            bank TEXT NOT NULL,
            category TEXT,
            tx_date TEXT,
            raw_text TEXT,
            confidence TEXT DEFAULT 'high',
            status TEXT DEFAULT 'confirmed',
            source TEXT DEFAULT 'gmail',
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE IF NOT EXISTS sync_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            synced_at TEXT DEFAULT CURRENT_TIMESTAMP,
            emails_found INTEGER DEFAULT 0,
            parsed_ok INTEGER DEFAULT 0,
            pending INTEGER DEFAULT 0
        );

        CREATE TABLE IF NOT EXISTS merchant_categories (
            merchant TEXT PRIMARY KEY,
            category TEXT NOT NULL,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE IF NOT EXISTS merchant_rules (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            pattern TEXT NOT NULL,
            clean_name TEXT NOT NULL,
            is_default INTEGER DEFAULT 0,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE IF NOT EXISTS budgets (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            category TEXT NOT NULL,
            month TEXT NOT NULL,
            amount REAL NOT NULL DEFAULT 0,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            UNIQUE (category, month)
        );
    """)
    conn.commit()
    _migrate(conn)
    _seed_merchant_rules(conn)
    conn.close()


def _migrate(conn):
    """Add new columns to existing tables if they don't exist."""
    existing = {row[1] for row in conn.execute("PRAGMA table_info(transactions)")}
    if "source" not in existing:
        conn.execute("ALTER TABLE transactions ADD COLUMN source TEXT DEFAULT 'gmail'")
        conn.commit()


def _seed_merchant_rules(conn):
    """Seed merchant_rules table with DEFAULT_RULES on first run (if empty)."""
    count = conn.execute("SELECT COUNT(*) FROM merchant_rules").fetchone()[0]
    if count == 0:
        from merchants import DEFAULT_RULES
        conn.executemany(
            "INSERT OR IGNORE INTO merchant_rules (pattern, clean_name, is_default) VALUES (?, ?, 1)",
            DEFAULT_RULES,
        )
        conn.commit()


# ─── TRANSACTIONS ─────────────────────────────────────────────────

def insert_transaction(tx: dict) -> bool:
    """
    Insert a transaction. Returns False if gmail_id already exists (duplicate).
    
    US-15 Duplicate Prevention:
    - The gmail_id UNIQUE constraint prevents duplicate transactions from being inserted
    - Returns False when a duplicate is detected (IntegrityError)
    - User-edited transactions are NEVER overwritten because this function only does INSERT
    - Re-syncing the same email will be blocked by the UNIQUE constraint
    - The sync endpoint tracks skipped duplicates separately in the response
    """
    conn = get_db()
    try:
        conn.execute(
            """INSERT INTO transactions
               (gmail_id, amount, tx_type, merchant, bank, category, tx_date, raw_text, confidence, status, source)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                tx.get("gmail_id"),
                tx["amount"],
                tx["tx_type"],
                tx.get("merchant", "Unknown"),
                tx["bank"],
                tx.get("category", "Uncategorized"),
                tx.get("tx_date", datetime.now().isoformat()),
                tx.get("raw_text", ""),
                tx.get("confidence", "high"),
                "confirmed" if tx.get("confidence") == "high" else "pending",
                tx.get("source", "gmail"),
            ),
        )
        conn.commit()
        return True
    except sqlite3.IntegrityError:
        return False  # duplicate gmail_id
    finally:
        conn.close()


def get_transactions(
    status: Optional[str] = None,
    page: int = 1,
    page_size: int = 25,
    period: Optional[str] = None,
    bank: Optional[str] = None,
    category: Optional[str] = None,
    amount_min: Optional[float] = None,
    amount_max: Optional[float] = None,
    date_from: Optional[str] = None,
    date_to: Optional[str] = None,
    search: Optional[str] = None,
):
    conn = get_db()
    offset = (page - 1) * page_size

    filter_conds, filter_params = _build_filters(
        period=period, bank=bank, category=category,
        amount_min=amount_min, amount_max=amount_max,
        date_from=date_from, date_to=date_to, search=search,
    )

    all_conds: List[str] = []
    all_params: list = []
    if status:
        all_conds.append("status = ?")
        all_params.append(status)
    all_conds.extend(filter_conds)
    all_params.extend(filter_params)

    where = ("WHERE " + " AND ".join(all_conds)) if all_conds else ""

    total = conn.execute(f"SELECT COUNT(*) FROM transactions {where}", all_params).fetchone()[0]
    rows = conn.execute(
        f"SELECT * FROM transactions {where} ORDER BY tx_date DESC LIMIT ? OFFSET ?",
        all_params + [page_size, offset],
    ).fetchall()

    conn.close()
    pages = math.ceil(total / page_size) if total > 0 else 1
    return {
        "items": [dict(r) for r in rows],
        "total": total,
        "page": page,
        "page_size": page_size,
        "pages": pages,
    }


def update_transaction(tx_id: int, updates: dict):
    conn = get_db()
    allowed = ["amount", "tx_type", "merchant", "bank", "category", "tx_date", "status", "confidence"]
    fields = {k: v for k, v in updates.items() if k in allowed}
    if not fields:
        conn.close()
        return

    set_clause = ", ".join(f"{k}=?" for k in fields)
    values = list(fields.values()) + [tx_id]
    conn.execute(f"UPDATE transactions SET {set_clause} WHERE id=?", values)
    conn.commit()

    # If category was changed, learn merchant → category mapping
    if "category" in fields:
        merchant_row = conn.execute(
            "SELECT merchant FROM transactions WHERE id=?", (tx_id,)
        ).fetchone()
        if merchant_row and merchant_row["merchant"]:
            conn.execute(
                """INSERT INTO merchant_categories (merchant, category, updated_at)
                   VALUES (?, ?, ?)
                   ON CONFLICT(merchant) DO UPDATE SET
                     category=excluded.category, updated_at=excluded.updated_at""",
                (merchant_row["merchant"], fields["category"], datetime.now().isoformat()),
            )
            conn.commit()

    conn.close()


def delete_transaction(tx_id: int):
    conn = get_db()
    conn.execute("DELETE FROM transactions WHERE id=?", (tx_id,))
    conn.commit()
    conn.close()


# ─── SUMMARY ─────────────────────────────────────────────────────

def get_summary(
    period: str = "month",
    bank: Optional[str] = None,
    category: Optional[str] = None,
    amount_min: Optional[float] = None,
    amount_max: Optional[float] = None,
    date_from: Optional[str] = None,
    date_to: Optional[str] = None,
    search: Optional[str] = None,
) -> dict:
    conn = get_db()

    filter_conds, filter_params = _build_filters(
        period=period, bank=bank, category=category,
        amount_min=amount_min, amount_max=amount_max,
        date_from=date_from, date_to=date_to, search=search,
    )
    all_conds = ["status='confirmed'"] + filter_conds
    where = "WHERE " + " AND ".join(all_conds)

    row = conn.execute(
        f"""SELECT
              COALESCE(SUM(CASE WHEN tx_type='credit' THEN amount ELSE 0 END), 0) AS income,
              COALESCE(SUM(CASE WHEN tx_type='debit'  THEN amount ELSE 0 END), 0) AS expenses
            FROM transactions {where}""",
        filter_params,
    ).fetchone()

    conn.close()
    income = row["income"]
    expenses = row["expenses"]
    return {
        "income": income,
        "expenses": expenses,
        "net": income - expenses,
        "period": period,
        "month": date_type.today().strftime("%Y-%m"),
    }


def get_category_summary(
    period: str = "month",
    bank: Optional[str] = None,
    category: Optional[str] = None,
    amount_min: Optional[float] = None,
    amount_max: Optional[float] = None,
    date_from: Optional[str] = None,
    date_to: Optional[str] = None,
    search: Optional[str] = None,
) -> List[dict]:
    conn = get_db()

    filter_conds, filter_params = _build_filters(
        period=period, bank=bank, category=category,
        amount_min=amount_min, amount_max=amount_max,
        date_from=date_from, date_to=date_to, search=search,
    )
    all_conds = ["tx_type='debit'", "status='confirmed'"] + filter_conds
    where = "WHERE " + " AND ".join(all_conds)

    rows = conn.execute(
        f"""SELECT COALESCE(category, 'other') AS category,
                   COALESCE(SUM(amount), 0) AS total
            FROM transactions {where}
            GROUP BY category
            ORDER BY total DESC""",
        filter_params,
    ).fetchall()

    conn.close()
    grand_total = sum(r["total"] for r in rows)
    return [
        {
            "category": r["category"],
            "total": r["total"],
            "percentage": round(r["total"] / grand_total * 100, 1) if grand_total > 0 else 0,
        }
        for r in rows
        if r["total"] > 0
    ]


# ─── SYNC LOG ────────────────────────────────────────────────────

def log_sync(emails_found, parsed_ok, pending):
    conn = get_db()
    conn.execute(
        "INSERT INTO sync_log (emails_found, parsed_ok, pending) VALUES (?, ?, ?)",
        (emails_found, parsed_ok, pending),
    )
    conn.commit()
    conn.close()


def get_last_sync():
    conn = get_db()
    row = conn.execute("SELECT * FROM sync_log ORDER BY id DESC LIMIT 1").fetchone()
    conn.close()
    return dict(row) if row else None


# ─── CATEGORY MAPPINGS ───────────────────────────────────────────

def get_learned_category(merchant: str) -> Optional[str]:
    if not merchant:
        return None
    conn = get_db()
    row = conn.execute(
        "SELECT category FROM merchant_categories WHERE LOWER(merchant)=LOWER(?)",
        (merchant,),
    ).fetchone()
    conn.close()
    return row["category"] if row else None


def get_category_mappings() -> List[dict]:
    conn = get_db()
    rows = conn.execute(
        "SELECT merchant, category, updated_at FROM merchant_categories ORDER BY merchant"
    ).fetchall()
    conn.close()
    return [dict(r) for r in rows]


def upsert_category_mapping(merchant: str, category: str):
    conn = get_db()
    conn.execute(
        """INSERT INTO merchant_categories (merchant, category, updated_at)
           VALUES (?, ?, ?)
           ON CONFLICT(merchant) DO UPDATE SET
             category=excluded.category, updated_at=excluded.updated_at""",
        (merchant, category, datetime.now().isoformat()),
    )
    conn.commit()
    conn.close()


def delete_category_mapping(merchant: str):
    conn = get_db()
    conn.execute("DELETE FROM merchant_categories WHERE LOWER(merchant)=LOWER(?)", (merchant,))
    conn.commit()
    conn.close()


# ─── MERCHANT RULES ──────────────────────────────────────────────

def get_merchant_rules() -> List[Tuple[str, str]]:
    """
    Return all merchant normalization rules as (pattern, clean_name) pairs.
    Custom rules (is_default=0) appear before default rules (is_default=1).
    """
    conn = get_db()
    rows = conn.execute(
        "SELECT pattern, clean_name FROM merchant_rules ORDER BY is_default ASC, id ASC"
    ).fetchall()
    conn.close()
    return [(r["pattern"], r["clean_name"]) for r in rows]


# ─── BUDGETS (US-21) ─────────────────────────────────────────────

def get_budgets(month: str) -> dict:
    """Return budgets + actual spend for every category in the given month."""
    conn = get_db()
    start, end = _month_range(month)

    # Actual spend per category this month
    actual_rows = conn.execute(
        """SELECT COALESCE(category, 'other') AS category,
                  COALESCE(SUM(amount), 0) AS actual
           FROM transactions
           WHERE tx_type = 'debit' AND status = 'confirmed'
             AND tx_date >= ? AND tx_date < ?
           GROUP BY category""",
        (start, end),
    ).fetchall()
    actuals = {r["category"]: r["actual"] for r in actual_rows}

    # Budget amounts stored for this month
    budget_rows = conn.execute(
        "SELECT category, amount FROM budgets WHERE month = ?", (month,)
    ).fetchall()
    budgets = {r["category"]: r["amount"] for r in budget_rows}

    conn.close()

    # Merge: all categories that have a budget or actual spend
    all_cats = set(actuals.keys()) | set(budgets.keys())
    categories = []
    for cat in sorted(all_cats):
        budget = budgets.get(cat, 0)
        actual = actuals.get(cat, 0)
        categories.append({
            "category": cat,
            "budget": budget,
            "actual": actual,
            "remaining": budget - actual,
        })

    # Sort: budgeted categories first (by actual desc), then unbudgeted by actual desc
    categories.sort(key=lambda c: (0 if c["budget"] > 0 else 1, -c["actual"]))

    # Projection metadata
    year_n, mo_n = int(month[:4]), int(month[5:7])
    days_in_month = calendar.monthrange(year_n, mo_n)[1]
    today = date_type.today()
    cur_month = today.strftime("%Y-%m")
    if month == cur_month:
        days_elapsed = today.day
    elif month < cur_month:
        days_elapsed = days_in_month  # past month, fully elapsed
    else:
        days_elapsed = 0  # future month

    return {
        "month": month,
        "days_in_month": days_in_month,
        "days_elapsed": days_elapsed,
        "categories": categories,
    }


def upsert_budget(category: str, amount: float, month: str):
    """Create or update a budget amount for a category+month."""
    conn = get_db()
    conn.execute(
        """INSERT INTO budgets (category, month, amount)
           VALUES (?, ?, ?)
           ON CONFLICT(category, month) DO UPDATE SET amount = excluded.amount""",
        (category, month, amount),
    )
    conn.commit()
    conn.close()


def suggest_budgets(month: str) -> List[dict]:
    """Suggest budget amounts from the previous month's actual spending."""
    year, mo = int(month[:4]), int(month[5:7])
    prev_mo   = mo - 1 if mo > 1 else 12
    prev_year = year if mo > 1 else year - 1
    prev_month = f"{prev_year}-{prev_mo:02d}"
    prev_start, prev_end = _month_range(prev_month)

    conn = get_db()
    rows = conn.execute(
        """SELECT COALESCE(category, 'other') AS category,
                  COALESCE(SUM(amount), 0) AS total
           FROM transactions
           WHERE tx_type = 'debit' AND status = 'confirmed'
             AND tx_date >= ? AND tx_date < ?
           GROUP BY category
           HAVING total > 0""",
        (prev_start, prev_end),
    ).fetchall()
    conn.close()

    suggestions = []
    for r in rows:
        suggested = math.ceil(r["total"] / 1000) * 1000  # round up to nearest ₨1,000
        suggestions.append({"category": r["category"], "amount": suggested})

    return suggestions


# ─── MONTHLY REPORT (US-22) ──────────────────────────────────────

def get_monthly_report(month: str) -> dict:
    """Full monthly snapshot report: 6 sections pre-calculated."""
    conn = get_db()
    start, end = _month_range(month)

    # Previous month range for comparison
    year, mo = int(month[:4]), int(month[5:7])
    prev_mo   = mo - 1 if mo > 1 else 12
    prev_year = year if mo > 1 else year - 1
    prev_month  = f"{prev_year}-{prev_mo:02d}"
    prev_start, prev_end = _month_range(prev_month)

    # ── Section 1: Key Numbers ──────────────────────────────
    totals_row = conn.execute(
        """SELECT
             COALESCE(SUM(CASE WHEN tx_type='credit' THEN amount ELSE 0 END), 0) AS income,
             COALESCE(SUM(CASE WHEN tx_type='debit'  THEN amount ELSE 0 END), 0) AS expenses,
             COUNT(*) AS tx_count
           FROM transactions
           WHERE status = 'confirmed' AND tx_date >= ? AND tx_date < ?""",
        (start, end),
    ).fetchone()
    income   = totals_row["income"]
    expenses = totals_row["expenses"]
    savings  = income - expenses

    # ── Section 2: Expense Breakdown by Category ────────────
    cat_rows = conn.execute(
        """SELECT COALESCE(category, 'other') AS category,
                  COALESCE(SUM(amount), 0) AS total
           FROM transactions
           WHERE tx_type = 'debit' AND status = 'confirmed'
             AND tx_date >= ? AND tx_date < ?
           GROUP BY category
           ORDER BY total DESC""",
        (start, end),
    ).fetchall()
    grand_exp = sum(r["total"] for r in cat_rows)
    by_category = [
        {
            "category":   r["category"],
            "total":      r["total"],
            "percentage": round(r["total"] / grand_exp * 100, 1) if grand_exp > 0 else 0,
        }
        for r in cat_rows if r["total"] > 0
    ]

    # ── Section 3: Top 5 Merchants ──────────────────────────
    merch_rows = conn.execute(
        """SELECT COALESCE(merchant, 'Unknown') AS merchant,
                  COALESCE(SUM(amount), 0) AS total,
                  COUNT(*) AS tx_count
           FROM transactions
           WHERE tx_type = 'debit' AND status = 'confirmed'
             AND tx_date >= ? AND tx_date < ?
           GROUP BY merchant
           ORDER BY total DESC
           LIMIT 5""",
        (start, end),
    ).fetchall()
    top_merchants = [dict(r) for r in merch_rows]

    # ── Section 4: Top 3 Biggest Single Expenses ────────────
    big_rows = conn.execute(
        """SELECT COALESCE(merchant, 'Unknown') AS merchant,
                  amount, tx_date,
                  COALESCE(category, 'other') AS category
           FROM transactions
           WHERE tx_type = 'debit' AND status = 'confirmed'
             AND tx_date >= ? AND tx_date < ?
           ORDER BY amount DESC
           LIMIT 3""",
        (start, end),
    ).fetchall()
    biggest_transactions = [dict(r) for r in big_rows]

    # ── Section 5: vs Last Month ────────────────────────────
    prev_row = conn.execute(
        """SELECT
             COALESCE(SUM(CASE WHEN tx_type='credit' THEN amount ELSE 0 END), 0) AS income,
             COALESCE(SUM(CASE WHEN tx_type='debit'  THEN amount ELSE 0 END), 0) AS expenses
           FROM transactions
           WHERE status = 'confirmed' AND tx_date >= ? AND tx_date < ?""",
        (prev_start, prev_end),
    ).fetchone()
    prev_income   = prev_row["income"]
    prev_expenses = prev_row["expenses"]
    prev_savings  = prev_income - prev_expenses

    def pct_chg(curr, prev):
        if prev == 0:
            return None
        return round((curr - prev) / abs(prev) * 100, 1)

    vs_last_month = {
        "income_change":  income   - prev_income,
        "expense_change": expenses - prev_expenses,
        "savings_change": savings  - prev_savings,
        "income_pct":     pct_chg(income,   prev_income),
        "expense_pct":    pct_chg(expenses, prev_expenses),
        "savings_pct":    pct_chg(savings,  prev_savings),
        "prev_month":     prev_month,
    }

    # ── Section 6: By Source / Bank ─────────────────────────
    src_rows = conn.execute(
        """SELECT COALESCE(bank, 'Unknown') AS bank, COUNT(*) AS count
           FROM transactions
           WHERE status = 'confirmed' AND tx_date >= ? AND tx_date < ?
           GROUP BY bank
           ORDER BY count DESC""",
        (start, end),
    ).fetchall()
    by_source = [dict(r) for r in src_rows]

    conn.close()

    return {
        "month":                month,
        "totals":               {"income": income, "expenses": expenses, "savings": savings, "tx_count": totals_row["tx_count"]},
        "by_category":          by_category,
        "top_merchants":        top_merchants,
        "biggest_transactions": biggest_transactions,
        "vs_last_month":        vs_last_month,
        "by_source":            by_source,
    }
