# Play Console Data Safety Form — Answers & Rationale

Reference for filling the Google Play "Data safety" section consistently on
every submission. Based on the code as of July 2026 (no backend, no analytics,
all data on-device).

## Overview answers

| Question | Answer | Rationale |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No** | Play defines "collected" as data transmitted off the device. All transactions, tokens and the PIN stay on-device; the only network traffic is the user's own Google OAuth/Gmail calls to Google, which is "ephemeral processing" / user-initiated access to their own account. |
| Is all of the user data collected by your app encrypted in transit? | Yes (asked only if collecting) | OAuth/Gmail traffic is HTTPS. |
| Do you provide a way for users to request that their data is deleted? | Yes | Uninstall wipes everything; Reset PIN wipes DB + tokens; Gmail access revocable at myaccount.google.com/permissions. |

## Why "No" to collection is defensible

- Financial info (transactions): parsed and stored in SQLite on-device only.
- Personal info (email address): the Google account is used only on-device to
  authenticate with Google; the developer never receives it.
- App has no server, no crash reporting, no analytics, no ads SDKs.

If any off-device feature is ever added (backup, crash reporting, analytics),
this form MUST be redone: financial info + email would become "collected".

## Related declarations (Play Console)

- **Financial features declaration**: personal expense tracking only — the app
  does not provide loans, payments, or money transfers.
- **Restricted-scope OAuth**: the app uses `gmail.readonly`. While the OAuth
  consent screen is in **Testing** mode: max 100 test users, users see an
  "unverified app" screen, and refresh tokens expire after 7 days (testers
  must reconnect Gmail weekly). Public production release requires Google
  OAuth verification incl. a CASA security assessment.
- **Privacy policy URL**: host `docs/privacy-policy.md` publicly (GitHub Pages)
  and link it in both Play Console and the OAuth consent screen.
