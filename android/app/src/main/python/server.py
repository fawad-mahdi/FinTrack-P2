import asyncio
import os
from contextlib import asynccontextmanager
from datetime import datetime, timedelta
from datetime import date as date_type
from functools import partial
from typing import Optional
from fastapi import FastAPI, HTTPException, Request
from fastapi.staticfiles import StaticFiles
from fastapi.responses import HTMLResponse
from dotenv import load_dotenv

from database import (
    init_db,
    insert_transaction, get_transactions, update_transaction, delete_transaction,
    log_sync, get_last_sync,
    get_summary, get_category_summary,
    get_category_mappings, upsert_category_mapping, delete_category_mapping,
    get_budgets, upsert_budget, suggest_budgets,
    get_monthly_report,
)
from auth_gmail import get_gmail_service, search_emails
from parsers import parse_email, BANK_CONFIG

# On Android, FINTRACK_APP_DIR is injected into os.environ by ServerProcessManager
# and points to filesDir (where static/index.html is extracted). On desktop it's unset.
BASE_DIR = os.environ.get("FINTRACK_APP_DIR") or os.path.dirname(os.path.abspath(__file__))
load_dotenv(os.path.join(BASE_DIR, ".env"))

PIN  = os.getenv("PIN",  "1234")
HOST = os.getenv("HOST", "127.0.0.1")
PORT = int(os.getenv("PORT", "8000"))

_authenticated  = set()
_MAX_SYNC_DAYS  = 90


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    print(f"\n  FinTrack PK running at http://{HOST}:{PORT}")
    print(f"  PIN: {PIN}\n")
    yield


app = FastAPI(lifespan=lifespan)
app.mount("/static", StaticFiles(directory=os.path.join(BASE_DIR, "static")), name="static")


# ─── PIN AUTH ────────────────────────────────────────────────────

@app.post("/api/auth")
async def authenticate(request: Request):
    body = await request.json()
    if body.get("pin") == PIN:
        _authenticated.add("user")
        return {"ok": True}
    raise HTTPException(status_code=401, detail="Wrong PIN")


def check_auth():
    if "user" not in _authenticated:
        raise HTTPException(status_code=401, detail="Not authenticated")


# ─── GMAIL SYNC (US-14, US-15) ───────────────────────────────────

