"""
Test suite for merchant name normalization (US-19)

This test verifies that:
1. Merchant normalization works correctly
2. Parsers use normalized merchant names
3. Category guessing works with normalized names
4. Database seeding works correctly
"""

import sys
import os
import tempfile
import shutil

sys.path.insert(0, os.path.dirname(__file__))

from merchants import normalize_merchant
from parsers import parse_scb, parse_meezan, guess_category
from database import init_db, get_merchant_rules


def test_normalize_merchant():
    """Test basic merchant normalization"""
    test_cases = [
        ("IMTIAZ SUPER MKT KARACHI", "Imtiaz Super Market"),
        ("pso fuel station", "PSO"),
        ("KFC LAHORE", "KFC"),
        ("chase up supermarket", "Chase Up"),
        ("EASYPAISA TRANSFER", "EasyPaisa"),
        ("K-ELECTRIC BILL PAYMENT", "K-Electric"),
        ("Some Random Merchant", "Some Random Merchant"),  # No match, basic clean
        ("", "Unknown"),  # Empty string
    ]
    
    print("Testing merchant normalization...")
    for raw, expected in test_cases:
        result = normalize_merchant(raw)
        status = "✓" if result == expected else "✗"
        print(f"  {status} {raw:40} -> {result:30} (expected: {expected})")
        assert result == expected, f"Expected '{expected}', got '{result}'"
    
    print("✓ All normalization tests passed!\n")


def test_scb_parser_with_normalization():
    """Test SCB parser uses normalized merchant names"""
    test_cases = [
        {
            "text": "SCBPL: PKR 1,500.00 have been paid at IMTIAZ SUPER MKT KARACHI using your card on 15-01-24",
            "expected_merchant": "Imtiaz Super Market",
            "expected_category": "groceries",
        },
        {
            "text": "SCBPL: PKR 3,200.00 have been debited at PSO FUEL STATION using your card on 16-01-24",
            "expected_merchant": "PSO",
            "expected_category": "fuel",
        },
        {
            "text": "SCBPL: PKR 850.00 have been paid at KFC GULBERG using your card on 17-01-24",
            "expected_merchant": "KFC",
            "expected_category": "dining",
        },
    ]
    
    print("Testing SCB parser with normalization...")
    for test in test_cases:
        result = parse_scb(test["text"], "test_id", "2024-01-15T10:00:00")
        assert result is not None, "Parser returned None"
        
        merchant = result["merchant"]
        category = result["category"]
        
        status = "✓" if merchant == test["expected_merchant"] else "✗"
        print(f"  {status} Merchant: {merchant:30} (expected: {test['expected_merchant']})")
        
        status = "✓" if category == test["expected_category"] else "✗"
        print(f"  {status} Category: {category:30} (expected: {test['expected_category']})")
        
        assert merchant == test["expected_merchant"], f"Expected merchant '{test['expected_merchant']}', got '{merchant}'"
        assert category == test["expected_category"], f"Expected category '{test['expected_category']}', got '{category}'"
    
    print("✓ All SCB parser tests passed!\n")


def test_meezan_parser_with_normalization():
    """Test Meezan parser uses normalized merchant names"""
    test_cases = [
        {
            "text": "PKR 2,500.00 is Debited from your account 1234567890 with the following details Merchant: PSO FUEL STATION LAHORE",
            "expected_merchant": "PSO",
            "expected_category": "fuel",
        },
        {
            "text": "PKR 1,200.00 sent to EASYPAISA ACCOUNT (AC 03001234567) on account",
            "expected_merchant": "EasyPaisa",
            "expected_category": "transfer",
        },
    ]
    
    print("Testing Meezan parser with normalization...")
    for test in test_cases:
        result = parse_meezan(test["text"], "test_id", "2024-01-15T10:00:00")
        assert result is not None, "Parser returned None"
        
        merchant = result["merchant"]
        category = result["category"]
        
        status = "✓" if merchant == test["expected_merchant"] else "✗"
        print(f"  {status} Merchant: {merchant:30} (expected: {test['expected_merchant']})")
        
        status = "✓" if category == test["expected_category"] else "✗"
        print(f"  {status} Category: {category:30} (expected: {test['expected_category']})")
        
        assert merchant == test["expected_merchant"], f"Expected merchant '{test['expected_merchant']}', got '{merchant}'"
        assert category == test["expected_category"], f"Expected category '{test['expected_category']}', got '{category}'"
    
    print("✓ All Meezan parser tests passed!\n")


def test_database_seeding():
    """Test that merchant_rules table is properly seeded"""
    # Create temporary database
    temp_dir = tempfile.mkdtemp()
    os.environ['FINTRACK_DB_DIR'] = temp_dir
    
    try:
        print("Testing database seeding...")
        
        # Initialize database
        init_db()
        
        # Get merchant rules
        rules = get_merchant_rules()
        
        print(f"  ✓ Database seeded with {len(rules)} merchant rules")
        
        # Verify some key rules exist
        rules_dict = {pattern: clean_name for pattern, clean_name in rules}
        
        key_rules = [
            ("imtiaz", "Imtiaz Super Market"),
            ("pso", "PSO"),
            ("kfc", "KFC"),
            ("easypaisa", "EasyPaisa"),
        ]
        
        for pattern, expected_name in key_rules:
            assert pattern in rules_dict, f"Pattern '{pattern}' not found in rules"
            assert rules_dict[pattern] == expected_name, f"Expected '{expected_name}', got '{rules_dict[pattern]}'"
            print(f"  ✓ Rule verified: {pattern} -> {expected_name}")
        
        print("✓ Database seeding test passed!\n")
        
    finally:
        # Clean up
        shutil.rmtree(temp_dir)


def test_category_guessing_with_normalized_names():
    """Test that category guessing works better with normalized names"""
    test_cases = [
        ("Imtiaz Super Market", "groceries"),
        ("PSO", "fuel"),
        ("KFC", "dining"),
        ("K-Electric", "utilities"),
        ("EasyPaisa", "transfer"),
    ]
    
    print("Testing category guessing with normalized names...")
    for merchant, expected_category in test_cases:
        category = guess_category(merchant)
        status = "✓" if category == expected_category else "✗"
        print(f"  {status} {merchant:30} -> {category:15} (expected: {expected_category})")
        assert category == expected_category, f"Expected '{expected_category}', got '{category}'"
    
    print("✓ All category guessing tests passed!\n")


if __name__ == "__main__":
    print("=" * 70)
    print("Merchant Name Normalization Test Suite (US-19)")
    print("=" * 70)
    print()
    
    try:
        test_normalize_merchant()
        test_scb_parser_with_normalization()
        test_meezan_parser_with_normalization()
        test_database_seeding()
        test_category_guessing_with_normalized_names()
        
        print("=" * 70)
        print("✓ ALL TESTS PASSED!")
        print("=" * 70)
        
    except AssertionError as e:
        print(f"\n✗ TEST FAILED: {e}")
        sys.exit(1)
    except Exception as e:
        print(f"\n✗ UNEXPECTED ERROR: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
