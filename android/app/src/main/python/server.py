import asyncio
import hashlib
import hmac
import os
import re
from datetime import datetime, timedelta
from datetime import date as date_type
from functools import partial
from typing import Optional
from fastapi import FastAPI, HTTPException, Request
from fastapi.staticfiles import StaticFiles
from fastapi.responses import HTMLResponse, JSONResponse
from dotenv import load_dotenv

from database import (
    init_db,
    insert_transaction, get_transactions, update_transaction, delete_transaction,
    log_sync, get_last_sync,
    get_summary, get_category_summary,
    get_category_mappings, upsert_category_mapping, delete_category_mapping,
    get_budgets, upsert_budget, suggest_budgets,
    get_monthly_report,
    get_setting, set_setting,
    get_user_banks, add_user_bank, delete_user_bank,
)
from auth_gmail import get_gmail_service, search_emails, get_account_email, OAuthRequiredError
from bank_registry import BANK_REGISTRY, entries_from_user_banks, find_bank, get_query_domains
from generic_parser import SKIP
from parsers import parse_email

# On Android, FINTRACK_APP_DIR is injected into os.environ by ServerProcessManager
# and points to filesDir. On desktop it's unset. Used for the optional .env only.
BASE_DIR = os.environ.get("FINTRACK_APP_DIR") or os.path.dirname(os.path.abspath(__file__))
load_dotenv(os.path.join(BASE_DIR, ".env"))

# The web frontend (index.html + static/) always ships alongside this file:
# repo root on desktop, the Chaquopy module dir on Android. It is NOT extracted
# into filesDir, so serve it from the module directory, not FINTRACK_APP_DIR.
STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")

PIN  = os.getenv("PIN",  "1234")
HOST = os.getenv("HOST", "127.0.0.1")
PORT = int(os.getenv("PORT", "8000"))

_MAX_SYNC_DAYS  = 90


# NOTE: FastAPI 0.88 (Android/Chaquopy pin) does not support the lifespan=
# constructor argument — it is silently ignored, so init_db() would never
# run. Use the legacy @app.on_event hook instead (desktop server.py uses
# lifespan on FastAPI 0.115).
app = FastAPI()


@app.on_event("startup")
async def _startup():
    init_db()
    print(f"\n  FinTrack PK running at http://{HOST}:{PORT}\n")
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


# ─── API AUTHORIZATION ───────────────────────────────────────────
#
# Every /api/* request must carry the per-launch capability token that the
# native layer generates (ServerProcessManager injects FINTRACK_API_TOKEN
# into this process; WebViewManager hands the same value to the frontend,
# which sends it as the X-API-Token header). There is no process-global
# session state: a request either presents the current launch's token or
# is rejected. Read the env var per request so a server restart inside a
# long-lived process never compares against a stale copy.

def _expected_api_token():
    # type: () -> str
    return os.environ.get("FINTRACK_API_TOKEN", "")


@app.middleware("http")
async def require_api_token(request: Request, call_next):
    if request.url.path.startswith("/api/"):
        expected = _expected_api_token()
        provided = request.headers.get("x-api-token", "")
        if not expected or not hmac.compare_digest(provided, expected):
            return JSONResponse(status_code=401, content={"detail": "Not authenticated"})
    return await call_next(request)


@app.get("/health")
async def health():
    """Unauthenticated liveness probe for the native process manager."""
    return {"status": "ok"}


def _pin_matches(submitted: str) -> bool:
    """Check a submitted PIN against the user-set PIN (settings table),
    falling back to the env-var default when none has been set."""
    stored_hash = get_setting("pin_hash")
    if stored_hash:
        submitted_hash = hashlib.sha256(submitted.encode()).hexdigest()
        return hmac.compare_digest(submitted_hash, stored_hash)
    return hmac.compare_digest(submitted, PIN)


# ─── PROFILE ─────────────────────────────────────────────────────

