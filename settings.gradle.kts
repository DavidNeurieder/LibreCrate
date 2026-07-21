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
        maven { url = uri("https://maven.ghostscript.com") }
    }
}

rootProject.name = "LibreCrate"
include(":app")
include(":vault-reader")
include(":reader-epub")
include(":vault-native-android")
