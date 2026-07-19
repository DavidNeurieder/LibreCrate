plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.shadow)
}

application {
    mainClass = "com.librecrate.app.cli.CliAppKt"
}

tasks {
    withType(com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class) {
        archiveBaseName.set("librecrate")
        archiveClassifier.set("")
        archiveVersion.set("")
        mergeServiceFiles()
    }
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
}
