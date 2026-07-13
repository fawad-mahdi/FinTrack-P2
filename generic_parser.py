"""
Generic, bank-agnostic transaction extraction engine.

Layered pipeline applied to any registered bank's alert email:
skip filter -> amount -> debit/credit direction -> merchant -> date
-> confidence score. Bank-specific override parsers (SCB, Meezan)
run before this engine and win when they produce a high-confidence
result; this engine is the net for every other format.

Confidence contract (consumed by database.insert_transaction):
  high   = amount + direction + merchant all found  -> auto-confirmed
  medium = amount + direction                        -> pending review
  low    = amount only                               -> pending review
  failed = no amount at all (stub row, amount 0.0)   -> pending review

This file is mirrored byte-identical at android/app/src/main/python/
and must stay Python 3.9 compatible (no X | Y unions, no match).
"""
import re
from datetime import datetime
from typing import Dict, List, Optional, Tuple

from merchants import normalize_merchant


class _SkipSentinel:
    """Returned by the router for recognised non-transaction emails."""

    def __repr__(self) -> str:
        return "<SKIP: non-transaction email>"


SKIP = _SkipSentinel()


# ─── LAYER 1: NON-TRANSACTION FILTER ──────────────────────────
# Hard patterns always skip (security/service notices — proven safe on
# SCB/Meezan corpora). Marketing patterns skip only when the email has
# no money amount, so a promo-worded real transaction still parses.

_HARD_SKIP_PATTERNS = [
    r"OTP\s+(?:is|for|code)",
    r"OTP[:\s]+\d",
    r"one.time\s*password",
    r"successfully logged (?:on|in)",
    r"login attempt",
    r"password\s*(?:reset|change|changed|expired|expiry)",
    r"block\s*(?:the|your)?\s*(?:mobile|card|account)",
    r"will never ask",
    r"successfully registered",
    r"profile.*updated",
    r"device.*(?:registered|activated)",
    r"\be-?statement\b",
    r"statement of account",
]

_MARKETING_SKIP_PATTERNS = [
    r"promotion(?:al)?\b",
    r"(?:exclusive|special|limited.time)\s+(?:offer|deal)",
    r"discount offer",
    r"\bvoucher\b",
    r"lucky draw",
    r"win\s+(?:a|an|amazing|exciting)",
    r"\bwebinar\b",
    r"installment plan offer",
]

_HARD_SKIP_RE = re.compile("|".join(_HARD_SKIP_PATTERNS), re.IGNORECASE)
_MARKETING_SKIP_RE = re.compile("|".join(_MARKETING_SKIP_PATTERNS), re.IGNORECASE)


def is_non_transaction(text: str) -> bool:
    if _HARD_SKIP_RE.search(text):
        return True
    if _MARKETING_SKIP_RE.search(text) and not _candidate_amounts(text):
        return True
    return False


# ─── LAYER 2: AMOUNT EXTRACTION ───────────────────────────────

_CURRENCY_RE = re.compile(
    r"(?:\bPKR|\bRs\.?|\bRupees|₨)\s*\.?\s*([\d,]+(?:\.\d{1,2})?)",
    re.IGNORECASE,
)
# Amounts labelled as balance / limit are not the transaction amount.
_BALANCE_CONTEXT_RE = re.compile(
    r"(?:balance|bal|available|avl|limit)\s*(?:is|of|now|:)?\s*$",
    re.IGNORECASE,
)


def _candidate_amounts(text: str) -> List[Tuple[int, float]]:
    """All (position, value) currency amounts, excluding balance/limit mentions."""
    out: List[Tuple[int, float]] = []
    for m in _CURRENCY_RE.finditer(text):
        prefix = text[max(0, m.start() - 30):m.start()]
        if _BALANCE_CONTEXT_RE.search(prefix):
            continue
        cleaned = m.group(1).replace(",", "")
        try:
            value = float(cleaned)
        except ValueError:
            continue
        if value >= 1:
            out.append((m.start(), value))
    return out


