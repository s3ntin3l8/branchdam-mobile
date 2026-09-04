plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.branchdam.mobile"
    compileSdk = 35

    val appVersionName = System.getenv("APP_VERSION_NAME")?.removePrefix("v") ?: run {
        val manifestFile = rootProject.file("../.release-please-manifest.json")
        if (manifestFile.exists()) {
            val json = manifestFile.readText()
            val match = Regex("\"\\.\":\\s*\"([^\"]+)\"").find(json)
            match?.groupValues?.get(1) ?: "0.2.0"
        } else {
            "0.2.0"
        }
    }
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

        // ABIs packed into the gomobile-built branchdam.aar. gomobile defaults
        // to all four; we make this explicit so dropping a target later is
        // intentional. Sub-issue A wires the AAR; sub-issue D removes the
        // NativeBridge.kt loadLibrary stub.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    // gomobile's AAR bundles a per-ABI JNI shim. Pick them up from app/libs.
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
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

    // F plan: unit tests for Android framework-touching code
    // (BootReceiver, MediaStoreObserver) run on the JVM without
    // Robolectric. `isReturnDefaultValues = true` makes the test
    // JVM return default values (null, 0, false) for Android
    // framework method calls instead of throwing RuntimeException.
    //
    // Trade-off: this turns every android.jar method into a silent
    // default-returning stub, which can mask real behavior (e.g.
    // WorkManager.getInstance chains, ContentResolver paths) and
    // give false confidence. The lifecycle/onChange paths that
    // depend on real framework behavior are covered by
    // instrumentation tests in androidTest/. The structural tests
    // here verify the API surface and contract, not the full
    // lifecycle.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.window:window:1.3.0")

    // branchdam Go engine, gomobile-bound. The .aar is produced by
    // `make mobile-build-android` (scripts/build-mobile.sh) and dropped into
    // app/libs/. File-tree dep is conditional so the project still builds
    // when the AAR hasn't been produced yet (e.g. unit-test runs on CI without
    // ANDROID_NDK_HOME). Sub-issue D will switch the consumer to the
    // gomobile-bound Engine API; until then, NativeBridge.kt's
    // loadLibrary("branchdamcore") stub is the runtime path.
    val branchdamAar = file("libs/branchdam.aar")
    if (branchdamAar.exists()) {
        implementation(files(branchdamAar))
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}
