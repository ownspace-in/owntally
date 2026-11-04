rootProject.name = "owntally"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":app:core-model")
include(":app:crypto")
include(":app:sync")
include(":app:data")
include(":app:ui")
include(":app:app")
