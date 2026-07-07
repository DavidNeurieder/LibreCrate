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

rootProject.name = "DocWallet"
include(":app")
include(":vault-core")
include(":vault-reader")
include(":reader-pdf")
include(":reader-epub")
include(":vault-cli")
