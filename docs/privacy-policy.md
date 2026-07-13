# FinTracker PK — Privacy Policy

_Last updated: 12 July 2026_

FinTracker PK ("the app") is a personal finance tracker that reads bank transaction
alert emails from your Gmail account and organizes them into an expense tracker
**stored entirely on your own device**.

This policy must be hosted at a public URL (e.g. GitHub Pages) and linked from
the Google Play listing and the Google OAuth consent screen.

## What the app accesses

- **Gmail (read-only).** With your explicit consent via Google sign-in, the app
  uses the `gmail.readonly` scope to search for and read transaction alert
  emails sent by Pakistani banks and mobile wallets (e.g. HBL, Meezan, UBL,
  Standard Chartered, JazzCash, EasyPaisa). Only emails from these known bank
  sender addresses are requested.

## What the app stores, and where

- Parsed transaction details (amount, merchant, date, category, bank name and
  a short excerpt of the alert email) are stored in a local SQLite database
  **on your device only**.
- Your Google OAuth token is stored in the app's private storage on your device.
- Your app PIN is stored encrypted (AES-256, Android Keystore) on your device.

## What the app does NOT do

- No data is transmitted to any server operated by the developer. The app has
  no backend; the only network calls are to Google's own APIs (sign-in, token
  refresh, Gmail read) over HTTPS.
- No data is shared with or sold to any third party.
- No analytics, advertising, or tracking SDKs are included.
- Emails are never stored in full and never leave your device.

## Google API Services — Limited Use disclosure

FinTracker PK's use and transfer of information received from Google APIs
adheres to the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including the Limited Use requirements. Gmail data is used solely to provide
the user-facing transaction-tracking feature, is processed on-device, and is
never transferred to third parties or used for advertising.

## Data retention and deletion

- All app data lives on your device. Uninstalling the app deletes the database,
  tokens, and settings.
- You can revoke the app's Gmail access at any time at
  [myaccount.google.com/permissions](https://myaccount.google.com/permissions).
- The in-app "Reset PIN" flow also wipes the local database and stored tokens.

## Children

The app is intended for adults managing their personal finances and is not
directed at children under 18.

## Contact

For privacy questions, contact: **fawadmahdi@gmail.com**

## Changes

Material changes to this policy will be reflected here with an updated date
before taking effect.
