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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "in.ownspace.owntally"
        minSdk = 30
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
            test.inputs.files("../../build.gradle.kts", "../../gradle/libs.versions.toml", "../core-model/build.gradle.kts", "../crypto/build.gradle.kts", "../sync/build.gradle.kts", "../data/build.gradle.kts", "../ui/build.gradle.kts", "build.gradle.kts")
            test.systemProperty("user.language", "en")
            test.systemProperty("user.country", "IN")
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
    implementation(project(":app:core-model"))
    implementation(project(":app:ui"))
    implementation("androidx.biometric:biometric:1.1.0")
    implementation(project(":app:data"))
    implementation(project(":app:crypto"))
    implementation(project(":app:sync"))
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.activity:activity-compose:1.11.0")

    testImplementation(kotlin("test"))

    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
