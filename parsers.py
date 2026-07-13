import re
from datetime import datetime
from html import unescape
from typing import Optional, Union

from bank_registry import find_bank
from generic_parser import (
    SKIP, _SkipSentinel, is_non_transaction, parse_failed_stub, parse_generic,
)
from merchants import normalize_merchant


def strip_html(text: str) -> str:
    """Remove HTML tags and decode entities. Meezan sends HTML emails."""
    text = unescape(text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def parse_amount(raw: str) -> float:
    cleaned = raw.replace(",", "").strip()
    try:
        return float(cleaned)
    except ValueError:
        return 0.0


def guess_category(merchant: str) -> str:
    # Check user-defined mappings first (US-13)
    try:
        from database import get_learned_category
        learned = get_learned_category(merchant)
        if learned:
            return learned
    except Exception:
        pass  # DB not ready yet (e.g., during cold start before init_db)

    m = merchant.lower()
    rules = {
        "groceries": ["imtiaz", "chase", "alfatah", "metro", "carrefour", "grocery", "mart", "supermarket", "hyperstar", "naheed"],
        "fuel": ["pso", "shell", "total", "attock", "fuel", "petrol", "caltex", "hascol", "byco"],
        "dining": ["restaurant", "cafe", "pizza", "kfc", "mcdonald", "food", "eat", "burger", "nando", "cheezious", "subway", "domino", "hardee", "biryani", "foodpanda"],
        "shopping": ["ndure", "khaadi", "sapphire", "gul ahmed", "outfitters", "clothing", "store", "liberty books", "book", "lals", "bonanza", "sana safinaz", "beechtree", "ego"],
        "utilities": ["kelectric", "k-electric", "ssgc", "ptcl", "jazz", "telenor", "zong", "ufone", "nayatel", "stormfiber", "lesco", "iesco", "wapda", "easyload", "top-up", "topup", "mobile load"],
        "transfer": ["transfer", "ibft", "raast", "wallet", "sent from", "beneficiary", "bank", "sent to", "easypaisa", "jazzcash", "sadapay", "nayapay"],
        "atm": ["atm", "withdrawal", "cash"],
        "medical": ["pharmacy", "hospital", "clinic", "lab", "medical", "shifa", "aga khan", "dawaai"],
        "education": ["school", "university", "college", "tuition", "academy"],
    }
    for category, keywords in rules.items():
        if any(kw in m for kw in keywords):
            return category
    return "other"


# ─── MEEZAN: SKIP NON-TRANSACTION EMAILS ──────────────────────

MEEZAN_SKIP_PATTERNS = [
    r"successfully logged on",
    r"login attempt",
    r"OTP\b",
    r"one.time\s*password",
    r"password\s*(?:reset|change)",
    r"block\s*(?:the|your)?\s*(?:mobile|card|account)",
    r"meezan bank will never ask",
    r"successfully registered",
    r"profile.*updated",
    r"Dear Customer,\s*You have successfully logged",
]

MEEZAN_SKIP_RE = re.compile("|".join(MEEZAN_SKIP_PATTERNS), re.IGNORECASE)


def is_meezan_non_transaction(text: str) -> bool:
    if MEEZAN_SKIP_RE.search(text):
        return True
    if not re.search(r"PKR\s*[\d,]+", text):
        return True
    return False


# ─── SCB PARSER ───────────────────────────────────────────────

SCB_DEBIT_PATTERN = re.compile(
    r"SCBPL:\s*PKR\s*([\d,]+\.?\d*)\s*have been (?:paid|debited|charged)\s*(?:at|to)\s*(.+?)\s*using\s*(.+?)\s*on\s*(\d{2}-\d{2}-\d{2})",
    re.IGNORECASE | re.DOTALL,
)

SCB_CREDIT_PATTERN = re.compile(
    r"SCBPL:\s*PKR\s*([\d,]+\.?\d*)\s*(?:has been credited|received|deposited)\s*(?:to|in)\s*(?:your)?\s*(?:account)?\s*(.+?)\s*on\s*(\d{2}-\d{2}-\d{2})",
    re.IGNORECASE | re.DOTALL,
)

SCB_FALLBACK = re.compile(
    r"(?:SCBPL|Standard Chartered)[:\s]*PKR\s*([\d,]+\.?\d*)\s*(.*)",
    re.IGNORECASE | re.DOTALL,
)


def parse_scb(text: str, gmail_id: str = "", email_date: str = "") -> Optional[dict]:
    fallback_date = email_date or datetime.now().isoformat()

    m = SCB_DEBIT_PATTERN.search(text)
    if m:
        amount = parse_amount(m.group(1))
        merchant_raw = m.group(2).strip()
        merchant = normalize_merchant(merchant_raw)          # US-19
        date_str = m.group(4).strip()
        try:
            tx_date = datetime.strptime(date_str, "%d-%m-%y").isoformat()
        except ValueError:
            tx_date = fallback_date
        return {
            "gmail_id": gmail_id, "amount": amount, "tx_type": "debit",
            "merchant": merchant, "bank": "SCB", "category": guess_category(merchant),
            "tx_date": tx_date, "raw_text": text[:500], "confidence": "high",
        }

    m = SCB_CREDIT_PATTERN.search(text)
    if m:
        amount = parse_amount(m.group(1))
        desc_raw = m.group(2).strip()
        desc = normalize_merchant(desc_raw)                  # US-19
        date_str = m.group(3).strip()
        try:
            tx_date = datetime.strptime(date_str, "%d-%m-%y").isoformat()
        except ValueError:
            tx_date = fallback_date
        return {
            "gmail_id": gmail_id, "amount": amount, "tx_type": "credit",
            "merchant": desc, "bank": "SCB", "category": "transfer",
            "tx_date": tx_date, "raw_text": text[:500], "confidence": "high",
        }

    m = SCB_FALLBACK.search(text)
    if m:
        amount = parse_amount(m.group(1))
        desc_raw = m.group(2).strip()[:100]
        merchant = normalize_merchant(desc_raw)              # US-19
        return {
            "gmail_id": gmail_id, "amount": amount, "tx_type": "debit",
            "merchant": merchant or "Unknown", "bank": "SCB", "category": "other",
            "tx_date": fallback_date, "raw_text": text[:500], "confidence": "low",
        }

    return None


# ─── MEEZAN PARSER ────────────────────────────────────────────

MEEZAN_SENT_TO_PATTERN = re.compile(
    r"PKR\s*([\d,]+\.?\d*)\s*(?:is\s*)?sent\s+to\s+(.+?)(?:\s*\(.*?(?:AC|account).*?\))*\s*(?:on account|\(MBL|with the following)",
    re.IGNORECASE | re.DOTALL,
)

MEEZAN_SENT_FROM_PATTERN = re.compile(
    r"PKR\s*([\d,]+\.?\d*)\s*sent from your account\s*\S+\s*with the following details.*?Beneficiary\s*(?:Account)?\s*:?\s*(.+?)(?:\s*(?:Branch|Remarks|Date|Regard|$))",
    re.IGNORECASE | re.DOTALL,
)

MEEZAN_DEBITED_PATTERN = re.compile(
    r"PKR\s*([\d,]+\.?\d*)\s*is\s*Debited\s*from\s*your\s*account\s*\S+\s*with the following details.*?(?:Beneficiary|Merchant|at)\s*(?:Account)?\s*:?\s*(.+?)(?:\s*(?:Branch|Remarks|Date|Regard|$))",
    re.IGNORECASE | re.DOTALL,
)

MEEZAN_CREDIT_PATTERN = re.compile(
    r"PKR\s*([\d,]+\.?\d*)\s*is\s*credited\s*to\s*your\s*account",
    re.IGNORECASE,
)


def parse_meezan(text: str, gmail_id: str = "", email_date: str = "") -> Optional[dict]:
    fallback_date = email_date or datetime.now().isoformat()
    clean = strip_html(text)

    if is_meezan_non_transaction(clean):
        return None

    m = MEEZAN_SENT_TO_PATTERN.search(clean)
    if m:
        amount = parse_amount(m.group(1))
        beneficiary_raw = m.group(2).strip()
        beneficiary = normalize_merchant(beneficiary_raw)    # US-19
        return {
            "gmail_id": gmail_id, "amount": amount, "tx_type": "debit",
            "merchant": beneficiary, "bank": "Meezan", "category": guess_category(beneficiary),
            "tx_date": fallback_date, "raw_text": clean[:500], "confidence": "high",
        }

    m = MEEZAN_SENT_FROM_PATTERN.search(clean)
    if m:
        amount = parse_amount(m.group(1))
        beneficiary_raw = m.group(2).strip()
        beneficiary = normalize_merchant(beneficiary_raw)    # US-19
        return {
            "gmail_id": gmail_id, "amount": amount, "tx_type": "debit",
            "merchant": beneficiary, "bank": "Meezan", "category": guess_category(beneficiary),
            "tx_date": fallback_date, "raw_text": clean[:500], "confidence": "high",
        }

    m = MEEZAN_DEBITED_PATTERN.search(clean)
    if m:
        amount = parse_amount(m.group(1))
        merchant_raw = m.group(2).strip()
        merchant = normalize_merchant(merchant_raw)          # US-19
        return {
            "gmail_id": gmail_id, "amount": amount, "tx_type": "debit",
            "merchant": merchant, "bank": "Meezan", "category": guess_category(merchant),
            "tx_date": fallback_date, "raw_text": clean[:500], "confidence": "high",
        }

    m = MEEZAN_CREDIT_PATTERN.search(clean)
    if m:
        amount = parse_amount(m.group(1))
        return {
            "gmail_id": gmail_id, "amount": amount, "tx_type": "credit",
            "merchant": "Incoming Transfer", "bank": "Meezan", "category": "transfer",
            "tx_date": fallback_date, "raw_text": clean[:500], "confidence": "high",
        }

    fm = re.search(r"PKR\s*([\d,]+\.?\d*)", clean)
    if fm:
        amount = parse_amount(fm.group(1))
        if amount < 1:
            return None
        return {
            "gmail_id": gmail_id, "amount": amount, "tx_type": "debit",
            "merchant": "Unknown (review raw email)", "bank": "Meezan", "category": "other",
            "tx_date": fallback_date, "raw_text": clean[:500], "confidence": "low",
        }

    return None


# ─── ROUTER ───────────────────────────────────────────────────

# Bank-specific parsers that outrank the generic engine when they
# produce a high-confidence result. Banks not listed here rely
# entirely on generic_parser.
BANK_OVERRIDES = {
    "SCB": [parse_scb],
    "Meezan": [parse_meezan],
}

_CONFIDENCE_RANK = {"high": 3, "medium": 2, "low": 1, "failed": 0}


def parse_email(text: str, sender: str, gmail_id: str = "", email_date: str = "", extra_entries: Optional[list] = None) -> Optional[Union[dict, _SkipSentinel]]:
    """
    Route an email to a transaction dict.

    extra_entries: user-added registry entries (bank_registry.entries_from_user_banks).

    Returns:
      None  — sender is not a registered bank (email ignored)
      SKIP  — registered bank, but a non-transaction email (OTP/login/promo)
      dict  — a transaction; confidence 'failed' means nothing could be
              extracted and the row is a pending-review stub
    """
    entry = find_bank(sender, extra_entries)
    if entry is None:
        return None

    clean = strip_html(text)
    if is_non_transaction(clean):
        return SKIP

    bank = entry["bank"]

    override_result = None
    for override in BANK_OVERRIDES.get(bank, []):
        result = override(text, gmail_id, email_date)
        if result:
            if result.get("confidence") == "high":
                return result
            if override_result is None:
                override_result = result

    generic = parse_generic(clean, bank, gmail_id, email_date)
    if generic is not None:
        generic["category"] = guess_category(generic["merchant"])
        if override_result and _CONFIDENCE_RANK.get(override_result["confidence"], 0) >= _CONFIDENCE_RANK.get(generic["confidence"], 0):
            return override_result
        return generic

    if override_result:
        return override_result

    return parse_failed_stub(clean, bank, gmail_id, email_date)
