plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// ─── OAuth client IDs (Android-type Google clients: no client secret) ───
// Configure in ~/.gradle/gradle.properties (NOT the repo one) or env vars:
//   FINTRACK_OAUTH_CLIENT_ID_RELEASE  (Android client for the Play App Signing SHA-1)
//   FINTRACK_OAUTH_CLIENT_ID_DEBUG    (Android client for the debug keystore SHA-1)
fun secretProperty(name: String): String =
    (project.findProperty(name) as String?) ?: System.getenv(name) ?: ""

// Google requires the reversed-client-ID custom scheme for Android clients.
fun oauthRedirectScheme(clientId: String): String =
    if (clientId.endsWith(".apps.googleusercontent.com"))
        "com.googleusercontent.apps." + clientId.removeSuffix(".apps.googleusercontent.com")
    else
        "com.fintrack.pk" // placeholder so builds work before the client ID is configured

val oauthClientIdRelease = secretProperty("FINTRACK_OAUTH_CLIENT_ID_RELEASE")
val oauthClientIdDebug = secretProperty("FINTRACK_OAUTH_CLIENT_ID_DEBUG")

// ─── Release signing (upload keystore lives OUTSIDE the repo) ───
// Configure in ~/.gradle/gradle.properties or env vars:
//   FINTRACK_KEYSTORE_PATH, FINTRACK_KEYSTORE_PASSWORD,
//   FINTRACK_KEY_ALIAS, FINTRACK_KEY_PASSWORD
val keystorePath = secretProperty("FINTRACK_KEYSTORE_PATH")

android {
    namespace = "com.fintrack.pk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fintrack.pk"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

<<<<<<< HEAD
        // AppAuth redirect scheme for OAuth (overridden per build type below)
        manifestPlaceholders["appAuthRedirectScheme"] = "com.fintrack.pk"
        buildConfigField("String", "OAUTH_CLIENT_ID", "\"\"")
=======
        // AppAuth redirect scheme for OAuth. The manifest callback filter and
        // Constants.OAUTH_REDIRECT_URI both derive from these, so the scheme
        // stays consistent per variant (see the dev build type override).
        manifestPlaceholders["appAuthRedirectScheme"] = "com.fintrack.pk"
        buildConfigField("String", "OAUTH_REDIRECT_SCHEME", "\"com.fintrack.pk\"")
>>>>>>> cf5955ab49e83f742b37cfff8091bf38565c16bf

        ndk {
            // Chaquopy supports these ABIs
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    signingConfigs {
        if (keystorePath.isNotEmpty()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = secretProperty("FINTRACK_KEYSTORE_PASSWORD")
                keyAlias = secretProperty("FINTRACK_KEY_ALIAS")
                keyPassword = secretProperty("FINTRACK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("String", "OAUTH_CLIENT_ID", "\"$oauthClientIdRelease\"")
            manifestPlaceholders["appAuthRedirectScheme"] = oauthRedirectScheme(oauthClientIdRelease)
        }
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "OAUTH_CLIENT_ID", "\"$oauthClientIdDebug\"")
            manifestPlaceholders["appAuthRedirectScheme"] = oauthRedirectScheme(oauthClientIdDebug)
        }
        create("dev") {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
<<<<<<< HEAD
            buildConfigField("String", "OAUTH_CLIENT_ID", "\"$oauthClientIdDebug\"")
            manifestPlaceholders["appAuthRedirectScheme"] = oauthRedirectScheme(oauthClientIdDebug)
=======
            manifestPlaceholders["appAuthRedirectScheme"] = "com.fintrack.pk.dev"
            buildConfigField("String", "OAUTH_REDIRECT_SCHEME", "\"com.fintrack.pk.dev\"")
>>>>>>> cf5955ab49e83f742b37cfff8091bf38565c16bf
            resValue("string", "app_name", "FinTrack Dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// Chaquopy Python configuration
chaquopy {
    defaultConfig {
        version = "3.9"
        pip {
            // Install Python dependencies
            // Note: Using versions compatible with Chaquopy (pre-built wheels available)
            // Install cryptography first with an older version that has pre-built wheels
            install("cryptography==3.4.8")  // Last version with pre-built wheels for all platforms
            install("fastapi==0.88.0")  // Older version compatible with pydantic 1.x
            install("uvicorn==0.20.0")
            install("sqlalchemy==1.4.46")  // 1.4.x series doesn't require greenlet with Rust
            install("google-api-python-client==2.70.0")  // Older version with compatible deps
            install("google-auth-httplib2==0.1.0")
            install("google-auth-oauthlib==0.8.0")
            install("python-multipart==0.0.5")
            install("pydantic==1.10.13")  // Pydantic 1.x doesn't require Rust
            install("python-dotenv==1.0.1")  // Pure-Python; server.py imports it at module load
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Material Design
    implementation("com.google.android.material:material:1.12.0")

    // Lifecycle components
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.2")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // WebView
    implementation("androidx.webkit:webkit:1.8.0")

    // SwipeRefreshLayout
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Preferences
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Security (Keystore)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Network monitoring
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // HTTP client for health checks
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // OAuth - AppAuth library for Gmail authentication
    implementation("net.openid:appauth:0.11.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
