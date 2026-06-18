pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Chaquopy Gradle plugin lives here, not on Plugin Portal
        maven("https://chaquo.com/maven")
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Chaquopy Python wheel repository
        maven("https://chaquo.com/maven")
    }
}

rootProject.name = "HermesAgent"
include(":app")