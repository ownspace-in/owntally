rootProject.name = "owntally"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":app:core-model")
include(":app:crypto")
include(":app:sync")
