# Android Google OAuth Security and Identity Audit

## 1. Executive Summary

**Release recommendation: No-Go.** This is a static-only review; no build, test, or runtime validation was performed.

The Android Gmail OAuth implementation is not production-ready. The repository contains a broken server-loopback OAuth path, persists refresh tokens and a client secret in plaintext, permits global cleartext traffic, and provides no verifiable release signing, registered redirect, or client-ID evidence.

Top risks:

- Critical: OAuth-related Kotlin code targets `/api/oauth/*` routes that do not exist in the embedded server.
- High: Refresh tokens and `client_secret` are stored as ordinary JSON.
- High: Production/dev redirect identity and Google Cloud registration cannot be validated; dev and production claim the same callback scheme.
- High: Local API authentication falls back to `PIN=1234` with a process-global session flag.
- High: Global cleartext traffic is enabled.

## 2. Authentication Flow Understanding

Detected stack:

- Google OAuth authorization-code flow for Gmail, not Google Sign-In or Credential Manager.
- Native AppAuth path: Settings -> `MainActivity.initiateOAuthFlow()` -> browser custom tab -> `OAuthCallbackActivity` -> Google token endpoint -> `token.json`.
- Separate embedded Python/FastAPI loopback flow: Kotlin references `/api/oauth/url`, `/oauth/callback`, and `/api/oauth/pending`.
- Embedded FastAPI API and WebView PIN gate.

The implemented design is inconsistent. The normal WebView Sync flow calls the local API directly. Repository design documentation explicitly says OAuth redirect was intentionally removed and desktop authentication is expected, while stale Kotlin retains a separate server-loopback OAuth design. The server loaded by `android/app/src/main/python/main.py` exposes no OAuth routes.

There is no remote backend session, no ID-token verification flow, and no Google account identity established for the application itself. This is Gmail delegated authorization only.

## 3. Review Scope and Evidence Base

Reviewed:

- Android manifest, Gradle, build variants, assets, OAuth callback, token manager, Main/Settings activities, WebView, local server, Python Gmail adapter, backup/network policy, logging, and OAuth tests.
- No screenshots were supplied.
- No real `credentials.json`, Google Cloud Console configuration, registered SHA-1/SHA-256 values, signing keys, release APK/AAB, redirect registration, or runtime logs were available.

Confidence is high for source-level defects. Registration and signing correctness are **Unknown**, which is release-blocking under the supplied gate.

## 4. Findings by Severity

### Critical

#### AUTH-01: Broken and conflicting Android OAuth flow contracts

- **Severity / Category:** Critical, Bug / Architecture
- **Root Cause:** Two OAuth designs were started. Kotlin retains a server-loopback contract, but the embedded server does not implement it; repository documentation says the redirect was intentionally removed.
- **Evidence:** `android/app/src/main/java/com/fintrack/pk/ui/MainActivity.kt` requests `/api/oauth/url` and `/api/oauth/pending`; `android/app/src/main/python/server.py` has no OAuth routes; `docs/superpowers/specs/2026-03-23-gmail-import-sync-design.md` says no OAuth redirect is implemented.
- **Expected Behaviour:** Android has one supported Gmail authorization path with matching UI, Kotlin, server, callback, and token-storage contracts.
- **Actual / Observed Behaviour:** Direct AppAuth, missing server endpoints, and a documented desktop-only fallback coexist.
- **Why This Is a Problem:** OAuth success depends on which stale or active entry point is reached. There is no coherent supported Android flow.
- **User / Business Impact:** First-time Gmail connection and recovery after revocation can fail or present contradictory behavior.
- **Recommended Fix:** Choose one architecture. Prefer hardened native AppAuth for Android; remove the dead server-loopback contract and update documentation/UI accordingly.
- **Patch Suggestion:** Remove `startServerOAuthFlow()` and `completePendingOAuthExchange()` after migrating all entry points to the direct flow, or implement and test the missing server endpoints. Do not ship both.
- **Acceptance Criteria:** No stale `/api/oauth/*` references remain; first login, cancellation, revoked consent, and reconnect work in a signed Android build.
- **Confidence:** High.

### High

#### AUTH-02: Refresh tokens and OAuth client material are stored unencrypted

