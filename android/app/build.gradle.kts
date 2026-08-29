plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.branchdam.mobile"
    compileSdk = 35

    val appVersionName = System.getenv("APP_VERSION_NAME")?.removePrefix("v") ?: "0.1.0"
    val appVersionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull() ?: run {
        val parts = appVersionName.split(".")
        if (parts.size >= 3) {
            (parts[0].toIntOrNull() ?: 0) * 10000 + (parts[1].toIntOrNull() ?: 0) * 100 + (parts[2].substringBefore("-").toIntOrNull() ?: 0)
        } else {
            1
        }
    }

    defaultConfig {
        applicationId = "com.branchdam.mobile"
        minSdk = 28
        targetSdk = 35
        versionCode = appVersionCode.coerceAtLeast(1)
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE")
            if (!keystorePath.isNullOrEmpty()) {
                val kFile = file(keystorePath)
                require(kFile.exists()) { "KEYSTORE_FILE does not exist at: $keystorePath" }
                val storePass = System.getenv("KEYSTORE_PASSWORD")
                val kAlias = System.getenv("KEY_ALIAS")
                val kPass = System.getenv("KEY_PASSWORD")
                require(!storePass.isNullOrEmpty()) { "KEYSTORE_PASSWORD must be provided when KEYSTORE_FILE is set" }
                require(!kAlias.isNullOrEmpty()) { "KEY_ALIAS must be provided when KEYSTORE_FILE is set" }
                require(!kPass.isNullOrEmpty()) { "KEY_PASSWORD must be provided when KEYSTORE_FILE is set" }
                storeFile = kFile
                storePassword = storePass
                keyAlias = kAlias
                keyPassword = kPass
            } else {
                val debugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
                if (debugKeystore.exists()) {
                    storeFile = debugKeystore
                    storePassword = "android" // pragma: allowlist secret
                    keyAlias = "androiddebugkey"
                    keyPassword = "android" // pragma: allowlist secret
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    testImplementation("junit:junit:4.13.2")
}
