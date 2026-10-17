plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-property:5.9.1")
}

tasks.test {
    useJUnitPlatform()
}