- **Severity / Category:** High, Security
- **Root Cause:** `token.json` is used as a shared Kotlin/Python credential store and includes access token, refresh token, and `client_secret`.
- **Evidence:** `OAuthTokenManager.saveTokenData`, `OAuthTokenManager.saveInitialTokens`, `MainActivity.completePendingOAuthExchange`, and `auth_gmail._AndroidCredentials.refresh` write raw JSON credentials.
- **Expected Behaviour:** Native clients use a public Android OAuth client with PKCE; long-lived credentials are Keystore-protected and never bundled with a usable secret.
- **Actual / Observed Behaviour:** Credentials are copied from assets to app files, then raw token JSON is written with `writeText`/`json.dump`.
- **Why This Is a Problem:** The security-crypto dependency is present but unused for OAuth storage. Backup exclusions cover `token.json` only, not all credential or pending-state artifacts.
- **User / Business Impact:** Device compromise, transfer/backup gaps, or debugging exposure can grant ongoing Gmail read access until revocation.
- **Recommended Fix:** Remove `client_secret` from the mobile architecture. Use Android client registration, native token brokering, and Keystore-backed encrypted storage. Do not expose refresh tokens to Python files.
- **Patch Suggestion:** Replace file-based token sharing with a native `GmailTokenBroker` that supplies short-lived access tokens to Python only when required.
- **Acceptance Criteria:** APK contains no client secret; token files contain no refresh token; all OAuth artifacts are excluded from backup and device transfer.
- **Confidence:** High.

#### AUTH-03: OAuth registration, redirect identity, and signing alignment are unproven and inconsistent

- **Severity / Category:** High, Configuration
- **Root Cause:** The code uses a desktop-style `installed` credential document, while Android package/signature registration evidence is absent. The dev build changes application ID but the callback scheme remains hardcoded to production.
- **Evidence:** `android/app/build.gradle.kts`, `Constants.OAUTH_REDIRECT_URI`, `AndroidManifest.xml`, and `credentials.json.example`.
- **Expected Behaviour:** Separate registered Android clients per variant, package name, signing certificate, and redirect identity.
- **Actual / Observed Behaviour:** The example lists only `http://localhost`; direct AppAuth sends `com.fintrack.pk://oauth2callback`; the dev app still claims the production callback scheme; no release signing configuration or SHA evidence exists.
- **Why This Is a Problem:** Redirect rejection, wrong-app callback resolution, and release-only login failure are credible outcomes.
- **User / Business Impact:** A release can pass local development yet fail authentication in production.
- **Recommended Fix:** Register Android OAuth clients for every released package/signing identity. Make redirect scheme variant-aware and consume the Gradle placeholder in the manifest.
- **Acceptance Criteria:** Release and dev have distinct verified registrations; signed release SHA-1/SHA-256 records are documented; redirect tests pass for each variant.
- **Confidence:** High for missing/mismatched source evidence; external registration is Unknown.

#### AUTH-04: Local API authentication defaults to `1234` and has no real session boundary

- **Severity / Category:** High, Security
- **Root Cause:** FastAPI uses a process-global `_authenticated` set and a default PIN instead of a per-session, caller-bound credential.
- **Evidence:** `android/app/src/main/python/server.py` defines `PIN = os.getenv("PIN", "1234")`, adds the literal `"user"` to a global set, and all protected endpoints call `check_auth()`.
- **Expected Behaviour:** Native lock state and local API authorization share one non-default security model with session invalidation and rate limiting.
- **Actual / Observed Behaviour:** Native PIN is skipped at startup, while the WebView/API gate accepts `1234` when no environment PIN is present. One successful request authenticates the entire server process.
- **Why This Is a Problem:** The embedded server is a sensitive trust boundary but has no caller identity, session token, logout route, or brute-force control.
- **User / Business Impact:** The local financial/Gmail API has weak access control.
- **Recommended Fix:** Remove the Python PIN as an authorization authority. Use a native-created, per-launch capability token and a single native lock policy.
- **Acceptance Criteria:** No default PIN; API requires a high-entropy per-launch credential; logout invalidates all API access; brute-force protection exists.
- **Confidence:** High.

#### AUTH-05: Manifest enables cleartext traffic globally

