import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The version, in one place (version.properties): Play needs VERSION_CODE to rise with every upload.
val versionProps = Properties().apply { file("version.properties").inputStream().use { load(it) } }

// The upload key (decision S1, Google Play App Signing) is the owner's, and it is never in the repository: each setting is read from the owner's own
// Gradle properties (~/.gradle/gradle.properties) or from the environment. Without them the release build is simply not signed (CI builds it that way).
fun uploadSetting(name: String): String? = (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() }
val uploadKey: String? = uploadSetting("OWNTALLY_UPLOAD_STORE_FILE")

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
        versionCode = versionProps.getProperty("VERSION_CODE").toInt()
        versionName = versionProps.getProperty("VERSION_NAME")
    }

    signingConfigs {
        if (uploadKey != null) {
            create("upload") {
                storeFile = file(uploadKey)
                storePassword = uploadSetting("OWNTALLY_UPLOAD_STORE_PASSWORD")
                keyAlias = uploadSetting("OWNTALLY_UPLOAD_KEY_ALIAS")
                keyPassword = uploadSetting("OWNTALLY_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Code and resource shrinking (M10, CRYPTO.md C8). The rules are in proguard-rules.pro; the whole emulator suite is run against the shrunk
            // build (the releaseTest build type below).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the owner's upload key when it is given (docs/RELEASE.md); otherwise left unsigned. Never the debug key.
            signingConfig = signingConfigs.findByName("upload")
            // Iroh's native library is about 14 MB for each architecture: a release build carries only the two that real phones use (the debug
            // build keeps them all so that the emulator, an x86_64 phone, can run it).
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        // The release build, signed with the debug key and with the emulator's architecture too, so that the whole emulator suite can run against the
        // shrunk code (./gradlew -PshrunkTests :app:app:installReleaseTest :app:app:installReleaseTestAndroidTest; docs/tasks/M10-hardening-release.md).
        // It is never shipped. The debug sources (the pretend members the tests use) are added to it for the same reason.
        create("releaseTest") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            proguardFile("proguard-releasetest-rules.pro")
            testProguardFiles("proguard-test-rules.pro")
            ndk.abiFilters.clear()
            ndk.abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    sourceSets.getByName("releaseTest").java.srcDir("src/debug/kotlin")

    // Run the instrumented tests against the shrunk build when asked.
    if (project.hasProperty("shrunkTests")) testBuildType = "releaseTest"

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
            test.inputs.files("../../build.gradle.kts", "../../gradle/libs.versions.toml", "../core-model/build.gradle.kts", "../crypto/build.gradle.kts", "../sync/build.gradle.kts", "../data/build.gradle.kts", "../ui/build.gradle.kts", "build.gradle.kts", "version.properties", "proguard-rules.pro", "proguard-releasetest-rules.pro")
            // ManifestRulesTest reads the merged manifest of the release build, which is what a phone gets.
            test.dependsOn("processReleaseManifest")
            test.systemProperty("user.language", "en")
            test.systemProperty("user.country", "IN")
            test.inputs.dir("src/main")
            test.inputs.file("../../.gitignore")
            test.inputs.file("../../docs/RELEASE.md")
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
    implementation("com.google.android.gms:play-services-nearby:19.5.1")
    // Iroh (decision I1/I2, M7): direct phone-to-phone connections with our relay as the home relay. MIT or Apache-2.0; a Rust library, so about 14 MB
    // for each phone architecture (see the ABI filter above) and the vulnerability check cannot see inside it (docs/tasks/M7-internet-sync.md, T7.4).
    implementation("computer.iroh:iroh-android:1.2.0")
    // The background sync job (decision B1, M7): AndroidX WorkManager, Apache-2.0. It adds the permissions listed in ManifestRulesTest and runs only our worker.
    implementation("androidx.work:work-runtime:2.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.activity:activity-compose:1.11.0")

    testImplementation(kotlin("test"))

    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.work:work-testing:2.12.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    add("releaseTestImplementation", "androidx.compose.ui:ui-test-manifest")
}
