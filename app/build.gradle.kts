plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.docwallet"
    compileSdk = 36

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.docwallet"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        resourceConfigurations += setOf("en")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/assets/dexopt/*"
        }
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    testImplementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.splash.screen)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.sqlite.ktx)
    implementation(libs.documentfile)
    implementation(project(":vault-core"))
    implementation(project(":vault-reader"))
    implementation(project(":reader-pdf"))
    implementation(project(":reader-epub"))
    implementation(libs.commonmark)
    implementation(libs.zxing.core)
    implementation(libs.commons.compress)
    implementation(libs.junrar)
    implementation(libs.mupdf.fitz)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.work.runtime)
    implementation(libs.preference.ktx)
    implementation(libs.coil.core)
    implementation(libs.coil.compose)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.arch.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test)

    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}