- **Severity / Category:** High, Configuration / Security
- **Root Cause:** `android:usesCleartextTraffic="true"` is global, while the intended restrictive network security configuration is not attached to the application.
- **Evidence:** `android/app/src/main/AndroidManifest.xml` and `android/app/src/main/res/xml/network_security_config.xml`.
- **Expected Behaviour:** Cleartext is allowed only where demonstrably required for the local loopback server.
- **Actual / Observed Behaviour:** All app networking is permitted to use cleartext.
- **Why This Is a Problem:** It broadens downgrade and man-in-the-middle exposure beyond localhost.
- **User / Business Impact:** Network security guarantees are weaker than the source comments claim.
- **Recommended Fix:** Attach the network security config and set the manifest default to deny cleartext.
- **Patch Suggestion:** Set `android:networkSecurityConfig="@xml/network_security_config"` and `android:usesCleartextTraffic="false"`; verify loopback behavior on supported Android versions.
- **Acceptance Criteria:** Release manifest denies non-loopback HTTP and HTTPS OAuth remains functional.
- **Confidence:** High.

#### AUTH-06: Direct callback lacks demonstrable state binding and explicit PKCE enforcement

- **Severity / Category:** High, Security
- **Root Cause:** The exported callback exchanges every parsed AppAuth response immediately; no expected request/state is persisted or compared, and the direct request has no explicit code-verifier configuration.
- **Evidence:** `MainActivity.initiateOAuthFlow` builds a request without explicit verifier setup; `OAuthCallbackActivity.onCreate` parses the incoming intent and exchanges it immediately.
- **Expected Behaviour:** An authorization response must be bound to a request initiated by this app, with PKCE and state validation before token exchange.
- **Actual / Observed Behaviour:** The only explicit PKCE/state implementation is in unused Python code; the direct AppAuth path does not prove equivalent protection.
- **Why This Is a Problem:** Custom-scheme callback injection/interception protection cannot be demonstrated.
- **User / Business Impact:** A malicious or mismatched callback can interfere with identity establishment.
- **Recommended Fix:** Persist minimal pending request state in Keystore-backed storage, require exact state match, and explicitly test PKCE behavior for the pinned AppAuth version.
- **Acceptance Criteria:** Forged/mismatched callbacks never exchange a code; instrumentation tests prove PKCE/state enforcement.
- **Confidence:** Medium.

### Medium

#### AUTH-07: Disconnect, relogin, and account switching are incomplete

- **Severity / Category:** Medium, Reliability / Security
- **Root Cause:** Disconnect only deletes `token.json`; it does not revoke consent, clear pending artifacts, end the embedded API session, or request account selection.
- **Evidence:** `SettingsActivity.disconnectGmail` and `OAuthTokenManager.deleteToken`.
- **Expected Behaviour:** Disconnect revokes or clearly explains retained consent, removes all local authorization state, and permits deliberate account selection on reconnect.
- **Actual / Observed Behaviour:** The Google grant can survive and the server's process-global authenticated state remains.
- **Why This Is a Problem:** Local token deletion is not a complete logout.
- **User / Business Impact:** Users cannot reliably switch accounts or understand whether Gmail access was revoked.
- **Recommended Fix:** Add a real logout/disconnect transaction with provider revocation, local cleanup, session invalidation, and explicit account-selection behavior.
- **Acceptance Criteria:** Reconnect can select another account and logout blocks all local API access.
- **Confidence:** High.

#### AUTH-08: OAuth lifecycle handling can strand or leak exchanges

- **Severity / Category:** Medium, Reliability
- **Root Cause:** `singleTask` callback handling lacks `onNewIntent`; token requests block with unbounded `CountDownLatch.await()`; callback work is not durable across recreation/process death.
- **Evidence:** `OAuthCallbackActivity.exchangeAuthorizationCode` and `OAuthTokenManager.performTokenRefresh`.
- **Expected Behaviour:** Cancellation-safe, lifecycle-aware asynchronous exchange with bounded timeouts and persisted request state.
- **Actual / Observed Behaviour:** Rotation, repeated callbacks, network hangs, or process death can leave the flow incomplete.
- **Why This Is a Problem:** Authorization is an external, interruptible workflow and cannot depend on a live Activity instance.
- **User / Business Impact:** Users may see failed or indefinitely pending connection attempts.
- **Recommended Fix:** Use lifecycle-aware suspending callbacks with timeout/cancellation and persist only minimal pending request metadata.
- **Acceptance Criteria:** Tests cover cancellation, rotation, duplicate callback, app kill, and network timeout.
- **Confidence:** High.

