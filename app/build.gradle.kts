plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreFile: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.alfa.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alfa.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "49").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.49"
    }

    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // the bundled llama-server must be extracted to nativeLibraryDir so it can be executed
        jniLibs { useLegacyPackaging = true }
        resources {
            // duplicate metadata from the Anthropic SDK's Jackson / OkHttp dependencies
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/INDEX.LIST",
                "META-INF/FastDoubleParser-*", "META-INF/io.netty.versions.properties",
            )
        }
    }
}

dependencies {
    // QR code encoding (pure Java, no Android deps)
    implementation("com.google.zxing:core:3.5.3")
    // NoCap Cloud: pairing QR scanner (camera permission is asked for only when you tap Scan QR)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    // NoCap Cloud: device token + pinned cert in Keystore-backed encrypted prefs
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // NoCap Cloud: 15-minute heartbeat
    implementation("androidx.work:work-runtime:2.9.1")
    // NoCap Cloud: open server apps in a Custom Tab
    implementation("androidx.browser:browser:1.8.0")
}
