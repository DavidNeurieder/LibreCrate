plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass = "com.docwallet.cli.CliAppKt"
}

dependencies {
    implementation(project(":vault-core"))
    implementation(libs.clikt)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.bouncycastle)
}
