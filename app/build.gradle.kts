plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

providers.gradleProperty("nexusMobileBuildDir").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let { layout.buildDirectory.set(file(it)) }

android {
    namespace = "com.nexus.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexus.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.2-beta.2"
        testInstrumentationRunner = "com.nexus.mobile.ReleaseSmokeInstrumentation"
    }

    // Opt-in emulator tests exercise the actual minified release, never a debug
    // substitute. Test APK/signing only; no test component enters the shipped APK.
    val releaseSmoke = providers.gradleProperty("nexusMobileReleaseSmoke").orNull == "true"
    if (releaseSmoke) testBuildType = "release"

    val signingValues = listOf("NEXUS_MOBILE_KEYSTORE", "NEXUS_MOBILE_STORE_PASSWORD",
        "NEXUS_MOBILE_KEY_ALIAS", "NEXUS_MOBILE_KEY_PASSWORD").map { System.getenv(it).orEmpty() }
    require(signingValues.all { it.isEmpty() } || signingValues.all { it.isNotEmpty() }) {
        "Release signing requires all four NEXUS_MOBILE signing environment variables."
    }
    require(!releaseSmoke || signingValues.all { it.isEmpty() }) {
        "Release smoke tests must not use production signing credentials."
    }
    if (signingValues.all { it.isNotEmpty() }) {
        signingConfigs.create("release") {
            storeFile = file(signingValues[0])
            storePassword = signingValues[1]
            keyAlias = signingValues[2]
            keyPassword = signingValues[3]
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            if (releaseSmoke) signingConfig = signingConfigs.getByName("debug")
            if (signingValues.all { it.isNotEmpty() }) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    // Bundled camera decoder: no Play services or first-use module download.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("io.github.webrtc-sdk:android:150.7871.01")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
