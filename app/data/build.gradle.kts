plugins {
    id("com.android.library")
    kotlin("android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "owntally.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // A fixed locale (English, India) so an amount in a test does not change with the machine. Tests of other locales say which they use.
        unitTests.all { test ->
            test.systemProperty("user.language", "en")
            test.systemProperty("user.country", "IN")
            // The simulation (T5.4) reads how many seeds to run from the environment; Gradle must know, or it skips a rerun with other seeds.
            for (name in listOf("OWNTALLY_SIM_SEEDS", "OWNTALLY_SIM_FIRST", "OWNTALLY_SIM_SEED")) {
                test.inputs.property(name, providers.environmentVariable(name).orElse(""))
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    // The database schema is exported so a test can check that the code and the schema agree, and migrations can be tested.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Android needs Tink's Android artifact (it has the Keystore integration). The JVM artifact that app/crypto uses for its own
// tests has the same classes, so it is removed from this module's classpath instead of being on it twice.
configurations.configureEach {
    exclude(group = "com.google.crypto.tink", module = "tink")
}

dependencies {
    implementation(project(":app:core-model"))
    implementation(project(":app:crypto"))
    implementation(project(":app:sync"))
    implementation(libs.tink.android)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
}
