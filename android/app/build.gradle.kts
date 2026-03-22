plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.fintrack.pk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fintrack.pk"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AppAuth redirect scheme for OAuth
        manifestPlaceholders["appAuthRedirectScheme"] = "com.fintrack.pk"

        ndk {
            // Chaquopy supports these ABIs
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
