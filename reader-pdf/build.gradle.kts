plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.librecrate.app.reader.pdf"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":vault-reader"))
    implementation(libs.mupdf.fitz)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)

    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
}
