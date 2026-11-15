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

rootProject.name = "LibreCrate"

val pinnedGradleVersion = "8.7"
if (gradle.gradleVersion != pinnedGradleVersion) {
    logger.warn(
        "LibreCrate builds must use Gradle $pinnedGradleVersion but ${gradle.gradleVersion} is running. " +
            "Invoke ./gradlew instead of a system Gradle; the Gradle version is embedded in " +
            "kotlin-tooling-metadata.json and breaks reproducible builds."
    )
}

include(":app")
include(":vault-reader")
include(":reader-epub")
include(":vault-native-android")
