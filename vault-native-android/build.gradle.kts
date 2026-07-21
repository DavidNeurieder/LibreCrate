plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "uniffi.vault_native"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        disable += listOf("ChromeOsAbiSupport", "UseTomlInstead")
    }
}

dependencies {
    api("net.java.dev.jna:jna:5.14.0@aar")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.librecrate"
            artifactId = "vault-native-android"
            version = "0.1.0"

            afterEvaluate {
                from(components["release"])
            }
        }
    }

    repositories {
        mavenLocal()

        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/davidneurieder/LibreCrate")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
