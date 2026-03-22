"""
merchants.py — Merchant name normalization engine (US-19)

Rules are matched case-insensitively via substring search. Order matters:
more specific patterns must appear before shorter overlapping ones.
First match wins.

Default rules live here in code and are also seeded into the merchant_rules
DB table on first run (is_default=True), making them manageable via future
admin UI (US-20).
"""

import re
from typing import List, Optional, Tuple

# ─── DEFAULT RULES ─────────────────────────────────────────────
# (pattern, clean_name) — order matters, more specific first

DEFAULT_RULES: List[Tuple[str, str]] = [

    # ── Grocery & Supermarkets ──────────────────────────────────
    ("imtiaz super mkt",        "Imtiaz Super Market"),
    ("imtiaz super market",     "Imtiaz Super Market"),
    ("imtiaz",                  "Imtiaz Super Market"),
    ("al-fatah",                "Al-Fatah"),
    ("al fatah",                "Al-Fatah"),
    ("alfatah",                 "Al-Fatah"),
    ("chase up",                "Chase Up"),
    ("chase",                   "Chase Up"),
    ("carrefour",               "Carrefour"),
    ("metro cash",              "Metro Cash & Carry"),
    ("metro",                   "Metro Cash & Carry"),
    ("hyperstar",               "Hyperstar"),
    ("naheed",                  "Naheed Supermarket"),
    ("agha's",                  "Agha's Supermarket"),

    # ── Fuel Stations ───────────────────────────────────────────
    ("pso",                     "PSO"),
    ("shell",                   "Shell"),
    ("total parco",             "Total Parco"),
    ("total",                   "Total Parco"),
    ("attock",                  "Attock Petroleum"),
    ("caltex",                  "Caltex"),
    ("hascol",                  "Hascol"),
    ("byco",                    "Byco"),

    # ── Food & Dining ────────────────────────────────────────────
    ("kfc",                     "KFC"),
    ("mcdonald",                "McDonald's"),
    ("pizza hut",               "Pizza Hut"),
    ("domino",                  "Domino's"),
    ("hardee",                  "Hardee's"),
    ("burger king",             "Burger King"),
    ("subway",                  "Subway"),
    ("nando's",                 "Nando's"),
    ("nandos",                  "Nando's"),
    ("student biryani",         "Student Biryani"),
    ("cheezious",               "Cheezious"),
    ("salt'n pepper",           "Salt'n Pepper"),
    ("saltn pepper",            "Salt'n Pepper"),
    ("foodpanda",               "Foodpanda"),

    # ── Clothing & Fashion ───────────────────────────────────────
    ("ndure",                   "Ndure"),
    ("khaadi",                  "Khaadi"),
    ("sapphire",                "Sapphire"),
    ("sana safinaz",            "Sana Safinaz"),
    ("gul ahmed",               "Gul Ahmed"),
    ("ideas by gul ahmed",      "Ideas by Gul Ahmed"),
    ("ideas",                   "Ideas by Gul Ahmed"),
    ("outfitters",              "Outfitters"),
    ("bonanza satrangi",        "Bonanza Satrangi"),
    ("bonanza",                 "Bonanza Satrangi"),
    ("junaid jamshed",          "Junaid Jamshed"),
    ("j.",                      "J."),
    ("lal's",                   "Lal's"),
    ("lals",                    "Lal's"),
    ("ego",                     "Ego"),
    ("beechtree",               "Beechtree"),

    # ── Telecom ──────────────────────────────────────────────────
    ("jazz",                    "Jazz"),
    ("telenor",                 "Telenor"),
    ("zong",                    "Zong"),
    ("ufone",                   "Ufone"),
    ("ptcl",                    "PTCL"),
    ("nayatel",                 "Nayatel"),
    ("stormfiber",              "Stormfiber"),
    ("wi-tribe",                "Wi-Tribe"),

    # ── Utilities ────────────────────────────────────────────────
    ("k-electric",              "K-Electric"),
    ("k electric",              "K-Electric"),
    ("kelectric",               "K-Electric"),
    ("ssgc",                    "SSGC"),
    ("wapda",                   "WAPDA"),
    ("lesco",                   "LESCO"),
    ("iesco",                   "IESCO"),
    ("gepco",                   "GEPCO"),
    ("fesco",                   "FESCO"),
    ("hesco",                   "HESCO"),
    ("mepco",                   "MEPCO"),

    # ── E-commerce & Tech ────────────────────────────────────────
    ("daraz",                   "Daraz"),
    ("amazon",                  "Amazon"),
    ("netflix",                 "Netflix"),
    ("spotify",                 "Spotify"),
    ("google",                  "Google"),
    ("apple store",             "Apple"),
    ("apple",                   "Apple"),
    ("microsoft",               "Microsoft"),
    ("adobe",                   "Adobe"),

    # ── Banking & Fintech ────────────────────────────────────────
    ("easypaisa",               "EasyPaisa"),
    ("easy paisa",              "EasyPaisa"),
    ("jazzcash",                "JazzCash"),
    ("jazz cash",               "JazzCash"),
    ("sadapay",                 "SadaPay"),
    ("nayapay",                 "NayaPay"),

    # ── Healthcare ───────────────────────────────────────────────
    ("aga khan",                "Aga Khan Hospital"),
    ("agha khan",               "Aga Khan Hospital"),
    ("shifa",                   "Shifa Hospital"),
    ("liaquat national",        "Liaquat National Hospital"),
    ("sehat kahani",            "Sehat Kahani"),
    ("dawaai",                  "Dawaai"),
    ("oladoc",                  "Oladoc"),

    # ── Malls & Retail ───────────────────────────────────────────
    ("centaurus",               "Centaurus Mall"),
    ("dolmen",                  "Dolmen Mall"),
    ("emporium",                "Emporium Mall"),
    ("packages mall",           "Packages Mall"),
    ("liberty books",           "Liberty Books"),
    ("book galleria",           "Book Galleria"),

    # ── Ride-hailing & Delivery ──────────────────────────────────
    ("uber",                    "Uber"),
    ("careem",                  "Careem"),
    ("bykea",                   "Bykea"),
    ("tcs",                     "TCS"),
    ("leopards",                "Leopards Courier"),
    ("dhl",                     "DHL"),
]


