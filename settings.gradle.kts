pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GG-retro-android"

// Pure Kotlin/JVM protocol library, also buildable on its own: ./gradlew -p gg-protocol test
includeBuild("gg-protocol")
include(":app")
