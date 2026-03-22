# MainActivity Integration Tests

## Overview

This directory contains integration tests for the MainActivity lifecycle management. These tests verify that the app correctly handles initialization, background/foreground transitions, and state restoration.

## Test Coverage

### MainActivityTest.kt

Integration tests covering MainActivity lifecycle behavior:

1. **testAppInitializationSequence**
   - Verifies Python runtime extraction check on first launch
   - Verifies PIN authentication is required
   - Verifies loading screen shows appropriate messages
   - **Requirements**: 12.1

2. **testBackgroundForegroundTransitions**
   - Verifies server continues running when app goes to background
   - Verifies state is saved in onPause()
   - Verifies state is restored in onResume()
   - **Requirements**: 12.1, 12.2

3. **testPinReauthenticationAfterTimeout**
   - Verifies PIN re-authentication is required after 5 minutes in background
   - Verifies timestamp tracking works correctly
   - **Requirements**: 12.2

4. **testStateRestorationAfterProcessDeath**
   - Verifies state is saved in onSaveInstanceState()
   - Verifies state is restored in onCreate()
   - Verifies server restart is triggered if it was running
   - **Requirements**: 12.3, 12.4

5. **testLowMemoryHandling**
   - Verifies app saves state when low memory warning is received
   - Verifies critical state is preserved
   - **Requirements**: 12.3

6. **testConfigurationChanges**
   - Verifies activity handles configuration changes without restart
   - Verifies server continues running during rotation
   - **Requirements**: 12.5

7. **testServerHealthCheckOnResume**
   - Verifies server health is checked when app resumes
   - Verifies server restart is attempted if not running
   - **Requirements**: 12.1

8. **testActivityLifecycleCleanup**
   - Verifies resources are cleaned up when activity is destroyed
   - Verifies server is stopped properly
   - **Requirements**: 12.1

## Running the Tests

### Prerequisites

1. **Android Device or Emulator**: Tests require a connected Android device or running emulator
2. **Minimum API Level**: API 24 (Android 7.0) or higher
3. **Gradle**: Ensure Gradle wrapper is properly configured

### Running All Tests

```bash
cd android
./gradlew connectedAndroidTest
```

### Running Specific Test Class

```bash
./gradlew connectedAndroidTest --tests "com.fintrack.pk.MainActivityTest"
```

### Running Specific Test Method

```bash
./gradlew connectedAndroidTest --tests "com.fintrack.pk.MainActivityTest.testAppInitializationSequence"
```

## Test Implementation Notes

### Mocking Strategy

These tests use a minimal mocking approach:

- **PIN Authentication**: Tests use a mock encrypted PIN to bypass actual Keystore operations
- **Python Runtime**: Tests mark runtime as already extracted to skip extraction
- **Server Process**: Tests verify lifecycle behavior without requiring actual server startup

### Test Isolation

Each test:
- Clears SharedPreferences before execution
- Sets up required mock data
- Cleans up after execution
- Runs independently without affecting other tests

### Limitations

Due to the integration nature of these tests:

1. **Server Startup**: Tests don't verify actual Python server startup (requires full environment)
2. **PIN Encryption**: Tests use mock encrypted PIN (actual Keystore operations require device)
3. **Activity Results**: Tests can't easily verify PinAuthenticationActivity launch results
4. **Network Monitoring**: Tests don't verify actual network state changes

These limitations are acceptable for integration tests focused on lifecycle behavior. Full end-to-end testing would require additional instrumentation.

## Test Maintenance

### When to Update Tests

Update tests when:
- MainActivity lifecycle methods change
- State save/restore logic is modified
- Background/foreground handling changes
- New lifecycle-related features are added

### Adding New Tests

When adding new tests:
1. Follow the existing naming convention: `test<FeatureName>`
2. Document the test purpose in a comment
3. Reference the relevant requirements
4. Use the helper methods for setup (e.g., `setupMockPin()`)
5. Clean up any test-specific state in tearDown()

## Troubleshooting

### Tests Fail to Run

**Issue**: `connectedAndroidTest` fails with "No connected devices"
**Solution**: Connect an Android device via USB or start an Android emulator

**Issue**: Tests fail with "Permission denied"
**Solution**: Ensure the app has necessary permissions on the test device

**Issue**: Tests timeout
**Solution**: Increase test timeout in build.gradle.kts:
```kotlin
android {
    defaultConfig {
        testInstrumentationRunnerArguments["timeout"] = "300000" // 5 minutes
    }
}
```

### Tests Fail Unexpectedly

**Issue**: Tests fail with "Activity not found"
**Solution**: Ensure the app is properly installed on the test device

**Issue**: Tests fail with "SharedPreferences not found"
**Solution**: Clear app data on the test device before running tests

**Issue**: Tests fail intermittently
**Solution**: Add appropriate delays (Thread.sleep) to allow async operations to complete

## CI/CD Integration

To run these tests in CI/CD pipelines:

1. **GitHub Actions Example**:
```yaml
- name: Run Integration Tests
  uses: reactivecircus/android-emulator-runner@v2
  with:
    api-level: 29
    script: ./gradlew connectedAndroidTest
```

2. **GitLab CI Example**:
```yaml
test:
  stage: test
  script:
    - ./gradlew connectedAndroidTest
  artifacts:
    reports:
      junit: app/build/outputs/androidTest-results/connected/*.xml
```

## Test Reports

After running tests, view the HTML report at:
```
android/app/build/reports/androidTests/connected/index.html
```

## References

- [Android Testing Documentation](https://developer.android.com/training/testing)
- [ActivityScenario Documentation](https://developer.android.com/reference/androidx/test/core/app/ActivityScenario)
- [Espresso Testing Framework](https://developer.android.com/training/testing/espresso)