#### AUTH-09: Tests validate file shapes, not the shipped OAuth workflow

- **Severity / Category:** Medium, Testing
- **Root Cause:** OAuth instrumentation tests create mock JSON and assert fields; no test exercises callback handling, server-route availability, redirect matching, PKCE/state, or secure storage.
- **Evidence:** `android/app/src/androidTest/java/com/fintrack/pk/integration/OAuthIntegrationTest.kt` tests token existence, expiry, deletion, and JSON fields; it asserts `client_secret` is present.
- **Expected Behaviour:** End-to-end contract tests cover all implemented OAuth components and release variants.
- **Actual / Observed Behaviour:** Tests are disconnected from the actual conflicting Android/server OAuth architecture.
- **Why This Is a Problem:** The most important breakage is invisible to the existing test suite.
- **User / Business Impact:** Regressions reach release without a meaningful gate.
- **Recommended Fix:** Add mock-provider and device tests for every auth-critical scenario.
- **Acceptance Criteria:** CI detects missing OAuth routes, redirect mismatch, callback forgery, and plaintext storage regressions.
- **Confidence:** High.

#### AUTH-10: Security documentation materially misstates the implementation

- **Severity / Category:** Medium, Maintainability / Architecture
- **Root Cause:** Documentation says OAuth tokens are stored securely and Android PIN authentication is implemented, while source writes raw token files and skips native PIN at startup.
- **Evidence:** `android/README.md`, `MainActivity.startInitializationFlow`, and `OAuthTokenManager.saveTokenData`.
- **Expected Behaviour:** Release documentation accurately describes implemented security controls and supported onboarding.
- **Actual / Observed Behaviour:** Documentation describes controls the shipped source does not enforce.
- **Why This Is a Problem:** Teams cannot make sound release decisions from contradictory documentation.
- **User / Business Impact:** Misconfigured deployments and false security assurance.
- **Recommended Fix:** Update documentation only after the architecture is fixed; add release documentation review to the security checklist.
- **Acceptance Criteria:** Docs, code, and release configuration describe the same supported OAuth path.
- **Confidence:** High.

### Low

#### AUTH-11: OAuth error payloads are logged and exportable without redaction

- **Severity / Category:** Low, Security / Maintainability
- **Root Cause:** Full token-endpoint error text is logged, and Debug Activity exports all logs.
- **Evidence:** `MainActivity.completePendingOAuthExchange` and `DebugActivity.exportLogs`.
- **Expected Behaviour:** OAuth diagnostics are redacted and restricted in release builds.
- **Actual / Observed Behaviour:** Token-endpoint errors can be preserved and shared in debug logs.
- **Why This Is a Problem:** Provider error payloads may contain sensitive technical or account context.
- **Recommended Fix:** Redact OAuth parameters/error bodies and make diagnostics opt-in for non-release builds.
- **Acceptance Criteria:** OAuth logs contain no authorization code, token, client secret, or provider payload.
- **Confidence:** Medium.

### Informational

#### AUTH-12: This is Gmail delegated authorization, not application identity

- **Severity / Category:** Informational, Architecture
- **Evidence:** Only `gmail.readonly` scope and authorization-code handling are present; no ID-token audience/issuer verification exists.
- **Note:** Backend token verification is not applicable to the visible flow because no remote backend exchange exists. If a remote backend is intended but absent from this repository, its verification behavior is Unknown and release-blocking.

## 5. End-to-End Workflow Validation Matrix

