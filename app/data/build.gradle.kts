plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "owntally.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

// Android needs Tink's Android artifact (it has the Keystore integration). The JVM artifact that app/crypto uses for its own
// tests has the same classes, so it is removed from this module's classpath instead of being on it twice.
configurations.configureEach {
    exclude(group = "com.google.crypto.tink", module = "tink")
}

dependencies {
    implementation(project(":app:core-model"))
    implementation(project(":app:crypto"))
    implementation("com.google.crypto.tink:tink-android:1.24.0")

    testImplementation(kotlin("test"))

    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
