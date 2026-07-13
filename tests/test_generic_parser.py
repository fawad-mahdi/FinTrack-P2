"""
Unit tests for the generic bank-agnostic extraction engine
(generic_parser.py + bank_registry.py) and a fixture-corpus run
through the full parse_email router.

Fixture contract: tests/fixtures/emails/<bank>/<case>.txt is the email
body; <case>.expected.json holds "sender" plus either {"skip": true},
{"confidence": "failed"}, or expected amount/tx_type/min_confidence/
merchant_contains.
"""
import json
from pathlib import Path

import pytest

from bank_registry import BANK_REGISTRY, entries_from_user_banks, find_bank, get_query_domains
from generic_parser import (
    SKIP,
    classify_direction,
    extract_amount,
    extract_date,
    extract_merchant,
    is_non_transaction,
    parse_failed_stub,
    parse_generic,
)
from parsers import parse_email

FIXTURE_DIR = Path(__file__).parent / "fixtures" / "emails"
_RANK = {"failed": 0, "low": 1, "medium": 2, "high": 3}


# ── bank_registry ─────────────────────────────────────────────────────────────

class TestBankRegistry:
    def test_exact_sender_match(self):
        entry = find_bank("alerts.pk@sc.com")
        assert entry is not None and entry["bank"] == "SCB"

    def test_domain_match(self):
        entry = find_bank("anything@hbl.com")
        assert entry is not None and entry["bank"] == "HBL"

    def test_subdomain_match(self):
        entry = find_bank("alerts@mail.hbl.com")
        assert entry is not None and entry["bank"] == "HBL"

    def test_display_name_form(self):
        entry = find_bank("HBL Alerts <alerts@hbl.com>")
        assert entry is not None and entry["bank"] == "HBL"

    def test_unknown_domain_returns_none(self):
        assert find_bank("alerts@randombank.com") is None

    def test_no_at_sign_returns_none(self):
        assert find_bank("not-an-email") is None

    def test_case_insensitive(self):
        entry = find_bank("Alerts.PK@SC.COM")
        assert entry is not None and entry["bank"] == "SCB"

    def test_domain_suffix_does_not_false_match(self):
        # evil-sc.com must not match sc.com
        assert find_bank("alerts@evil-sc.com") is None

    def test_query_domains_unique_and_complete(self):
        domains = get_query_domains()
        assert len(domains) == len(set(domains))
        assert "sc.com" in domains and "meezanbank.com" in domains
        assert len(domains) >= len(BANK_REGISTRY)

    def test_every_entry_has_required_keys(self):
        for entry in BANK_REGISTRY:
            assert entry["bank"] and entry["domains"]
            assert "senders" in entry and "verified" in entry


class TestUserBankEntries:
    CUSTOM = [
        {"bank": "TestBank", "domains": ["testbank.com.pk"], "senders": ["alerts@othermail.pk"], "verified": True},
    ]

    def test_extra_sender_match(self):
        entry = find_bank("alerts@othermail.pk", self.CUSTOM)
        assert entry is not None and entry["bank"] == "TestBank"

    def test_extra_domain_match(self):
        entry = find_bank("no-reply@testbank.com.pk", self.CUSTOM)
        assert entry is not None and entry["bank"] == "TestBank"

    def test_extra_subdomain_match(self):
        entry = find_bank("x@mail.testbank.com.pk", self.CUSTOM)
        assert entry is not None and entry["bank"] == "TestBank"

    def test_builtin_wins_over_extra(self):
        shadow = [{"bank": "Impostor", "domains": ["sc.com"], "senders": [], "verified": True}]
        entry = find_bank("alerts.pk@sc.com", shadow)
        assert entry is not None and entry["bank"] == "SCB"

    def test_no_match_still_none(self):
        assert find_bank("alerts@randombank.com", self.CUSTOM) is None

    def test_query_domains_include_extra_senders_and_domains(self):
        domains = get_query_domains(self.CUSTOM)
        assert "testbank.com.pk" in domains
        assert "alerts@othermail.pk" in domains
        assert len(domains) == len(set(domains))

    def test_query_domains_without_extra_unchanged(self):
        assert get_query_domains() == get_query_domains(None)

    def test_entries_from_user_banks_classification(self):
        rows = [
            {"id": 1, "bank": "TestBank", "address": "alerts@othermail.pk", "enabled": 1},
            {"id": 2, "bank": "TestBank", "address": "testbank.com.pk", "enabled": 1},
            {"id": 3, "bank": "Disabled", "address": "off.com", "enabled": 0},
        ]
        entries = entries_from_user_banks(rows)
        assert len(entries) == 1
        entry = entries[0]
        assert entry["bank"] == "TestBank"
        assert entry["senders"] == ["alerts@othermail.pk"]
        assert entry["domains"] == ["testbank.com.pk"]
        assert entry["verified"] is True