def extract_amount(text: str) -> Optional[float]:
    candidates = _candidate_amounts(text)
    return candidates[0][1] if candidates else None


# ─── LAYER 3: DEBIT / CREDIT CLASSIFICATION ───────────────────

_DEBIT_RE = re.compile(
    r"(?:debited|charged|spent|withdrawn|withdrawal|purchased?|"
    r"paid|payment|bill paid|\bPOS\b|used\s+(?:for|at)|"
    r"(?:sent|transferred)(?!\s+to\s+your\s+account))",
    re.IGNORECASE,
)
_CREDIT_RE = re.compile(
    r"(?:credited|received|deposited|refund(?:ed)?|revers(?:ed|al)|"
    r"cash\s?back|(?:sent|transferred)\s+to\s+your\s+account)",
    re.IGNORECASE,
)
_FORCE_CREDIT_RE = re.compile(r"refund|revers(?:ed|al)|cash\s?back", re.IGNORECASE)


def classify_direction(text: str, amount_pos: int = 0) -> Optional[str]:
    """'debit' or 'credit' from the verb nearest the amount; refunds force credit."""
    if _FORCE_CREDIT_RE.search(text):
        return "credit"
    debit_hits = [m.start() for m in _DEBIT_RE.finditer(text)]
    credit_hits = [m.start() for m in _CREDIT_RE.finditer(text)]
    if not debit_hits and not credit_hits:
        return None
    if not credit_hits:
        return "debit"
    if not debit_hits:
        return "credit"
    nearest_debit = min(abs(p - amount_pos) for p in debit_hits)
    nearest_credit = min(abs(p - amount_pos) for p in credit_hits)
    return "debit" if nearest_debit <= nearest_credit else "credit"


# ─── LAYER 4: MERCHANT / BENEFICIARY EXTRACTION ───────────────

_MERCHANT_TERMINATOR = (
    r"(?=\s+(?:on|via|using|dated|through|from|ref\b|txn|a/?c\b|account|"
    r"with the following|branch|remarks)\b|\s*[.,;\n(]|$)"
)
# Labelled fields win over prose prepositions.
_LABELLED_MERCHANT_RE = re.compile(
    r"(?:beneficiary(?:\s*name)?|merchant(?:\s*name)?|payee|paid to)\s*:?\s+(.{2,60}?)"
    + _MERCHANT_TERMINATOR,
    re.IGNORECASE,
)
_DEBIT_MERCHANT_RE = re.compile(
    r"(?:\bat|\bto|\btowards|\bfor|in favou?r of)\s+(.{2,60}?)" + _MERCHANT_TERMINATOR,
    re.IGNORECASE,
)
_CREDIT_MERCHANT_RE = re.compile(
    r"\bfrom\s+(.{2,60}?)" + _MERCHANT_TERMINATOR,
    re.IGNORECASE,
)
_ACCOUNT_NOISE_RE = re.compile(r"(?:[x*]{2,}[\d*]*|\b\d{5,}\b)", re.IGNORECASE)


def _clean_candidate(raw: str) -> Optional[str]:
    s = _ACCOUNT_NOISE_RE.sub(" ", raw)
    s = re.sub(r"\s+", " ", s).strip(" -:")
    if len(s) < 2:
        return None
    lowered = s.lower()
    if lowered.startswith(("your", "you ", "the ")) or lowered in ("your account", "account"):
        return None
    # A merchant is a name, not a money figure or bare number.
    if re.match(r"(?:pkr|rs\.?|rupees|₨)\b", lowered) or not re.search(r"[a-z]", lowered):
        return None
    return s


def extract_merchant(text: str, direction: Optional[str]) -> Optional[str]:
    patterns = [_LABELLED_MERCHANT_RE]
    if direction == "credit":
        patterns.append(_CREDIT_MERCHANT_RE)
    else:
        patterns.append(_DEBIT_MERCHANT_RE)
    for pattern in patterns:
        for m in pattern.finditer(text):
            cleaned = _clean_candidate(m.group(1))
            if cleaned:
                return normalize_merchant(cleaned)
    return None


