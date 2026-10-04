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
        versionCode = 3
        versionName = "0.1.1-beta.2"
    }

    val signingValues = listOf("NEXUS_MOBILE_KEYSTORE", "NEXUS_MOBILE_STORE_PASSWORD",
        "NEXUS_MOBILE_KEY_ALIAS", "NEXUS_MOBILE_KEY_PASSWORD").map { System.getenv(it).orEmpty() }
    require(signingValues.all { it.isEmpty() } || signingValues.all { it.isNotEmpty() }) {
        "Release signing requires all four NEXUS_MOBILE signing environment variables."
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
