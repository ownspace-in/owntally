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

    buildTypes {
        release {
            // Iroh's native library is about 14 MB for each architecture: a release build carries only the two that real phones use (the debug
            // build keeps them all so that the emulator, an x86_64 phone, can run it).
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    packaging {
        // JNA (which Iroh's bindings use) ships the native libraries of desktop systems inside its jar: none of them is for a phone.
        resources.excludes += listOf("darwin-*/**", "linux-*/**", "win32-*/**", "android-*/**", "freebsd-*/**", "sunos-*/**", "dragonwood-*/**")
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
            // ManifestRulesTest reads the merged manifest of the release build, which is what a phone gets.
            test.dependsOn("processReleaseManifest")
            test.systemProperty("user.language", "en")
            test.systemProperty("user.country", "IN")
            test.inputs.dir("src/main")
            test.inputs.dir("../../site") // InviteSiteTest reads the invite page
            test.inputs.dir("../../relay") // RelayConfigTest reads the relay's service files and runbooks
            test.inputs.dir("../ui/src/main")
            test.inputs.dir("../data/src/main")
            test.inputs.dir("../sync/src/main")
            test.inputs.dir("../crypto/src/main")
            test.inputs.dir("../core-model/src/main")
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
    // Nearby Connections (same-room sync, M6): Google Play services' client library for it. It adds only ACCESS_NETWORK_STATE to the manifest and no
    // reporting library (checked on 8 Nov 2026); NetworkUseGuardTest allows this one Play services library and no other.
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // Iroh (decision I1/I2, M7): direct phone-to-phone connections with our relay as the home relay. MIT or Apache-2.0; a Rust library, so about 14 MB
    // for each phone architecture (see the ABI filter above) and the vulnerability check cannot see inside it (docs/tasks/M7-internet-sync.md, T7.4).
    implementation("computer.iroh:iroh-android:1.1.0")
    // The background sync job (decision B1, M7): AndroidX WorkManager, Apache-2.0. It adds the permissions listed in ManifestRulesTest and runs only our worker.
    implementation("androidx.work:work-runtime:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.activity:activity-compose:1.11.0")

    testImplementation(kotlin("test"))

    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.work:work-testing:2.11.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