# ─── LAYER 5: DATE EXTRACTION ─────────────────────────────────

_DATE_PATTERNS = [
    (re.compile(r"\b(\d{1,2}-\d{1,2}-\d{4})\b"), ("%d-%m-%Y",)),
    (re.compile(r"\b(\d{1,2}/\d{1,2}/\d{4})\b"), ("%d/%m/%Y",)),
    (re.compile(r"\b(\d{1,2}-\d{1,2}-\d{2})\b"), ("%d-%m-%y",)),
    (re.compile(r"\b(\d{1,2}/\d{1,2}/\d{2})\b"), ("%d/%m/%y",)),
    (
        re.compile(r"\b(\d{1,2}[- ][A-Za-z]{3,9}[- ,]+\d{2,4})\b"),
        ("%d-%b-%Y", "%d %b %Y", "%d %B %Y", "%d-%b-%y", "%d %b, %Y"),
    ),
    (
        re.compile(r"\b([A-Za-z]{3,9}\s+\d{1,2},?\s+\d{4})\b"),
        ("%b %d, %Y", "%B %d, %Y", "%b %d %Y", "%B %d %Y"),
    ),
]


def extract_date(text: str, email_date: str = "") -> str:
    for pattern, formats in _DATE_PATTERNS:
        m = pattern.search(text)
        if not m:
            continue
        raw = re.sub(r"\s+", " ", m.group(1)).strip()
        for fmt in formats:
            try:
                parsed = datetime.strptime(raw, fmt)
            except ValueError:
                continue
            if 2000 <= parsed.year <= 2100:
                return parsed.isoformat()
    return email_date or datetime.now().isoformat()


# ─── PIPELINE ─────────────────────────────────────────────────

def parse_generic(text: str, bank: str, gmail_id: str = "", email_date: str = "") -> Optional[Dict]:
    """
    Extract a transaction from any registered bank's email.
    Returns None when no amount is found (caller inserts a failed stub).
    The caller (parsers.parse_email) fills in "category".
    """
    amounts = _candidate_amounts(text)
    if not amounts:
        return None

    # Prefer the amount nearest a direction verb.
    best_pos, best_amount = amounts[0]
    best_direction = None
    best_distance = None
    for pos, value in amounts:
        direction = classify_direction(text, pos)
        if direction is None:
            continue
        hits = _DEBIT_RE if direction == "debit" else _CREDIT_RE
        distance = min(abs(m.start() - pos) for m in hits.finditer(text))
        if best_distance is None or distance < best_distance:
            best_pos, best_amount = pos, value
            best_direction = direction
            best_distance = distance

    merchant = extract_merchant(text, best_direction)

    if best_direction and merchant:
        confidence = "high"
    elif best_direction:
        confidence = "medium"
    else:
        confidence = "low"

    if merchant is None:
        merchant = "Incoming Transfer" if best_direction == "credit" else "Unknown (review raw email)"

    return {
        "gmail_id": gmail_id,
        "amount": best_amount,
        "tx_type": best_direction or "debit",
        "merchant": merchant,
        "bank": bank,
        "tx_date": extract_date(text, email_date),
        "raw_text": text[:500],
        "confidence": confidence,
    }


def parse_failed_stub(text: str, bank: str, gmail_id: str = "", email_date: str = "") -> Dict:
    """
    Stub row for a registered-bank email that passed the skip filter but
    yielded no amount. Lands in Pending Review with the raw text so the
    user can correct it instead of the email being silently dropped.
    """
    return {
        "gmail_id": gmail_id,
        "amount": 0.0,
        "tx_type": "debit",
        "merchant": "Unparsed email (review)",
        "bank": bank,
        "category": "other",
        "tx_date": email_date or datetime.now().isoformat(),
        "raw_text": text[:1000],
        "confidence": "failed",
    }
