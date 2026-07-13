"""
Declarative registry of Pakistani bank / wallet alert senders.

Adding support for a new bank = appending one dict here. No other code
changes are required unless the bank needs a bank-specific override
parser (registered in parsers.BANK_OVERRIDES).

Entries with verified=False are educated guesses for the alert sender
domain and MUST be validated against a real alert email before being
trusted. Correcting one is a one-line edit to "domains".

This file is mirrored byte-identical at android/app/src/main/python/
and must stay Python 3.9 compatible.
"""
import re
from typing import Dict, List, Optional

BANK_REGISTRY: List[Dict] = [
    {"bank": "SCB", "domains": ["sc.com"], "senders": ["alerts.pk@sc.com"], "verified": True},
    {"bank": "Meezan", "domains": ["meezanbank.com"], "senders": ["no-reply@meezanbank.com"], "verified": True},
    {"bank": "HBL", "domains": ["hbl.com"], "senders": [], "verified": False},
    {"bank": "UBL", "domains": ["ubl.com.pk", "ubldigital.com"], "senders": [], "verified": False},
    {"bank": "MCB", "domains": ["mcb.com.pk"], "senders": [], "verified": False},
    {"bank": "Bank Alfalah", "domains": ["bankalfalah.com"], "senders": [], "verified": False},
    {"bank": "Faysal", "domains": ["faysalbank.com"], "senders": [], "verified": False},
    {"bank": "Bank AL Habib", "domains": ["bankalhabib.com"], "senders": [], "verified": False},
    {"bank": "Askari", "domains": ["askaribank.com.pk"], "senders": [], "verified": False},
    {"bank": "JS Bank", "domains": ["jsbl.com"], "senders": [], "verified": False},
    {"bank": "EasyPaisa", "domains": ["easypaisa.com.pk", "telenorbank.pk"], "senders": [], "verified": False},
    {"bank": "JazzCash", "domains": ["jazzcash.com.pk"], "senders": [], "verified": False},
    {"bank": "NayaPay", "domains": ["nayapay.com"], "senders": [], "verified": False},
    {"bank": "SadaPay", "domains": ["sadapay.pk"], "senders": [], "verified": False},
]

_ADDRESS_RE = re.compile(r"<([^>]+)>")


def _extract_address(sender: str) -> str:
    """Pull the bare address out of 'Display Name <addr@domain>' or return as-is."""
    m = _ADDRESS_RE.search(sender)
    address = m.group(1) if m else sender
    return address.strip().lower()


def find_bank(sender: str, extra_entries: Optional[List[Dict]] = None) -> Optional[Dict]:
    """Return the registry entry matching the email sender, or None.

    extra_entries: user-added registry entries (see entries_from_user_banks),
    checked after the built-in registry so built-ins win on conflict.
    """
    address = _extract_address(sender)
    if "@" not in address:
        return None
    domain = address.rsplit("@", 1)[1]

    entry_lists = [BANK_REGISTRY] + ([extra_entries] if extra_entries else [])
    for entries in entry_lists:
        for entry in entries:
            if address in entry["senders"]:
                return entry
        for entry in entries:
            for d in entry["domains"]:
                if domain == d or domain.endswith("." + d):
                    return entry
    return None


def get_query_domains(extra_entries: Optional[List[Dict]] = None) -> List[str]:
    """All registered sender domains (plus user senders/domains), for building
    the Gmail search query. Gmail's from:() accepts both bare domains and
    full addresses."""
    domains: List[str] = []
    for entry in BANK_REGISTRY:
        for d in entry["domains"]:
            if d not in domains:
                domains.append(d)
    for entry in extra_entries or []:
        for d in entry["domains"] + entry["senders"]:
            if d not in domains:
                domains.append(d)
    return domains


def entries_from_user_banks(rows: List[Dict]) -> List[Dict]:
    """Convert user_banks DB rows into registry-entry dicts, one per bank.
    Addresses containing '@' become senders, bare domains become domains.
    Rows with enabled == 0 are skipped."""
    by_bank: Dict[str, Dict] = {}
    for row in rows:
        if not row.get("enabled", 1):
            continue
        bank = row["bank"]
        entry = by_bank.setdefault(
            bank, {"bank": bank, "domains": [], "senders": [], "verified": True}
        )
        address = row["address"].strip().lower()
        key = "senders" if "@" in address else "domains"
        if address not in entry[key]:
            entry[key].append(address)
    return list(by_bank.values())
