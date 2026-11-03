plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "owntally.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":app:core-model"))
    implementation(project(":app:crypto"))

    testImplementation(kotlin("test"))
}