@app.post("/api/sync")
async def sync_gmail(request: Request):
    """
    Sync Gmail bank alerts and import transactions.
    
    US-15 Duplicate Prevention:
    - Tracks skipped_duplicate count separately in response
    - Duplicates are detected by gmail_id UNIQUE constraint
    - User-edited transactions are never overwritten (insert_transaction only does INSERT)
    - Frontend displays "already imported" count to user
    """
    check_auth()

    body: dict = {}
    try:
        body = await request.json()
    except Exception:
        pass

    date_from_str: Optional[str] = body.get("date_from")
    date_to_str:   Optional[str] = body.get("date_to")

    today   = date_type.today()
    max_from = today - timedelta(days=_MAX_SYNC_DAYS)

    if date_from_str:
        try:
            df = date_type.fromisoformat(date_from_str[:10])
            if df < max_from:
                df = max_from
            date_from_str = df.isoformat()
        except ValueError:
            date_from_str = None

    if date_to_str:
        try:
            date_to_str = date_type.fromisoformat(date_to_str[:10]).isoformat()
        except ValueError:
            date_to_str = None

    max_results = 100
    if date_from_str:
        end        = date_type.fromisoformat(date_to_str) if date_to_str else today
        delta_days = (end - date_type.fromisoformat(date_from_str)).days
        if delta_days > 30:
            max_results = 500

    loop = asyncio.get_running_loop()

    try:
        service = await loop.run_in_executor(None, get_gmail_service)
    except FileNotFoundError as e:
        raise HTTPException(status_code=500, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Gmail auth failed: {e}")

    senders = [config["sender"] for config in BANK_CONFIG.values()]
    query   = " OR ".join(f"from:{s}" for s in senders)
    if date_from_str:
        query += f" after:{date_from_str.replace('-', '/')}"
    if date_to_str:
        before_dt = date_type.fromisoformat(date_to_str) + timedelta(days=1)
        query    += f" before:{before_dt.isoformat().replace('-', '/')}"

    try:
        emails = await loop.run_in_executor(
            None, partial(search_emails, service, query, max_results)
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Gmail search failed: {e}")

    total            = len(emails)
    parsed_ok        = 0
    pending          = 0
    skipped_parse    = 0
    skipped_duplicate = 0

    for email in emails:
        text = email["body"] or email["snippet"]
        if not text:
            skipped_parse += 1
            continue

        result = parse_email(text, email["sender"], email["gmail_id"], email.get("date", ""))
        if result is None:
            skipped_parse += 1
            continue

        inserted = insert_transaction(result)
        if inserted:
            if result["confidence"] == "high":
                parsed_ok += 1
            else:
                pending += 1
        else:
            skipped_duplicate += 1

    log_sync(total, parsed_ok, pending)

    return {
        "emails_found":     total,
        "parsed_ok":        parsed_ok,
        "pending_review":   pending,
        "skipped_parse":    skipped_parse,
        "skipped_duplicate": skipped_duplicate,
        "date_from":        date_from_str,
        "date_to":          date_to_str,
    }


# ─── TRANSACTIONS ────────────────────────────────────────────────

@app.get("/api/transactions")
async def list_transactions(
    status:     str   = None,
    page:       int   = 1,
    page_size:  int   = 25,
    period:     str   = None,
    bank:       str   = None,
    category:   str   = None,
    amount_min: float = None,
    amount_max: float = None,
    date_from:  str   = None,
    date_to:    str   = None,
    search:     str   = None,
):
    check_auth()
    return get_transactions(
        status=status, page=page, page_size=page_size,
        period=period, bank=bank, category=category,
        amount_min=amount_min, amount_max=amount_max,
        date_from=date_from, date_to=date_to, search=search,
    )


@app.post("/api/transactions")
async def create_transaction(request: Request):
    """Manually add a transaction (US-10)."""
    check_auth()
    body = await request.json()

    for field in ["amount", "tx_type", "merchant", "bank"]:
        if field not in body:
            raise HTTPException(status_code=400, detail=f"Missing required field: {field}")

    tx = {
        "gmail_id":  None,
        "amount":    float(body["amount"]),
        "tx_type":   body["tx_type"],
        "merchant":  body.get("merchant", "").strip(),
        "bank":      body["bank"],
        "category":  body.get("category", "other"),
        "tx_date":   body.get("tx_date") or datetime.now().isoformat(),
        "raw_text":  "",
        "confidence": "high",
        "source":    "manual",
    }
    insert_transaction(tx)
    return {"ok": True}


@app.post("/api/transactions/cash")
async def create_cash_transaction(request: Request):
    """Cash expense quick-add (US-20)."""
    check_auth()
    body = await request.json()

    try:
        amount = float(body.get("amount", 0))
    except (TypeError, ValueError):
        raise HTTPException(status_code=400, detail="amount must be a number")
    if amount <= 0:
        raise HTTPException(status_code=400, detail="amount must be positive")

    note     = (body.get("note") or "").strip() or "Cash Expense"
    category = body.get("category") or "other"
    date_str = body.get("date") or ""
    if date_str and len(date_str) == 10:      # YYYY-MM-DD → ISO datetime
        date_str = date_str + "T00:00:00"
    if not date_str:
        date_str = datetime.now().isoformat()

    tx = {
        "gmail_id":   None,
        "amount":     amount,
        "tx_type":    "debit",
        "merchant":   note,
        "bank":       "Cash",
        "category":   category,
        "tx_date":    date_str,
        "raw_text":   "",
        "confidence": "high",
        "source":     "cash",
    }
    insert_transaction(tx)
    return {"ok": True}


@app.patch("/api/transactions/{tx_id}")
async def patch_transaction(tx_id: int, request: Request):
    check_auth()
    body = await request.json()
    update_transaction(tx_id, body)
    return {"ok": True}


@app.delete("/api/transactions/{tx_id}")
async def remove_transaction(tx_id: int):
    check_auth()
    delete_transaction(tx_id)
    return {"ok": True}


# ─── SUMMARY ─────────────────────────────────────────────────────

@app.get("/api/summary")
async def summary(
    period:     str   = "month",
    bank:       str   = None,
    category:   str   = None,
    amount_min: float = None,
    amount_max: float = None,
    date_from:  str   = None,
    date_to:    str   = None,
    search:     str   = None,
):
    check_auth()
    return get_summary(
        period=period, bank=bank, category=category,
        amount_min=amount_min, amount_max=amount_max,
        date_from=date_from, date_to=date_to, search=search,
    )


@app.get("/api/summary/categories")
async def category_summary(
    period:     str   = "month",
    bank:       str   = None,
    category:   str   = None,
    amount_min: float = None,
    amount_max: float = None,
    date_from:  str   = None,
    date_to:    str   = None,
    search:     str   = None,
):
    check_auth()
    return get_category_summary(
        period=period, bank=bank, category=category,
        amount_min=amount_min, amount_max=amount_max,
        date_from=date_from, date_to=date_to, search=search,
    )


# ─── SYNC STATUS ─────────────────────────────────────────────────

@app.get("/api/sync/status")
async def sync_status():
    check_auth()
    return get_last_sync() or {"message": "No sync yet"}


# ─── CATEGORY MAPPINGS ───────────────────────────────────────────

@app.get("/api/categories/mappings")
async def list_mappings():
    check_auth()
    return get_category_mappings()


@app.post("/api/categories/mappings")
async def add_mapping(request: Request):
    check_auth()
    body     = await request.json()
    merchant = (body.get("merchant") or "").strip()
    category = (body.get("category") or "").strip()
    if not merchant or not category:
        raise HTTPException(status_code=400, detail="merchant and category are required")
    upsert_category_mapping(merchant, category)
    return {"ok": True}


@app.delete("/api/categories/mappings/{merchant}")
async def remove_mapping(merchant: str):
    check_auth()
    delete_category_mapping(merchant)
    return {"ok": True}


# ─── BUDGETS (US-21) ─────────────────────────────────────────────

@app.get("/api/budgets")
async def get_budgets_endpoint(month: str = None):
    check_auth()
    if not month:
        month = date_type.today().strftime("%Y-%m")
    return get_budgets(month)


@app.put("/api/budgets")
async def upsert_budget_endpoint(request: Request):
    check_auth()
    body     = await request.json()
    category = (body.get("category") or "").strip()
    if not category:
        raise HTTPException(status_code=400, detail="category is required")
    try:
        amount = float(body.get("amount", 0))
    except (TypeError, ValueError):
        raise HTTPException(status_code=400, detail="amount must be a number")
    month = body.get("month") or date_type.today().strftime("%Y-%m")
    upsert_budget(category, amount, month)
    return {"ok": True}


@app.post("/api/budgets/suggest")
async def suggest_budgets_endpoint(request: Request):
    check_auth()
    body: dict = {}
    try:
        body = await request.json()
    except Exception:
        pass
    month = body.get("month") or date_type.today().strftime("%Y-%m")
    return suggest_budgets(month)


# ─── MONTHLY REPORT (US-22) ──────────────────────────────────────

@app.get("/api/report")
async def monthly_report(month: str = None):
    check_auth()
    if not month:
        month = date_type.today().strftime("%Y-%m")
    return get_monthly_report(month)


# ─── FRONTEND ────────────────────────────────────────────────────

@app.get("/")
async def index():
    with open(os.path.join(BASE_DIR, "static", "index.html")) as f:
        return HTMLResponse(f.read())


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("server:app", host=HOST, port=PORT, reload=True)