# ── is_non_transaction ────────────────────────────────────────────────────────

class TestSkipFilter:
    def test_otp_is_skipped(self):
        assert is_non_transaction("Your OTP for login is 123456") is True

    def test_login_alert_is_skipped(self):
        assert is_non_transaction("You have successfully logged on to Internet Banking") is True

    def test_estatement_is_skipped(self):
        assert is_non_transaction("Your e-Statement for June is attached") is True

    def test_otp_footer_mention_not_skipped(self):
        # Security footer without an actual OTP should not kill a transaction
        assert is_non_transaction(
            "PKR 500 debited at KFC. Never share your OTP with anyone."
        ) is False

    def test_marketing_without_amount_skipped(self):
        assert is_non_transaction("Exclusive offer! Enter our lucky draw today") is True

    def test_marketing_with_amount_not_skipped(self):
        assert is_non_transaction("Special offer redeemed: PKR 1,200 charged at Daraz") is False

    def test_plain_transaction_not_skipped(self):
        assert is_non_transaction("PKR 1,500 debited from your account at Imtiaz") is False


# ── extract_amount ────────────────────────────────────────────────────────────

class TestExtractAmount:
    def test_pkr_prefix(self):
        assert extract_amount("PKR 1,500.50 debited") == 1500.50

    def test_rs_prefix(self):
        assert extract_amount("Rs. 900 paid") == 900.0

    def test_rs_no_dot(self):
        assert extract_amount("Rs 2,000 sent") == 2000.0

    def test_balance_amount_excluded(self):
        assert extract_amount("Available balance PKR 99,000") is None

    def test_transaction_amount_preferred_over_balance(self):
        text = "PKR 750 debited at Shop. Available balance: PKR 88,000"
        assert extract_amount(text) == 750.0

    def test_no_amount_returns_none(self):
        assert extract_amount("Dear customer, thank you") is None

    def test_sub_one_rupee_ignored(self):
        assert extract_amount("Fee Rs 0.00 applied") is None


# ── classify_direction ────────────────────────────────────────────────────────

class TestClassifyDirection:
    def test_debited_is_debit(self):
        assert classify_direction("PKR 100 debited from account") == "debit"

    def test_credited_is_credit(self):
        assert classify_direction("PKR 100 credited to account") == "credit"

    def test_refund_forces_credit(self):
        assert classify_direction("PKR 100 refund processed for your purchase") == "credit"

    def test_sent_to_your_account_is_credit(self):
        assert classify_direction("PKR 100 sent to your account by Ali") == "credit"

    def test_sent_to_person_is_debit(self):
        assert classify_direction("PKR 100 sent to Ali Hassan") == "debit"

    def test_card_used_is_debit(self):
        assert classify_direction("Card was used for PKR 100 at Daraz") == "debit"

    def test_no_verb_returns_none(self):
        assert classify_direction("Dear customer, thank you") is None


# ── extract_merchant ──────────────────────────────────────────────────────────

