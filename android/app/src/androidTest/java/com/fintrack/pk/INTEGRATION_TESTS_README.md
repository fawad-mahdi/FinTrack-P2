# FinTrack Android Integration Tests

This directory contains comprehensive integration tests for the FinTrack Android app. These tests verify that all components work together correctly and that critical user paths function as expected.

## Test Structure

### 1. Component Integration Tests (`integration/`)

Tests for interactions between major components:

#### `ComponentIntegrationTest.kt`
- Server startup coordination with MainActivity
- Server restart and stop coordination
- Server crash callback integration
- Server status reporting
- Multiple start/stop cycles
- Server health monitoring
- Component cleanup

**Key Integration Points:**
- ServerProcessManager ↔ MainActivity
- Server lifecycle management
- Health check coordination

#### `OAuthIntegrationTest.kt`
- Token validation and expiry checking
- Token refresh flow integration
- Token storage in Python-compatible format
- Credentials loading and validation
- Token lifecycle management

**Key Integration Points:**
- OAuthTokenManager ↔ MainActivity
- Token validation and refresh
- Python backend compatibility

#### `StorageIntegrationTest.kt`
- Database backup with server coordination
- Database restore with server coordination
- Log rotation and export functionality
- File size reporting
- Concurrent storage operations

**Key Integration Points:**
- StorageManager ↔ ServerProcessManager
- Backup/restore coordination
- Server stop/start during operations

#### `PinSecurityIntegrationTest.kt`
- PIN encryption and decryption
- PIN setup and storage
- PIN validation flow
- Failed attempt tracking
- Lockout mechanism
- PIN change flow
- PIN reset and data clearing

**Key Integration Points:**
- PinEncryptionManager ↔ PinAuthenticationActivity
- PIN security with encryption
- Failed attempt handling

### 2. End-to-End Flow Tests (`e2e/`)

Tests for complete user flows:

#### `AppLaunchFlowTest.kt`
- First launch flow (runtime extraction + PIN setup + server start)
- Subsequent launch flow (PIN login + server start)
- Launch with incorrect PIN
- Launch with server failure
- Launch sequence timing
- Launch with retries
- State persistence
- Cleanup on failure

**Complete Flow:**
1. Python runtime extraction (first launch)
2. PIN authentication (setup or login)
3. Server initialization and startup
4. WebView loading

#### `BackgroundForegroundFlowTest.kt`
- Background/foreground within 5-minute threshold (no re-auth)
- Background/foreground exceeding 5-minute threshold (re-auth required)
- Server state preservation during transitions
- Server restart after background if not running
- Multiple background/foreground cycles
- PIN re-authentication timing boundary
- State persistence across transitions
- Rapid transitions

**Key Scenarios:**
- App goes to background < 5 minutes → No re-auth
- App goes to background > 5 minutes → PIN re-auth required
- Server health check on foreground

#### `ServerCrashRecoveryTest.kt`
- Server crash detection
- Server restart after crash
- Restart with exponential backoff
- Crash callback invocation
- Server status after crash
- Multiple crash/recovery cycles
- Recovery with health check
- Crash recovery timing
- State consistency after crash

**Recovery Flow:**
1. Detect server crash (health check failure)
2. Attempt restart with backoff (1s, 2s, 4s, 8s, max 10s)
3. Limit restart attempts (max 5 in 1 minute)
4. Invoke crash callback if exhausted

### 3. Regression Tests (`regression/`)

Tests to ensure critical functionality doesn't break:

#### `CriticalPathRegressionTest.kt`
- App launch and initialization
- PIN authentication (setup and login)
- Server startup and operation
- OAuth token management
- Database backup and restore
- Log rotation and export
- PIN change flow
- Server restart after crash
- Complete user flow
- Error handling and recovery

**Critical Paths Covered:**
- First launch → PIN setup → Server start → Ready
- Subsequent launch → PIN login → Server start → Ready
- OAuth flow → Token storage → Token refresh
- Backup → Restore → Verify
- Server crash → Detect → Restart → Recover

#### `DataPersistenceRegressionTest.kt`
- PIN persistence across instances
- OAuth token persistence
- Database file persistence
- Log file persistence
- SharedPreferences persistence
- App state persistence
- Persistence after multiple writes
- Persistence with large values
- Persistence across app updates
- Persistence with concurrent access
- Persistence after clear and recreate
- File persistence with rotation
- Database persistence with backup/restore

**Data Types Tested:**
- Encrypted PIN
- OAuth tokens
- Database files
- Log files
- SharedPreferences (String, Int, Boolean, Long, Float)
- App state

