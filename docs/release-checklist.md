# Play Store Release Checklist (manual steps)

The code side is done. These are the steps only you (account owner) can do,
in order. Items marked ⚠ block everything after them.

## 1. Keystore (once) ⚠

```bash
mkdir -p ~/keystores
keytool -genkeypair -v -keystore ~/keystores/fintrack-upload.jks \
  -alias fintrack-upload -keyalg RSA -keysize 4096 -validity 10000
```

Add to `~/.gradle/gradle.properties` (NOT the repo):

```
FINTRACK_KEYSTORE_PATH=/Users/fawad/keystores/fintrack-upload.jks
FINTRACK_KEYSTORE_PASSWORD=...
FINTRACK_KEY_ALIAS=fintrack-upload
FINTRACK_KEY_PASSWORD=...
```

Back up the keystore + passwords in a password manager. Losing the upload key
is recoverable via Play support; losing it silently is a world of pain.

## 2. Play Console app (once) ⚠

1. Create the app in Play Console (`com.fintrack.pk`), category Finance.
2. Build and upload: `cd android && ./gradlew bundleRelease`
   → `app/build/outputs/bundle/release/app-release.aab` to a **closed testing** track.
3. Enroll in **Play App Signing** (default).
4. Record two SHA-1s:
   - Play Console → Setup → App integrity → **App signing key certificate** SHA-1
   - Debug keystore: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1`

## 3. Google Cloud Console OAuth (once) ⚠

Project: the existing FinTracker project.

1. OAuth consent screen: stay in **Testing** mode. Set app name, support email,
   privacy policy URL (step 4), scope `gmail.readonly` only. Add every tester's
   Gmail address (max 100).
2. Credentials → Create credentials → OAuth client ID → **Android**:
   - Client A: package `com.fintrack.pk`, SHA-1 = **Play App signing** SHA-1 (release)
   - Client B: package `com.fintrack.pk`, SHA-1 = **debug keystore** SHA-1 (dev)
3. Put the client IDs in `~/.gradle/gradle.properties`:
   ```
   FINTRACK_OAUTH_CLIENT_ID_RELEASE=NNNN-xxxx.apps.googleusercontent.com
   FINTRACK_OAUTH_CLIENT_ID_DEBUG=NNNN-yyyy.apps.googleusercontent.com
   ```
   (Client IDs are not secrets, but keep the pattern.)
4. **Rotate/delete the old client secret** ("Desktop" client
   `746798969138-...`): it shipped inside every previously built APK and must
   be treated as compromised. The desktop app can keep using a freshly
   downloaded credentials.json from a NEW desktop client if needed.

## 4. Privacy policy hosting ⚠

Host `docs/privacy-policy.md` at a public URL (GitHub Pages / gist). Link it in
both the Play listing and the OAuth consent screen.

## 5. On-device OAuth verification (debug client first)

```bash
cd android && ./gradlew installDebug
```

1. Fresh install → Settings → Connect Gmail → Google consent (expect the
   "unverified app" warning — Continue) → "Gmail connected" toast.
2. Verify token has no secret:
   `adb shell run-as com.fintrack.pk cat files/config/token.json` →
   `"client_secret": ""` and `"refresh_token"` non-empty.
   **If refresh_token is empty, stop — the offline-access params need debugging.**
3. Sync a date range → transactions import; check Pending tab for
   medium/low/unparsed rows.
4. Force-expire: edit `expiry` in token.json to a past date
   (`adb shell run-as ...`), sync again → both Kotlin (logcat `OAuthTokenManager`)
   and Python (`files/logs/server_logs.txt`) refresh without a secret.
5. Upgrade path: install an OLD apk, connect Gmail, install the new build on
   top → app should prompt to reconnect Gmail (old-client token auto-deleted),
   not crash.
6. API token check: `adb shell "curl -s http://127.0.0.1:8000/api/transactions"`
   → must return `{"detail":"Missing or invalid API token"}`.

Then repeat 1–3 with the **release** build (`./gradlew installRelease` needs
the keystore configured) — this validates minified OAuth + the release client.

## 6. Bank sender validation (ongoing)

12 of 14 registry domains in `bank_registry.py` are unvalidated guesses
(`verified: False`). For each bank a tester actually uses, confirm one real
alert email's sender domain and fix the registry entry (one line), then flip
`verified: True`. SCB + Meezan are already verified.

## 7. Play listing + rollout

- Store listing: title, short/full description, ≥2 phone screenshots, 512px
  icon, 1024×500 feature graphic.
- Content rating questionnaire (Finance, no UGC); target audience 18+.
- **Financial features declaration**: personal expense tracking; no loans/payments.
- Data safety form: answers in `docs/data-safety.md`.
- Testers on the closed track = same people as OAuth consent-screen test users.
- Country: Pakistan. Roll out.

## Known product caveats (communicate to testers)

- **Weekly Gmail reconnect**: Testing-mode consent + restricted scope means
  Google expires refresh tokens after 7 days. Full fix = Google OAuth
  verification + CASA assessment (deferred until traction justifies cost).
- Testers see an "unverified app" interstitial at first connect — expected.
- Unparsed bank emails now appear in Pending Review as "Unparsed email
  (review)" rows with the raw text, instead of being dropped.
