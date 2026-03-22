import asyncio
import os
from datetime import datetime, timedelta
from datetime import date as date_type
from typing import Optional
from fastapi import FastAPI, HTTPException, Request
from fastapi.staticfiles import StaticFiles
from fastapi.responses import HTMLResponse

from database import (
    init_db,
    insert_transaction, get_transactions, update_transaction, delete_transaction,
    log_sync, get_last_sync,
    get_summary, get_category_summary,
    get_category_mappings, upsert_category_mapping, delete_category_mapping,
    get_budgets, upsert_budget, suggest_budgets,
    get_monthly_report,
)
from auth_gmail import (
    get_gmail_service, search_emails, OAuthRequiredError,
    build_oauth_url, save_pending_exchange, get_pending_exchange,
    clear_pending_exchange,
)
from parsers import parse_email, BANK_CONFIG

# Android environment paths
# On Android, FINTRACK_APP_DIR points to filesDir where static assets are extracted.
# On desktop fallback, use the script directory.
BASE_DIR = os.environ.get("FINTRACK_APP_DIR") or os.path.dirname(os.path.abspath(__file__))
CONFIG_DIR = os.environ.get('FINTRACK_CONFIG_DIR', BASE_DIR)
LOGS_DIR = os.environ.get('FINTRACK_LOGS_DIR', BASE_DIR)

# Default PIN for Android (will be managed by Android layer)
PIN  = os.environ.get("PIN",  "1234")
HOST = os.environ.get("HOST", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8000"))

_authenticated  = set()
_MAX_SYNC_DAYS  = 90


# FastAPI 0.88.0 (Chaquopy/Android) does NOT support the lifespan parameter
# (added in 0.93.0). Use on_event("startup") instead.
app = FastAPI()


@app.on_event("startup")
async def startup():
    init_db()
    print(f"\n  FinTrack PK running at http://{HOST}:{PORT}")
    print(f"  PIN: {PIN}\n")

# Mount static files directory (index.html is extracted here by FinTrackApplication)
_static_dir = os.path.join(BASE_DIR, "static")
if os.path.isdir(_static_dir):
    app.mount("/static", StaticFiles(directory=_static_dir), name="static")

# Health check endpoint for Android ServerProcessManager
@app.get("/health")
async def health_check():
    """Health check endpoint for process monitoring."""
    return {"status": "ok", "service": "fintrack-pk"}


# ─── OAUTH (loopback redirect for Android) ──────────────────────

@app.get("/api/oauth/url")
async def oauth_start():
    """Return the Google OAuth authorization URL.

    The redirect_uri points back to this server's /oauth/callback route
    so the token exchange happens automatically.
    """
    redirect_uri = f"http://127.0.0.1:{PORT}/oauth/callback"
    try:
        auth_url, state = build_oauth_url(redirect_uri)
        return {"url": auth_url, "state": state}
    except FileNotFoundError as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/oauth/callback")
async def oauth_callback(request: Request):
    """Google redirects here after user authorizes.

    Exchanges the auth code for tokens, saves token.json,
    and shows a success page.
    """
    code = request.query_params.get("code")
    state = request.query_params.get("state")
    error = request.query_params.get("error")

    if error:
        return HTMLResponse(
            f"<h2>OAuth Error</h2><p>{error}</p>"
            "<p>Close this tab and try again.</p>",
            status_code=400,
        )

    if not code or not state:
        return HTMLResponse(
            "<h2>Missing parameters</h2><p>Close this tab and try again.</p>",
            status_code=400,
        )

    try:
        save_pending_exchange(code, state)
    except Exception as e:
        import traceback
        err_detail = traceback.format_exc()
        print(f"OAuth save pending error: {err_detail}")
        return HTMLResponse(
            f"<h2>Authorization failed</h2><p>{e}</p>"
            "<p>Close this tab and try again.</p>",
            status_code=500,
        )

    # Tell user to return to app — Kotlin side will complete the exchange
    return HTMLResponse("""<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>FinTrack PK</title>
<style>body{font-family:system-ui;display:flex;align-items:center;justify-content:center;
min-height:80vh;margin:0;background:#f5f5f5;color:#333}
.card{background:#fff;padding:40px;border-radius:12px;text-align:center;
box-shadow:0 2px 8px rgba(0,0,0,0.1)}
.ok{font-size:48px;margin-bottom:16px}h2{margin:0 0 8px}</style>
</head><body><div class="card">
<div class="ok">✓</div>
<h2>Gmail Authorized</h2>
<p>Return to FinTrack PK to complete setup.</p>
<p>Tap <b>Sync Gmail</b> to import your transactions.</p>
</div></body></html>""")


@app.get("/api/oauth/pending")
async def oauth_pending():
    """Return pending OAuth exchange data for Kotlin-side token exchange."""
    data = get_pending_exchange()
    if data is None:
        return {"pending": False}
    return {"pending": True, **data}


@app.delete("/api/oauth/pending")
async def oauth_clear_pending():
    """Clear the pending OAuth exchange after Kotlin completes it."""
    clear_pending_exchange()
    return {"ok": True}


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

    The heavy Gmail API work runs in a background thread so the async
    event loop stays free to serve /health checks (prevents false crash
    detection by ServerProcessManager during long syncs).

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

    # --- Run the blocking Gmail work in a thread ---
    def _do_sync():
        try:
            service = get_gmail_service()
        except OAuthRequiredError:
            return {"_error": "oauth_required"}
        except FileNotFoundError as e:
            return {"_error": str(e)}
        except Exception as e:
            return {"_error": f"Gmail auth failed: {e}"}

        senders = [config["sender"] for config in BANK_CONFIG.values()]
        query   = " OR ".join(f"from:{s}" for s in senders)
        if date_from_str:
            query += f" after:{date_from_str.replace('-', '/')}"
        if date_to_str:
            before_dt = date_type.fromisoformat(date_to_str) + timedelta(days=1)
            query    += f" before:{before_dt.isoformat().replace('-', '/')}"

        try:
            emails = search_emails(service, query, max_results=max_results)
        except Exception as e:
            return {"_error": f"Gmail search failed: {e}"}

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

    result = await asyncio.to_thread(_do_sync)

    if "_error" in result:
        err = result["_error"]
        if err == "oauth_required":
            raise HTTPException(status_code=401, detail="oauth_required")
        raise HTTPException(status_code=500, detail=err)

    return result


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
    index_path = os.path.join(BASE_DIR, "static", "index.html")
    if os.path.exists(index_path):
        with open(index_path) as f:
            return HTMLResponse(f.read())
    return {"status": "ok", "message": "FinTrack PK API Server", "version": "1.0.0"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("server:app", host=HOST, port=PORT, reload=False)
