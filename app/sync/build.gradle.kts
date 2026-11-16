plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":app:core-model"))
    implementation(project(":app:crypto"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // The WebSocket transport for the relay (decision W1, 8 Nov 2026): OkHttp 4.12, the last 4.x release. OkHttp 5 asks for compile SDK 37, which the
    // Android modules (AGP 8.9.2) cannot use; Dependabot's proposal of 5.x is held back (.github/dependabot.yml).
    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.1")
}

tasks.test {
    useJUnitPlatform()
}