class TestExtractMerchant:
    def test_at_merchant(self):
        assert extract_merchant("PKR 100 paid at KFC DHA on 01-01-26", "debit") is not None

    def test_labelled_beneficiary(self):
        m = extract_merchant("Beneficiary: JOHN DOE Branch: Karachi", "debit")
        assert m is not None and "john" in m.lower()

    def test_credit_from(self):
        m = extract_merchant("PKR 100 received from ACME LTD on 01-01-26", "credit")
        assert m is not None and "acme" in m.lower()

    def test_account_numbers_stripped(self):
        m = extract_merchant("PKR 100 sent to ALI RAZA 03001234567 from your account", "debit")
        assert m is not None and "0300" not in m

    def test_your_account_rejected(self):
        assert extract_merchant("PKR 100 debited from your account", "debit") is None


# ── extract_date ──────────────────────────────────────────────────────────────

class TestExtractDate:
    def test_ddmmyyyy_dash(self):
        assert extract_date("on 05-07-2026").startswith("2026-07-05")

    def test_ddmmyyyy_slash(self):
        assert extract_date("on 09/07/2026").startswith("2026-07-09")

    def test_ddmmyy(self):
        assert extract_date("on 05-07-26").startswith("2026-07-05")

    def test_dd_mon_yyyy(self):
        assert extract_date("on 10 Jul 2026").startswith("2026-07-10")

    def test_fallback_to_email_date(self):
        assert extract_date("no date here", "2026-01-01T00:00:00") == "2026-01-01T00:00:00"


# ── parse_generic / parse_failed_stub ────────────────────────────────────────

class TestParseGeneric:
    def test_full_extraction_is_high(self):
        r = parse_generic("PKR 1,000 debited at KFC on 01-07-2026", "HBL", "g1")
        assert r["confidence"] == "high"
        assert r["tx_type"] == "debit"
        assert r["amount"] == 1000.0
        assert r["bank"] == "HBL"

    def test_amount_and_direction_only_is_medium(self):
        r = parse_generic("PKR 1,000 debited.", "HBL")
        assert r["confidence"] == "medium"

    def test_amount_only_is_low(self):
        r = parse_generic("PKR 1,000 reflected in statement xyz", "HBL")
        assert r["confidence"] == "low"
        assert r["tx_type"] == "debit"  # default

    def test_no_amount_returns_none(self):
        assert parse_generic("Dear customer, hello", "HBL") is None

    def test_failed_stub_shape(self):
        stub = parse_failed_stub("some text", "HBL", "g9", "2026-07-01T00:00:00")
        assert stub["confidence"] == "failed"
        assert stub["amount"] == 0.0
        assert stub["gmail_id"] == "g9"
        assert stub["raw_text"] == "some text"


# ── fixture corpus through the full router ────────────────────────────────────

_fixture_cases = sorted(FIXTURE_DIR.glob("*/*.txt"))


@pytest.mark.parametrize("txt_path", _fixture_cases, ids=lambda p: f"{p.parent.name}/{p.stem}")
def test_fixture_corpus(txt_path):
    expected = json.loads(txt_path.with_suffix("").with_suffix(".expected.json").read_text())
    body = txt_path.read_text()

    result = parse_email(body, expected["sender"], gmail_id=f"fx-{txt_path.stem}",
                         email_date="2026-07-01T00:00:00")

    if expected.get("skip"):
        assert result is SKIP, f"expected SKIP, got {result}"
        return

    assert result is not None and result is not SKIP

    if expected.get("confidence") == "failed":
        assert result["confidence"] == "failed"
        return

    assert result["amount"] == expected["amount"]
    assert result["tx_type"] == expected["tx_type"]
    assert _RANK[result["confidence"]] >= _RANK[expected["min_confidence"]], (
        f"confidence {result['confidence']} below {expected['min_confidence']}: {result}"
    )
    if "merchant_contains" in expected:
        assert expected["merchant_contains"] in result["merchant"].lower(), (
            f"merchant {result['merchant']!r} missing {expected['merchant_contains']!r}"
        )
    # Every parsed fixture must carry the fields insert_transaction needs
    for key in ("gmail_id", "bank", "category", "tx_date", "raw_text"):
        assert result.get(key), f"missing {key}: {result}"
