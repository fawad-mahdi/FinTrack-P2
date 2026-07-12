"""
Unit tests for email parsers (parsers.py).

Tests the SCB and Meezan regex parsers, the email router (parse_email),
category guesser, and Meezan non-transaction filter.
"""
import pytest
from parsers import (
    parse_scb, parse_meezan, parse_email,
    guess_category, is_meezan_non_transaction,
    strip_html, parse_amount,
)


# ── strip_html() ──────────────────────────────────────────────────────────────

class TestStripHtml:
    def test_removes_html_tags(self):
        assert strip_html("<p>Hello</p>") == "Hello"

    def test_decodes_html_entities(self):
        assert "PKR" in strip_html("PKR&nbsp;1,500")

    def test_collapses_whitespace(self):
        result = strip_html("  hello   world  ")
        assert "  " not in result

    def test_plain_text_unchanged(self):
        assert strip_html("PKR 1500 paid") == "PKR 1500 paid"


# ── parse_amount() ────────────────────────────────────────────────────────────

class TestParseAmount:
    def test_parses_plain_number(self):
        assert parse_amount("1500") == 1500.0

    def test_parses_comma_formatted(self):
        assert parse_amount("1,500") == 1500.0

    def test_parses_large_amount(self):
        assert parse_amount("1,23,456.78") == 123456.78

    def test_returns_zero_for_invalid_string(self):
        assert parse_amount("abc") == 0.0

    def test_strips_whitespace(self):
        assert parse_amount("  2000  ") == 2000.0


# ── guess_category() ─────────────────────────────────────────────────────────

class TestGuessCategory:
    def test_kfc_is_dining(self):
        assert guess_category("KFC DHA") == "dining"

    def test_shell_is_fuel(self):
        assert guess_category("Shell Petrol Station") == "fuel"

    def test_imtiaz_is_groceries(self):
        assert guess_category("Imtiaz Super Market") == "groceries"

    def test_kelectric_is_utilities(self):
        assert guess_category("KElectric Bill") == "utilities"

    def test_ibft_is_transfer(self):
        assert guess_category("IBFT Sent") == "transfer"

    def test_atm_is_atm(self):
        assert guess_category("ATM Withdrawal") == "atm"

    def test_shifa_is_medical(self):
        assert guess_category("Shifa Hospital") == "medical"

    def test_unknown_merchant_is_other(self):
        assert guess_category("Random Unknown Corp") == "other"

    def test_case_insensitive(self):
        assert guess_category("KFC DHA") == guess_category("kfc dha")


# ── is_meezan_non_transaction() ───────────────────────────────────────────────

class TestMeezanNonTransaction:
    def test_login_alert_is_skipped(self):
        assert is_meezan_non_transaction("You have successfully logged on to Meezan Internet Banking") is True

    def test_otp_message_is_skipped(self):
        assert is_meezan_non_transaction("Your OTP for login is 123456") is True

    def test_password_reset_is_skipped(self):
        assert is_meezan_non_transaction("Your password has been reset successfully") is True

    def test_transaction_with_pkr_is_not_skipped(self):
        assert is_meezan_non_transaction("PKR 5,000 sent to John Doe with the following details") is False

    def test_no_pkr_amount_is_skipped(self):
        assert is_meezan_non_transaction("Dear Customer, your profile has been updated") is True


# ── parse_scb() ───────────────────────────────────────────────────────────────

