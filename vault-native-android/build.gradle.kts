plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "uniffi.vault_native"
    compileSdk = 36

    ndkVersion = "28.2.13676358"

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
        disable += listOf("ChromeOsAbiSupport", "UseTomlInstead", "NewApi")
    }

    sourceSets {
        getByName("main") {
            java.srcDir("build/generated/java")
            kotlin.srcDir("build/generated/java")
        }
    }
}

dependencies {
    api("net.java.dev.jna:jna:5.14.0@aar")
}

// ---------------------------------------------------------------------------
// Rust native library build — runs automatically on every Gradle build
// ---------------------------------------------------------------------------

/** Ensure ~/.cargo/bin is on PATH so `cargo` is found even if Gradle daemon
 *  doesn't inherit the shell's PATH (e.g. on F-Droid CI). */
fun Exec.ensureCargoOnPath() {
    doFirst {
        val cargoDir = file("${System.getProperty("user.home")}/.cargo/bin")
        if (cargoDir.exists()) {
            environment("PATH", "${cargoDir.absolutePath}:${System.getenv("PATH") ?: ""}")
        }
    }
}

val vaultProjectDir = rootProject.projectDir.resolve("vault-native")
val vaultTargetDir = vaultProjectDir.resolve("target")

val rustSource: FileTree = fileTree(vaultProjectDir) {
    include("**/*.rs", "**/Cargo.toml", "**/Cargo.lock")
    exclude("target/**")
}

val hostTarget: String by lazy {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    val arch = when {
        osArch == "amd64" || osArch == "x86_64" -> "x86_64"
        osArch == "aarch64" || osArch == "arm64" -> "aarch64"
        else -> error("Unsupported host CPU architecture: $osArch. Run scripts/build_native.sh manually.")
    }
    val vendor = when {
        osName.contains("linux") -> "unknown-linux-gnu"
        osName.contains("mac") || osName.contains("darwin") -> "apple-darwin"
        else -> error("Unsupported host OS: $osName. Run scripts/build_native.sh manually.")
    }
    "$arch-$vendor"
}

val isMac = System.getProperty("os.name").lowercase().contains("mac")

val hostLibDir = vaultTargetDir.resolve("$hostTarget/release")
val hostLibFile = hostLibDir.resolve(if (isMac) "libvault_native.dylib" else "libvault_native.so")

val androidTarget = "aarch64-linux-android"
val androidLibDir = vaultTargetDir.resolve("$androidTarget/release")
val androidLibFile = androidLibDir.resolve("libvault_native.so")

val jniLibDir = project.projectDir.resolve("src/main/jniLibs/arm64-v8a")
val jniLibFile = jniLibDir.resolve("libvault_native.so")

val generatedBindingsDir = layout.buildDirectory.dir("generated/java").get().asFile

// --- Build Rust library for host (contains UniFFI metadata for bindings) ---
val buildHostRustLib by tasks.registering(Exec::class) {
    description = "Build Rust library for host platform"
    workingDir = vaultProjectDir
    commandLine("cargo", "build", "-p", "vault-native", "--target", hostTarget, "--release")
    inputs.files(rustSource)
    outputs.file(hostLibFile)
    ensureCargoOnPath()
}

// --- Generate Kotlin bindings from the host .so ---
val generateKotlinBindings by tasks.registering(Exec::class) {
    description = "Generate Kotlin UniFFI bindings from Rust"
    dependsOn(buildHostRustLib)
    workingDir = vaultProjectDir
    commandLine(
        "cargo", "run", "-p", "vault-native", "--example", "gen_kotlin", "--",
        hostLibFile.absolutePath,
        generatedBindingsDir.absolutePath
    )
    inputs.file(hostLibFile)
    outputs.dir(generatedBindingsDir)
    ensureCargoOnPath()
}

// --- Build Rust library for Android ---
val buildAndroidRustLib by tasks.registering(Exec::class) {
    description = "Build Rust library for Android (arm64-v8a)"
    dependsOn(generateKotlinBindings)
    workingDir = vaultProjectDir
    commandLine("cargo", "build", "-p", "vault-native", "--target", androidTarget, "--release")
    inputs.files(rustSource)
    outputs.file(androidLibFile)
    ensureCargoOnPath()

    doFirst {
        val ndkDir = sequenceOf(
            android.ndkDirectory.takeIf { it.exists() },
            System.getenv("ANDROID_NDK_HOME")?.let { file(it).takeIf { it.exists() } },
            let {
                val ndkParent = file("${System.getProperty("user.home")}/Android/Sdk/ndk")
                if (ndkParent.isDirectory) ndkParent.listFiles()?.maxOrNull() else null
            }?.takeIf { it.exists() },
        ).firstOrNull() ?: throw GradleException(
            "NDK not found. Set ANDROID_NDK_HOME or configure ndkVersion in build.gradle.kts."
        )
        val toolchainDir = ndkDir.resolve("toolchains/llvm/prebuilt/linux-x86_64")
        if (!toolchainDir.exists()) {
            toolchainDir.resolve("darwin-x86_64").takeIf { it.exists() }?.let { return@doFirst }
            toolchainDir.resolve("darwin-aarch64").takeIf { it.exists() }?.let { return@doFirst }
            throw GradleException("NDK toolchain not found at $toolchainDir. Install NDK r28c.")
        }
        val clang = toolchainDir.resolve("bin/aarch64-linux-android26-clang").absolutePath
        val ar = toolchainDir.resolve("bin/llvm-ar").absolutePath
        environment("CC_aarch64_linux_android", clang)
        environment("AR_aarch64_linux_android", ar)
        environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", clang)
    }
}

// --- Copy .so to jniLibs so it gets packaged into the APK ---
val copyJniLib by tasks.registering(Copy::class) {
    description = "Copy Rust .so to jniLibs"
    dependsOn(buildAndroidRustLib)
    from(androidLibFile)
    into(jniLibDir)
    inputs.file(androidLibFile)
    outputs.file(jniLibFile)
}

// Wire into build pipeline — bindings before compile, .so before JNI merge
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(generateKotlinBindings)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(copyJniLib)
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
