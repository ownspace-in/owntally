plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":app:core-model"))
    // Pinned. Released 16 Oct 2026. All cryptography comes from here or from the JDK (docs/CRYPTO.md).
    implementation("com.google.crypto.tink:tink:1.24.0")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-property:5.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

tasks.test {
    useJUnitPlatform()
}