@app.get("/api/profile")
async def get_profile():
    email = get_setting("gmail_email")
    if not email:
        loop = asyncio.get_running_loop()
        email = await loop.run_in_executor(None, get_account_email)
        if email:
            set_setting("gmail_email", email)
    return {
        "name": get_setting("profile_name") or "User",
        "email": email,
    }


@app.put("/api/profile")
async def update_profile(request: Request):
    body = await request.json()
    name = str(body.get("name") or "").strip()
    if not name or len(name) > 50:
        raise HTTPException(status_code=400, detail="Name must be 1-50 characters")
    set_setting("profile_name", name)
    return {"ok": True}


@app.post("/api/profile/pin")
async def change_pin(request: Request):
    body = await request.json()
    current = str(body.get("current_pin") or "")
    new = str(body.get("new_pin") or "")
    if not _pin_matches(current):
        raise HTTPException(status_code=401, detail="Current PIN is incorrect")
    if not (new.isdigit() and len(new) == 4):
        raise HTTPException(status_code=400, detail="New PIN must be exactly 4 digits")
    set_setting("pin_hash", hashlib.sha256(new.encode()).hexdigest())
    return {"ok": True}


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
    except OAuthRequiredError as e:
        # 428 Precondition Required — frontend triggers the native OAuth flow
        # (AndroidBridge.requestSync()) and retries the sync once connected.
        raise HTTPException(status_code=428, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Gmail auth failed: {e}")

    user_entries = entries_from_user_banks(get_user_banks())

    query = "from:(" + " OR ".join(get_query_domains(user_entries)) + ")"
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
    unparsed         = 0
    skipped_parse    = 0
    skipped_duplicate = 0

    for email in emails:
        text = email["body"] or email["snippet"]
        if not text:
            skipped_parse += 1
            continue

        result = parse_email(text, email["sender"], email["gmail_id"], email.get("date", ""), extra_entries=user_entries)
        if result is None or result is SKIP:
            # Unregistered sender, or a recognised non-transaction email
            # (OTP / login / promo) — nothing to import.
            skipped_parse += 1
            continue

        inserted = insert_transaction(result)
        if inserted:
            if result["confidence"] == "high":
                parsed_ok += 1
            else:
                # medium/low/failed all land in Pending Review;
                # 'failed' rows are stubs with only raw_text to correct.
                pending += 1
                if result["confidence"] == "failed":
                    unparsed += 1
        else:
            skipped_duplicate += 1

    log_sync(total, parsed_ok, pending)

    return {
        "emails_found":     total,
        "parsed_ok":        parsed_ok,
        "pending_review":   pending,
        "unparsed":         unparsed,
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
    return get_transactions(
        status=status, page=page, page_size=page_size,
        period=period, bank=bank, category=category,
        amount_min=amount_min, amount_max=amount_max,
        date_from=date_from, date_to=date_to, search=search,
    )


@app.post("/api/transactions")
async def create_transaction(request: Request):
    """Manually add a transaction (US-10)."""
    body = await request.json()

    for field in ["amount", "tx_type", "merchant", "bank"]:
        if field not in body:
            raise HTTPException(status_code=400, detail=f"Missing required field: {field}")

    try:
        amount = float(body["amount"])
    except (TypeError, ValueError):
        raise HTTPException(status_code=400, detail="amount must be a number")

    tx = {
        "gmail_id":  None,
        "amount":    amount,
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
    body = await request.json()
    update_transaction(tx_id, body)
    return {"ok": True}


@app.delete("/api/transactions/{tx_id}")
async def remove_transaction(tx_id: int):
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
    return get_category_summary(
        period=period, bank=bank, category=category,
        amount_min=amount_min, amount_max=amount_max,
        date_from=date_from, date_to=date_to, search=search,
    )


# ─── SYNC STATUS ─────────────────────────────────────────────────

@app.get("/api/sync/status")
async def sync_status():
    return get_last_sync() or {"message": "No sync yet"}


# ─── CATEGORY MAPPINGS ───────────────────────────────────────────

@app.get("/api/categories/mappings")
async def list_mappings():
    return get_category_mappings()


@app.post("/api/categories/mappings")
async def add_mapping(request: Request):
    body     = await request.json()
    merchant = (body.get("merchant") or "").strip()
    category = (body.get("category") or "").strip()
    if not merchant or not category:
        raise HTTPException(status_code=400, detail="merchant and category are required")
    upsert_category_mapping(merchant, category)
    return {"ok": True}


@app.delete("/api/categories/mappings/{merchant}")
async def remove_mapping(merchant: str):
    delete_category_mapping(merchant)
    return {"ok": True}


# ─── USER BANKS ──────────────────────────────────────────────────

# Free-mail providers can never be a bank's alert sender; adding one would
# flood the sync query with personal mail.
_BLOCKED_BANK_DOMAINS = {
    "gmail.com", "googlemail.com", "yahoo.com", "outlook.com",
    "hotmail.com", "live.com", "icloud.com", "proton.me", "protonmail.com",
}

# A bare domain (bank.com) or a full sender address (alerts@bank.com).
_ADDRESS_SHAPE_RE = re.compile(r"^(?:[a-z0-9._%+-]+@)?[a-z0-9][a-z0-9.-]*\.[a-z]{2,}$")


@app.get("/api/banks")
async def list_banks():
    return {
        "builtin": BANK_REGISTRY,
        "custom": get_user_banks(),
    }


@app.post("/api/banks")
async def add_bank(request: Request):
    body    = await request.json()
    bank    = str(body.get("bank") or "").strip()
    address = str(body.get("address") or "").strip().lower()

    if not bank or not address:
        raise HTTPException(status_code=400, detail="bank and address are required")
    if len(bank) > 50:
        raise HTTPException(status_code=400, detail="Bank name must be 50 characters or fewer")
    if not _ADDRESS_SHAPE_RE.match(address):
        raise HTTPException(status_code=400, detail="Enter a sender address (alerts@bank.com) or domain (bank.com)")

    domain = address.rsplit("@", 1)[1] if "@" in address else address
    if domain in _BLOCKED_BANK_DOMAINS:
        raise HTTPException(status_code=400, detail=f"{domain} is a personal email provider, not a bank sender")

    # Reject anything already covered by the built-in registry or an
    # existing custom entry (probe as a full address so domain rules match).
    probe = address if "@" in address else "probe@" + address
    existing = find_bank(probe, entries_from_user_banks(get_user_banks()))
    if existing:
        raise HTTPException(status_code=400, detail=f"Already covered by {existing['bank']}")

    row = add_user_bank(bank, address)
    if row is None:
        raise HTTPException(status_code=400, detail="This address has already been added")
    return row


@app.delete("/api/banks/{bank_id}")
async def remove_bank(bank_id: int):
    delete_user_bank(bank_id)
    return {"ok": True}


# ─── BUDGETS (US-21) ─────────────────────────────────────────────

@app.get("/api/budgets")
async def get_budgets_endpoint(month: str = None):
    if not month:
        month = date_type.today().strftime("%Y-%m")
    return get_budgets(month)


@app.put("/api/budgets")
async def upsert_budget_endpoint(request: Request):
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
    if not month:
        month = date_type.today().strftime("%Y-%m")
    return get_monthly_report(month)


# ─── FRONTEND ────────────────────────────────────────────────────

@app.get("/")
async def index():
    with open(os.path.join(STATIC_DIR, "index.html")) as f:
        return HTMLResponse(f.read())


if __name__ == "__main__":
    import uvicorn
    dev_mode = os.getenv("FINTRACK_DEV", "").lower() in ("1", "true")
    uvicorn.run("server:app", host=HOST, port=PORT, reload=dev_mode)