class TestParseScb:
    def test_parses_debit_transaction(self):
        text = "SCBPL: PKR 1,500.00 have been paid at KFC DHA using Debit Card on 01-03-26"
        result = parse_scb(text, gmail_id="g1")
        assert result is not None
        assert result["tx_type"] == "debit"
        assert result["amount"] == 1500.0
        assert result["bank"] == "SCB"
        assert result["confidence"] == "high"

    def test_debit_merchant_normalized(self):
        text = "SCBPL: PKR 2,000 have been debited at Shell Clifton using Card on 01-03-26"
        result = parse_scb(text)
        assert result is not None
        assert result["merchant"]  # not empty

    def test_parses_credit_transaction(self):
        text = "SCBPL: PKR 80,000 has been credited to your account Salary on 01-03-26"
        result = parse_scb(text, gmail_id="g2")
        assert result is not None
        assert result["tx_type"] == "credit"
        assert result["amount"] == 80000.0
        assert result["category"] == "transfer"

    def test_gmail_id_stored_in_result(self):
        text = "SCBPL: PKR 500 have been paid at Utility Store using Card on 01-03-26"
        result = parse_scb(text, gmail_id="unique-123")
        assert result["gmail_id"] == "unique-123"

    def test_fallback_pattern_on_partial_match(self):
        text = "SCBPL: PKR 3,000 some unstructured alert text"
        result = parse_scb(text)
        assert result is not None
        assert result["amount"] == 3000.0
        assert result["confidence"] == "low"

    def test_returns_none_for_non_scb_text(self):
        assert parse_scb("Your internet banking session has expired") is None

    def test_tx_date_parsed_from_email(self):
        text = "SCBPL: PKR 500 have been paid at KFC using Card on 15-02-26"
        result = parse_scb(text)
        assert result is not None
        assert "2026-02-15" in result["tx_date"]

    def test_raw_text_truncated_to_500_chars(self):
        long_text = ("SCBPL: PKR 500 have been paid at KFC using Card on 01-03-26 " + "x" * 600)
        result = parse_scb(long_text)
        assert result is not None
        assert len(result["raw_text"]) <= 500


# ── parse_meezan() ────────────────────────────────────────────────────────────

class TestParseMeezan:
    def test_parses_sent_to_transaction(self):
        text = "PKR 5,000 sent to John Doe on account with the following details"
        result = parse_meezan(text)
        assert result is not None
        assert result["tx_type"] == "debit"
        assert result["amount"] == 5000.0
        assert result["bank"] == "Meezan"

    def test_parses_credit_transaction(self):
        text = "PKR 50,000 is credited to your account"
        result = parse_meezan(text)
        assert result is not None
        assert result["tx_type"] == "credit"
        assert result["category"] == "transfer"

    def test_skips_login_notification(self):
        text = "Dear Customer, You have successfully logged on to Meezan Internet Banking."
        assert parse_meezan(text) is None

    def test_skips_otp_message(self):
        text = "Your OTP for Meezan Internet Banking is 789012"
        assert parse_meezan(text) is None

    def test_handles_html_encoded_email(self):
        html = "<p>PKR&nbsp;2,000 sent to Noman Khan on account with the following details</p>"
        result = parse_meezan(html)
        assert result is not None
        assert result["amount"] == 2000.0

    def test_gmail_id_stored(self):
        text = "PKR 1,000 sent to ABC on account with the following details"
        result = parse_meezan(text, gmail_id="meezan-id-99")
        assert result["gmail_id"] == "meezan-id-99"

    def test_returns_none_for_empty_text(self):
        assert parse_meezan("") is None

    def test_returns_none_for_text_with_no_pkr(self):
        assert parse_meezan("Your Meezan account profile has been updated.") is None


# ── parse_email() (router) ────────────────────────────────────────────────────

class TestParseEmailRouter:
    def test_routes_scb_sender_to_scb_parser(self):
        text = "SCBPL: PKR 1,500 have been paid at KFC using Card on 01-03-26"
        result = parse_email(text, sender="alerts.pk@sc.com")
        assert result is not None
        assert result["bank"] == "SCB"

    def test_routes_meezan_sender_to_meezan_parser(self):
        text = "PKR 3,000 sent to John on account with the following details"
        result = parse_email(text, sender="no-reply@meezanbank.com")
        assert result is not None
        assert result["bank"] == "Meezan"

    def test_unknown_sender_returns_none(self):
        result = parse_email("some text", sender="unknown@randombank.com")
        assert result is None

    def test_sender_match_is_case_insensitive(self):
        text = "SCBPL: PKR 500 have been paid at Shop using Card on 01-03-26"
        result = parse_email(text, sender="Alerts.PK@SC.COM")
        assert result is not None

    def test_gmail_id_passed_through_to_result(self):
        text = "SCBPL: PKR 500 have been paid at Shop using Card on 01-03-26"
        result = parse_email(text, sender="alerts.pk@sc.com", gmail_id="pass-through-id")
        assert result["gmail_id"] == "pass-through-id"