| Scenario | Expected Behaviour | Observed / Inferred Behaviour | Status | Blocking? | Notes |
|---|---|---|---|---|---|
| First login | Complete Gmail consent | Conflicting direct, stale server, and desktop-only paths | Fail | Yes | AUTH-01 |
| Returning user | Use valid token | File presence/expiry only; Gmail validates later | Risk | Yes | No real app session validation |
| Cancelled login | Return cleanly | Direct path has a toast; supported architecture unclear | Risk | Yes | Mixed paths |
| No Google account | Clear recovery | No tested handling | Unknown | Yes | |
| Multiple accounts | Allow explicit selection | No selection request shown | Risk | Yes | AUTH-07 |
| Revoked consent | Clear token, reconnect | Recovery contract is inconsistent | Fail | Yes | AUTH-01 |
| Invalid/expired token | Refresh or reconnect | Refresh uses plaintext material; reconnect path is unclear | Risk | Yes | |
| Backend rejects token | Reject safely | No remote backend exists in scope | Unknown | Yes | |
| Network failure | Bounded retry/error | Blocking latches have no timeout | Risk | Yes | AUTH-08 |
| Interrupted flow | Resume safely | Pending state differs by path | Risk | Yes | |
| App killed during auth | Restore safely | No durable direct-flow state | Risk | Yes | |
| Rotation/process death | Preserve callback | Callback not lifecycle durable | Risk | Yes | |
| Logout/relogin | Invalidate local/upstream session | Deletes only token file | Risk | Yes | AUTH-07 |
| Dev vs release | Distinct registered callback | Callback scheme collision and missing registration evidence | Risk | Yes | AUTH-03 |

## 6. Configuration Validation Checklist

| Control | Result |
|---|---|
| Package/application identity alignment | Risk: `com.fintrack.pk.dev` still uses production callback scheme |
| Android client ID correctness | Unknown: real credentials excluded from repository |
| Web/server client ID correctness | Unknown: source uses desktop-style `installed` credentials |
| Redirect URI correctness | Fail/Risk: localhost template, custom URI direct path, and removed server redirect |
| Signing alignment | Unknown: no release signing/SHA evidence |
| Environment separation | Fail/Risk: one credential contract and callback collision |
| google-services consistency | N/A: Firebase/google-services is not used |
| Backend verification alignment | N/A for visible Gmail flow; Unknown if external backend exists |
| Logout/session invalidation | Fail/Risk: local token deletion only |

## 7. Security Review

MASVS-aligned assessment:

- **Storage:** Fails protected credential-storage expectations. Refresh tokens, client secret, and pending exchange data are raw JSON.
- **Network:** Fails least-cleartext expectations because cleartext is globally enabled.
- **Authentication/session:** Fails unified session handling. Native Keystore PIN exists but startup skips it; the WebView/API uses a separate defaultable PIN and global flag.
- **OAuth:** Least privilege is good (`gmail.readonly`), but redirect/client/state/PKCE controls are not proven.
- **Logging:** Error payloads can be exported without redaction.

## 8. Architecture and Code Quality Review

Positive controls include localhost binding, token expiry buffering, backup exclusion for `token.json`, and a narrow Gmail scope.

The implementation remains architecturally weak:

- Two mutually inconsistent OAuth designs exist.
- Two unrelated PIN/security models exist.
- Kotlin and Python duplicate token lifecycle logic and write the same file.
- Comments and documentation claim behavior that source does not provide.
- `appAuthRedirectScheme` is configured but not consumed by the manifest callback.
- The embedded server is a sensitive trust boundary but has no real session model.

## 9. Testing Gaps

Missing or inadequate:

- First-login, re-auth, cancellation, no-account, account-switch, revoke, and token-rejection tests.
- Callback state/PKCE and forged-deep-link tests.
- Release/dev redirect and signing verification tests.
- Secure-storage and backup/device-transfer tests for all OAuth artifacts.
- Local API session isolation, default-PIN rejection, rate-limit, logout, and cross-process access tests.
- Process death, rotation, duplicate callback, and timeout tests.

No test execution was performed, per the supplied static-analysis constraint.

## 10. Prioritised Remediation Plan

1. Select and implement one Android OAuth architecture; remove incompatible paths.
2. Register proper Android OAuth clients and release signing identities; make redirect handling variant-aware.
3. Remove mobile client-secret handling and plaintext token files.
4. Replace the local API's default/global PIN session with a native-owned per-launch authorization capability.
5. Disable global cleartext traffic and prove localhost-only behavior.
6. Add explicit state/PKCE protections and lifecycle-safe callback processing.
7. Implement real disconnect/account switching.
8. Add release-gating instrumentation and configuration checks.

