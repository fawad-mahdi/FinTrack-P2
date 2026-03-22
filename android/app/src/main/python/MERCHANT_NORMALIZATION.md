# Merchant Name Normalization (US-19)

## Overview

The merchant name normalization system cleans up messy merchant names from bank SMS alerts, making them consistent and readable. This improves the user experience and makes category guessing more accurate.

## Implementation

### 1. Normalization Engine (`merchants.py`)

The core normalization logic is in `merchants.py`:

- **DEFAULT_RULES**: A list of ~105 pattern-to-clean-name mappings for common Pakistani merchants
- **normalize_merchant()**: Main function that takes a raw merchant string and returns a clean display name
- **_basic_clean()**: Fallback function that title-cases and de-noises merchant names that don't match any rule

#### How it works:

1. Takes a raw merchant string (e.g., "IMTIAZ SUPER MKT KARACHI")
2. Searches through rules in order (case-insensitive substring match)
3. Returns the clean name from the first matching rule (e.g., "Imtiaz Super Market")
4. If no rule matches, applies basic cleanup (title-case, collapse spaces, truncate)

#### Rule Categories:

- **Grocery & Supermarkets**: Imtiaz, Al-Fatah, Chase Up, Carrefour, Metro, Hyperstar, Naheed, Agha's
- **Fuel Stations**: PSO, Shell, Total Parco, Attock, Caltex, Hascol, Byco
- **Food & Dining**: KFC, McDonald's, Pizza Hut, Domino's, Hardee's, Burger King, Subway, Nando's, Student Biryani, Cheezious, Salt'n Pepper, Foodpanda
- **Clothing & Fashion**: Ndure, Khaadi, Sapphire, Sana Safinaz, Gul Ahmed, Ideas, Outfitters, Bonanza, Junaid Jamshed, J., Lal's, Ego, Beechtree
- **Telecom**: Jazz, Telenor, Zong, Ufone, PTCL, Nayatel, Stormfiber, Wi-Tribe
- **Utilities**: K-Electric, SSGC, WAPDA, LESCO, IESCO, GEPCO, FESCO, HESCO, MEPCO
- **E-commerce & Tech**: Daraz, Amazon, Netflix, Spotify, Google, Apple, Microsoft, Adobe
- **Banking & Fintech**: EasyPaisa, JazzCash, SadaPay, NayaPay
- **Healthcare**: Aga Khan Hospital, Shifa, Liaquat National, Sehat Kahani, Dawaai, Oladoc
- **Malls & Retail**: Centaurus, Dolmen, Emporium, Packages Mall, Liberty Books, Book Galleria
- **Ride-hailing & Delivery**: Uber, Careem, Bykea, TCS, Leopards, DHL

### 2. Database Integration (`database.py`)

The `merchant_rules` table stores normalization rules:

```sql
CREATE TABLE merchant_rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    pattern TEXT NOT NULL,
    clean_name TEXT NOT NULL,
    is_default INTEGER DEFAULT 0,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);
```

- **is_default=1**: Rules from DEFAULT_RULES (seeded on first run)
- **is_default=0**: Custom rules added by users (future feature)

#### Functions:

- **get_merchant_rules()**: Returns all rules ordered by priority (custom first, then default)
- **_seed_merchant_rules()**: Seeds the table with DEFAULT_RULES on first database initialization

### 3. Parser Integration (`parsers.py`)

Both `parse_scb()` and `parse_meezan()` call `normalize_merchant()` after extracting the raw merchant name:

```python
merchant_raw = m.group(2).strip()
merchant = normalize_merchant(merchant_raw)  # US-19
```

The normalized merchant name is then passed to `guess_category()` for better category detection.

#### Example Flow:

1. **Raw SMS**: "SCBPL: PKR 1,500.00 have been paid at IMTIAZ SUPER MKT KARACHI using your card on 15-01-24"
2. **Parser extracts**: "IMTIAZ SUPER MKT KARACHI"
3. **Normalization**: "Imtiaz Super Market"
4. **Category guessing**: "groceries" (matches "imtiaz" keyword)
5. **Stored in DB**: merchant="Imtiaz Super Market", category="groceries"

### 4. Caching

The normalization engine uses a module-level cache to avoid repeated database queries:

- **_rules_cache**: Populated on first call to `normalize_merchant()`
- **invalidate_rules_cache()**: Call this after adding/removing rules to refresh the cache

## Testing

Run the test suite to verify the implementation:

```bash
python3 android/app/src/main/python/test_merchants.py
```

The test suite verifies:

1. ✓ Merchant normalization works correctly
2. ✓ SCB parser uses normalized merchant names
3. ✓ Meezan parser uses normalized merchant names
4. ✓ Database seeding works correctly
5. ✓ Category guessing works with normalized names

## Benefits

1. **Consistency**: "IMTIAZ SUPER MKT", "imtiaz", "Imtiaz Super Market" all normalize to "Imtiaz Super Market"
2. **Readability**: Clean, properly capitalized merchant names in the UI
3. **Better categorization**: Normalized names match category keywords more reliably
4. **Extensibility**: Custom rules can be added to the database (future feature)

## Future Enhancements

- Admin UI to add/edit/delete custom merchant rules (US-20)
- Machine learning to suggest new rules based on user edits
- Merchant logo/icon mapping for visual recognition
- Fuzzy matching for typos and variations

## Requirements Satisfied

This implementation satisfies **Requirement 9.5** from the requirements document:

> THE Android_App SHALL normalize merchant names from bank alerts to improve consistency and readability

## Related User Stories

- **US-19**: Merchant name normalization (this feature)
- **US-13**: Category learning from user edits (uses normalized merchant names)
- **US-16**: Category spending summary (benefits from consistent merchant names)
