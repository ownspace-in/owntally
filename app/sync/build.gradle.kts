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
    // The WebSocket transport (M0 spike) needs OkHttp, but the app does not use it until M6. OkHttp 5 asks for compile SDK 37, which the
    // Android modules (AGP 8.9.2) cannot use yet, so it is kept off the app's classpath for now: compile-only here, a normal dependency
    // in the tests. M6 must decide how the app gets a WebSocket client (an OkHttp version that fits, or the newer SDK with the AGP 9 migration).
    compileOnly("com.squareup.okhttp3:okhttp:5.5.1")
    testImplementation("com.squareup.okhttp3:okhttp:5.5.1")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
}

tasks.test {
    useJUnitPlatform()
}