## 11. Go / No-Go Recommendation

**No-Go.**

Blocking conditions:

- Confirmed conflicting and broken OAuth flow contracts.
- High-severity plaintext credential storage.
- High-severity local authentication/session weakness.
- High-severity global cleartext configuration.
- Release signing, client registration, and redirect registration remain Unknown.

Release can proceed only after the critical contract conflict is removed, the high findings are fixed, and a signed release build has passed end-to-end OAuth validation against the actual Google Cloud registrations.

# GitHub Issues

These are consolidated issue drafts. No GitHub issues were created.

## 1. Restore a single working Android Gmail OAuth flow

- **Severity:** Critical
- **Category:** Bug, Architecture
- **Suggested Labels:** `bug`, `oauth`, `android`, `severity:critical`
- **Impacted Files / Components:** `MainActivity.kt`, `main.py`, `server.py`, Android sync design
- **Evidence / Proof:** Kotlin references `/api/oauth/*`; the embedded server registers no matching routes; the design document says redirect is intentionally removed.
- **Expected vs Actual:** One complete Android flow vs conflicting direct, stale server, and desktop-only behavior.
- **Root Cause:** Dead server-loopback OAuth contract coexists with direct AppAuth.
- **Recommendation:** Standardize on hardened native AppAuth or fully implement/test the server contract.
- **Acceptance Criteria:** First login, re-auth, cancellation, and callback complete in a signed Android build.

## 2. Remove plaintext OAuth credential persistence from the Android app

- **Severity:** High
- **Category:** Security
- **Suggested Labels:** `security`, `oauth`, `android`, `severity:high`
- **Impacted Files / Components:** `OAuthTokenManager.kt`, `MainActivity.kt`, `auth_gmail.py`, backup rules
- **Evidence / Proof:** Raw JSON stores access token, refresh token, and client secret.
- **Expected vs Actual:** Keystore-protected public-client tokens vs plaintext shared files.
- **Root Cause:** File-based Kotlin/Python credential sharing.
- **Recommendation:** Native token broker, Android client registration, no client secret, protected storage.
- **Acceptance Criteria:** No secret in APK or token file; all credential material protected and excluded from backup.

## 3. Establish release-safe Android OAuth client, signing, and redirect configuration

- **Severity:** High
- **Category:** Configuration
- **Suggested Labels:** `configuration`, `oauth`, `android`, `severity:high`
- **Impacted Files / Components:** `build.gradle.kts`, `AndroidManifest.xml`, `Constants.kt`, OAuth assets
- **Evidence / Proof:** Dev application ID suffix conflicts with hardcoded production callback; real registrations/signing are unavailable.
- **Expected vs Actual:** Variant-specific registered package/signature/redirect vs unproven desktop-style configuration.
- **Root Cause:** No release configuration contract.
- **Recommendation:** Document and register separate Android clients and signing fingerprints per variant.
- **Acceptance Criteria:** Signed dev/release redirect tests pass and registration evidence is secured in release documentation.

## 4. Replace default global FastAPI PIN authentication with a native session boundary

- **Severity:** High
- **Category:** Security
- **Suggested Labels:** `security`, `android`, `architecture`, `severity:high`
- **Impacted Files / Components:** `server.py`, WebView auth scripts, `MainActivity.kt`
- **Evidence / Proof:** Default `PIN=1234`; one global `_authenticated` set controls all protected routes.
- **Expected vs Actual:** Native-bound, per-session authorization vs default PIN and global process state.
- **Root Cause:** Embedded API owns an insecure second authentication model.
- **Recommendation:** Native-created per-launch capability token with explicit logout and rate limiting.
- **Acceptance Criteria:** No default PIN or process-global session; unauthorized local requests fail consistently.

## 5. Restrict cleartext traffic to the minimum required local surface

- **Severity:** High
- **Category:** Configuration, Security
- **Suggested Labels:** `security`, `configuration`, `android`, `severity:high`
- **Impacted Files / Components:** `AndroidManifest.xml`, `network_security_config.xml`
- **Evidence / Proof:** Global `usesCleartextTraffic=true`; restrictive XML is not referenced.
- **Expected vs Actual:** Deny-by-default cleartext vs global permission.
- **Root Cause:** Incomplete network security configuration.
- **Recommendation:** Attach restrictive configuration and disable global cleartext.
- **Acceptance Criteria:** Release manifest denies non-loopback HTTP and OAuth remains functional.

