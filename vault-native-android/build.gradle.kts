plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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

fun isExecutableOnPath(name: String, path: String): File? =
    path.split(File.pathSeparator).filter { it.isNotBlank() }.firstNotNullOfOrNull { dir ->
        File(dir, name).takeIf { it.canExecute() }
    }

fun candidateCargoDirs(): Sequence<File> = sequenceOf(
    System.getenv("CARGO_HOME")?.let { file("$it/bin") },
    System.getenv("HOME")?.let { file("$it/.cargo/bin") },
    file("${System.getProperty("user.home")}/.cargo/bin"),
    file("/root/.cargo/bin"),
    file("/home/vagrant/.cargo/bin"),
    file("/home/fdroid/.cargo/bin"),
    file("/usr/local/cargo/bin"),
).filterNotNull().distinct()

fun Exec.ensureCargoOnPath() {
    doFirst {
        val pathEnv = System.getenv("PATH").orEmpty()
        val cargoFile = isExecutableOnPath("cargo", pathEnv)
            ?: candidateCargoDirs().firstNotNullOfOrNull { dir -> File(dir, "cargo").takeIf { it.canExecute() } }
            ?: throw GradleException(
                "cargo not found. HOME=${System.getenv("HOME") ?: ""}, " +
                    "user.home=${System.getProperty("user.home")}, " +
                    "user.name=${System.getProperty("user.name")}, " +
                    "CARGO_HOME=${System.getenv("CARGO_HOME") ?: ""}. " +
                    "Probed: " +
                    (candidateCargoDirs().map { it.absolutePath } + "PATH=$pathEnv").joinToString(", ") +
                    ". Install Rustup and retry."
            )
        logger.lifecycle("using cargo at ${cargoFile.absolutePath}")
        setCommandLine(listOf(cargoFile.absolutePath) + (commandLine as List<Any>).drop(1))
        environment("PATH", "${cargoFile.parent}${File.pathSeparator}$pathEnv")
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

val hostLibDir = vaultTargetDir.resolve("$hostTarget/debug")
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
    commandLine("cargo", "build", "-p", "vault-native", "--target", hostTarget)
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
        val clangxx = toolchainDir.resolve("bin/aarch64-linux-android26-clang++").absolutePath
        val ar = toolchainDir.resolve("bin/llvm-ar").absolutePath
        val ranlib = toolchainDir.resolve("bin/llvm-ranlib").absolutePath
        val sysroot = toolchainDir.resolve("sysroot").absolutePath
        environment("CC_aarch64_linux_android", clang)
        environment("CXX_aarch64_linux_android", clangxx)
        environment("AR_aarch64_linux_android", ar)
        environment("RANLIB_aarch64_linux_android", ranlib)
        environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", clang)
        environment("BINDGEN_EXTRA_CLANG_ARGS", "--sysroot=$sysroot --target=aarch64-linux-android26")
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
