plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // The code lives in the owntally.* packages, like every other module: `in` is a Kotlin keyword, so the
    // application id (below) cannot also be the package name.
    namespace = "owntally.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "in.ownspace.owntally"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // The rule tests read these files directly. Declaring them makes Gradle re-run the tests when one changes.
        unitTests.all { test ->
            test.inputs.dir("src/main")
            test.inputs.dir("../ui/src/main")
            test.inputs.dir("../data/src/main")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// One copy of Tink in the APK: the Android artifact (see app/data/build.gradle.kts).
configurations.configureEach {
    exclude(group = "com.google.crypto.tink", module = "tink")
}

dependencies {
    implementation(project(":app:ui"))
    implementation(project(":app:data"))
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.activity:activity-compose:1.11.0")

    testImplementation(kotlin("test"))
}