# ─── MODULE-LEVEL CACHE ────────────────────────────────────────
# Populated on first call to normalize_merchant().
# Holds custom DB rules (higher priority) + DEFAULT_RULES as fallback.
_rules_cache: Optional[List[Tuple[str, str]]] = None


def _load_rules() -> List[Tuple[str, str]]:
    """Load rules from DB (custom first) then fall back to DEFAULT_RULES."""
    global _rules_cache
    if _rules_cache is not None:
        return _rules_cache
    try:
        from database import get_merchant_rules
        db_rules = get_merchant_rules()
        # DB holds ALL rules (custom + default); use them if populated
        _rules_cache = db_rules if db_rules else DEFAULT_RULES
    except Exception:
        # DB not ready yet (cold start before init_db) — use hardcoded rules
        _rules_cache = DEFAULT_RULES
    return _rules_cache


def invalidate_rules_cache():
    """Call this after adding/removing merchant rules so the cache refreshes."""
    global _rules_cache
    _rules_cache = None


# ─── NORMALIZATION ─────────────────────────────────────────────

def normalize_merchant(raw: str) -> str:
    """
    Normalize a raw merchant string to a clean display name.

    Tries each rule in order (case-insensitive substring match).
    Returns a title-cased cleaned version of the raw string if no rule matches.
    """
    if not raw or not raw.strip():
        return "Unknown"

    rules = _load_rules()
    raw_lower = raw.lower()

    for pattern, clean_name in rules:
        if pattern.lower() in raw_lower:
            return clean_name

    # No rule matched — basic cleanup for display
    return _basic_clean(raw)


def _basic_clean(merchant: str) -> str:
    """Title-case and de-noise a raw merchant string that matched no rule."""
    s = merchant.strip()
    # Collapse multiple spaces
    s = re.sub(r"\s+", " ", s)
    # Title-case
    s = s.title()
    # Truncate to a readable length
    return s[:60]
