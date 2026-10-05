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
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
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
    // ALFA Assistant: official Anthropic SDK (Claude)
    implementation("com.anthropic:anthropic-java:2.34.0")
}