## 6. Enforce OAuth state binding and prove PKCE for the direct callback

- **Severity:** High
- **Category:** Security
- **Suggested Labels:** `security`, `oauth`, `android`, `severity:high`
- **Impacted Files / Components:** `MainActivity.kt`, `OAuthCallbackActivity.kt`
- **Evidence / Proof:** Exported callback immediately exchanges parsed intent; no persisted expected state or explicit verifier setup.
- **Expected vs Actual:** Bound, validated callback vs unproven request correlation.
- **Root Cause:** Direct AppAuth flow lacks application-level correlation controls.
- **Recommendation:** Persist pending state securely, validate before exchange, and add adversarial callback tests.
- **Acceptance Criteria:** Forged/mismatched callbacks fail; tests prove PKCE/state behavior.

## 7. Implement complete Gmail disconnect and account switching

- **Severity:** Medium
- **Category:** Reliability, Security
- **Suggested Labels:** `oauth`, `android`, `bug`, `severity:medium`
- **Impacted Files / Components:** `SettingsActivity.kt`, `OAuthTokenManager.kt`, `server.py`
- **Evidence / Proof:** Disconnect deletes only `token.json`; no revocation, pending cleanup, or API logout.
- **Expected vs Actual:** Full session/consent cleanup vs local file deletion.
- **Root Cause:** Token deletion is treated as logout.
- **Recommendation:** Add revocation, artifact cleanup, server-session invalidation, and explicit account selection.
- **Acceptance Criteria:** Reconnect can select another account and logout blocks all local API access.

## 8. Make OAuth exchange lifecycle-safe and bounded

- **Severity:** Medium
- **Category:** Reliability
- **Suggested Labels:** `oauth`, `android`, `bug`, `severity:medium`
- **Impacted Files / Components:** `OAuthCallbackActivity.kt`, `OAuthTokenManager.kt`
- **Evidence / Proof:** Unbounded latches, `singleTask` callback without `onNewIntent`, non-durable in-flight state.
- **Expected vs Actual:** Cancellable lifecycle-aware exchange vs potential stranded flow.
- **Root Cause:** Blocking callback adaptation without lifecycle persistence.
- **Recommendation:** Use cancellable suspend APIs with timeout and durable pending state.
- **Acceptance Criteria:** Rotation, timeout, duplicate callback, and process-death tests pass.

## 9. Replace fixture-only OAuth tests with release-gating workflow coverage

- **Severity:** Medium
- **Category:** Testing
- **Suggested Labels:** `testing`, `oauth`, `android`, `severity:medium`
- **Impacted Files / Components:** `OAuthIntegrationTest.kt`, Android test suite, CI configuration
- **Evidence / Proof:** Tests assert JSON fields rather than OAuth routes, callback validity, registration, or protected storage.
- **Expected vs Actual:** End-to-end contract coverage vs file-shape tests.
- **Root Cause:** Tests are disconnected from the shipped architecture.
- **Recommendation:** Add mock-provider and device tests for every auth-critical scenario.
- **Acceptance Criteria:** CI detects missing OAuth routes, redirect mismatch, callback forgery, and plaintext storage regressions.

## 10. Correct security and OAuth documentation drift

- **Severity:** Medium
- **Category:** Maintainability, Architecture
- **Suggested Labels:** `documentation`, `security`, `oauth`, `severity:medium`
- **Impacted Files / Components:** `android/README.md`, root `README.md`, OAuth design specification
- **Evidence / Proof:** Documentation claims secure token storage/native PIN while code uses plaintext tokens and skips native PIN; docs describe desktop OAuth while Android retains direct AppAuth.
- **Expected vs Actual:** One documented release architecture vs contradictory operating models.
- **Root Cause:** Security documentation was not maintained with implementation changes.
- **Recommendation:** Update documentation after architecture consolidation; include release-config evidence and test procedure.
- **Acceptance Criteria:** Documentation, source, and release runbook describe the same Android OAuth workflow.