#### `ErrorHandlingRegressionTest.kt`
- Invalid PIN handling
- PIN decryption with corrupted data
- Server startup failure handling
- Database restore with invalid file
- OAuth token with missing file
- OAuth token with malformed JSON
- File I/O error handling
- Server crash recovery
- Failed attempt lockout handling
- Log rotation with I/O errors
- Backup with missing database
- Multiple concurrent errors
- Error recovery after cleanup
- Graceful degradation with missing components

**Error Scenarios:**
- Invalid/corrupted data
- Missing files
- I/O failures
- Server crashes
- Network errors
- Concurrent errors

## Running the Tests

### Run All Integration Tests
```bash
cd android
./gradlew connectedAndroidTest
```

### Run Specific Test Suite
```bash
# Component integration tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fintrack.pk.integration.ComponentIntegrationTest

# OAuth integration tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fintrack.pk.integration.OAuthIntegrationTest

# Storage integration tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fintrack.pk.integration.StorageIntegrationTest

# PIN security integration tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fintrack.pk.integration.PinSecurityIntegrationTest

# App launch flow tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fintrack.pk.e2e.AppLaunchFlowTest

# Background/foreground flow tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fintrack.pk.e2e.BackgroundForegroundFlowTest

# Server crash recovery tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fintrack.pk.e2e.ServerCrashRecoveryTest

# Critical path regression tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fintrack.pk.regression.CriticalPathRegressionTest

# Data persistence regression tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fintrack.pk.regression.DataPersistenceRegressionTest

# Error handling regression tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fintrack.pk.regression.ErrorHandlingRegressionTest
```

### Run Specific Test Method
```bash
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fintrack.pk.integration.ComponentIntegrationTest#testServerStartupCoordination
```

## Test Requirements

### Device/Emulator Requirements
- Android API 24 (Android 7.0) or higher
- Sufficient storage for database and log files
- Network connectivity (for OAuth tests)

### Test Environment Setup
1. Ensure Python runtime is available in assets
2. Ensure credentials.json is configured (for OAuth tests)
3. Clear app data before running tests for clean state
4. Disable animations for reliable UI tests

### Known Limitations
- Server startup may fail in some test environments (tests handle gracefully)
- OAuth token refresh requires valid credentials
- Some tests require network connectivity
- Tests may take several minutes to complete

## Test Coverage

### Component Integration
- ✅ ServerProcessManager lifecycle
- ✅ WebViewManager initialization
- ✅ OAuthTokenManager operations
- ✅ PinEncryptionManager security
- ✅ StorageManager operations

### End-to-End Flows
- ✅ Complete app launch sequence
- ✅ Background/foreground transitions
- ✅ Server crash detection and recovery
- ✅ PIN re-authentication flow

### Regression Coverage
- ✅ All critical user paths
- ✅ Data persistence across restarts
- ✅ Error handling and recovery
- ✅ Edge cases and boundary conditions

## Test Maintenance

### Adding New Tests
1. Identify the integration point or flow to test
2. Choose appropriate test category (integration/e2e/regression)
3. Follow existing test patterns and naming conventions
4. Add comprehensive assertions and logging
5. Update this README with new test information

### Test Best Practices
- Each test should be independent and idempotent
- Use `@Before` and `@After` for setup and cleanup
- Add descriptive test names and comments
- Use Logger for debugging information
- Handle test environment limitations gracefully
- Verify both success and failure scenarios

### Debugging Failed Tests
1. Check test logs for error messages
2. Verify device/emulator meets requirements
3. Ensure app data is cleared before tests
4. Check for timing issues (add delays if needed)
5. Verify test environment setup (credentials, network, etc.)

## Test Metrics

### Total Tests: 100+
- Component Integration: 30+ tests
- End-to-End Flows: 30+ tests
- Regression Tests: 40+ tests

### Estimated Runtime
- Component Integration: ~5-10 minutes
- End-to-End Flows: ~10-15 minutes
- Regression Tests: ~10-15 minutes
- **Total: ~25-40 minutes** (depending on device/emulator)

## Continuous Integration

These tests are designed to run in CI/CD pipelines:

```yaml
# Example GitHub Actions workflow
- name: Run Integration Tests
  run: ./gradlew connectedAndroidTest
  
- name: Upload Test Reports
  uses: actions/upload-artifact@v2
  with:
    name: test-reports
    path: app/build/reports/androidTests/
```

## Related Documentation

- [Project Structure](../../PROJECT_STRUCTURE.md)
- [Testing Strategy](../../docs/TESTING_STRATEGY.md)
- [Build Configuration](../../build.gradle.kts)
- [Main README](../../README.md)

## Support

For issues or questions about the integration tests:
1. Check test logs for detailed error messages
2. Review this README for test requirements
3. Check existing test implementations for examples
4. Consult the main project documentation
