plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "owntally.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // A fixed locale (English, India) so an amount in a test does not change with the machine. Tests of other locales say which they use.
        unitTests.all { test ->
            test.systemProperty("user.language", "en")
            test.systemProperty("user.country", "IN")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// One copy of Tink: the Android artifact (see app/data/build.gradle.kts). The crypto module brings the JVM one, which has the same classes.
configurations.configureEach {
    exclude(group = "com.google.crypto.tink", module = "tink")
}

dependencies {
    implementation(project(":app:core-model"))
    implementation(project(":app:crypto"))
    implementation(project(":app:data"))
    implementation(project(":app:sync"))
    implementation(libs.tink.android)
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation(kotlin("test"))
    // Only to prove in a test that the QR codes we draw can be read by an independent reader (Apache-2.0, not shipped).
    testImplementation("com.google.zxing:core:3.5.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
