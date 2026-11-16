import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The NDK revision is pinned in gradle.properties so the app module and the
// Rust task always use the same toolchain. A different NDK changes codegen and
// the embedded compiler path, which breaks reproducible builds.
val pinnedNdkVersion: String = providers.gradleProperty("librecrate.ndkVersion").get()

val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties().apply {
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun localPropertyFile(key: String): File? {
    val value = localProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val candidate = File(value)
    return if (candidate.isAbsolute) candidate else localPropertiesFile.parentFile.resolve(value)
}

fun ndkRevision(dir: File): String? {
    val sourceProperties = dir.resolve("source.properties")
    if (!sourceProperties.isFile) return null
    return Properties().apply {
        sourceProperties.inputStream().use { load(it) }
    }.getProperty("Pkg.Revision")?.trim()?.takeIf { it.isNotEmpty() }
}

fun installedNdks(ndkParent: File?): List<File> =
    ndkParent?.takeIf { it.isDirectory }?.listFiles()
        ?.filter { it.isDirectory && ndkRevision(it) != null }
        ?.sortedByDescending { it.name }
        .orEmpty()

val sdkDirs = sequenceOf(
    localPropertyFile("sdk.dir"),
    System.getenv("ANDROID_HOME")?.let(::File),
    System.getenv("ANDROID_SDK_ROOT")?.let(::File),
    File(System.getProperty("user.home"), "Android/Sdk"),
    File("/opt/android-sdk"),
    File("/usr/local/lib/android/sdk"),
    File("/builds/fdroid/buildserver"),
).filterNotNull().filter { it.isDirectory }.distinct().toList()

val detectedNdkDir: File? =
    sequenceOf(
        localPropertyFile("ndk.dir"),
        System.getenv("ANDROID_NDK_HOME")?.let(::File),
        System.getenv("ANDROID_NDK_ROOT")?.let(::File),
        System.getenv("NDK_HOME")?.let(::File),
    ).filterNotNull().firstOrNull { it.isDirectory }
        ?: sdkDirs.asSequence()
            .flatMap { installedNdks(File(it, "ndk")).asSequence() }
            .firstOrNull()

// Only point AGP at a discovered NDK when it is exactly the pinned revision.
// Otherwise let AGP resolve `ndkVersion` itself so we fail with a clear message
// instead of silently building with a different toolchain.
val configuredNdkDir: File? = detectedNdkDir?.takeIf { ndkRevision(it) == pinnedNdkVersion }

logger.lifecycle(
    "Using Android NDK $pinnedNdkVersion" +
        (configuredNdkDir?.let { " (${it.absolutePath})" } ?: " (not installed yet)")
)

android {
    namespace = "uniffi.vault_native"
    compileSdk = 36

    configuredNdkDir?.let { ndkPath = it.absolutePath }
    ndkVersion = pinnedNdkVersion

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
    api("net.java.dev.jna:jna:5.17.0@aar")
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
val androidLibDir = File("/tmp/librecrate-cargo-target/$androidTarget/release")
val androidLibFile = androidLibDir.resolve("libvault_native.so")

val jniLibDir = project.projectDir.resolve("src/main/jniLibs/arm64-v8a")
val jniLibFile = jniLibDir.resolve("libvault_native.so")

val generatedBindingsDir = layout.buildDirectory.dir("generated/java").get().asFile

// Bump this whenever the registry patches below change. It is part of the Rust
// task inputs so existing OpenSSL build artifacts are invalidated.
val rustDepsPatchRevision = "4"

// Cargo does not track SOURCE_DATE_EPOCH, so the epoch is also recorded in this
// marker file to detect stale vendored-OpenSSL objects.
val androidBuildInputsMarker = androidLibDir.parentFile.parentFile.resolve(".librecrate-rust-build-inputs")

fun gitCommitEpoch(): Long? = runCatching {
    val process = ProcessBuilder(
        "git", "-C", rootProject.projectDir.absolutePath, "log", "-1", "--format=%ct"
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0) output.toLongOrNull() else null
}.getOrNull()

// F-Droid exports SOURCE_DATE_EPOCH from the build directory *before* it checks
// out the commit being built. Trusting that value makes the same commit produce
// different binaries depending on repo state, so derive the epoch from the
// checked-out commit instead. LIBRECRATE_SOURCE_DATE_EPOCH is an explicit
// override for reproducing older artifacts.
fun resolveSourceDateEpoch(): Pair<Long, String> {
    val override = System.getenv("LIBRECRATE_SOURCE_DATE_EPOCH")?.trim()?.takeIf { it.isNotEmpty() }
    if (override != null) {
        val value = override.toLongOrNull()
            ?: throw GradleException("LIBRECRATE_SOURCE_DATE_EPOCH must be a Unix timestamp, got '$override'")
        return value to "LIBRECRATE_SOURCE_DATE_EPOCH"
    }
    gitCommitEpoch()?.let { return it to "git commit timestamp" }
    val ambient = System.getenv("SOURCE_DATE_EPOCH")?.trim()?.toLongOrNull()
    if (ambient != null) {
        logger.lifecycle("Warning: git metadata unavailable; falling back to ambient SOURCE_DATE_EPOCH=$ambient")
        return ambient to "ambient SOURCE_DATE_EPOCH"
    }
    logger.lifecycle("Warning: no git metadata or SOURCE_DATE_EPOCH; using 0")
    return 0L to "fallback"
}

val (sourceDateEpoch, sourceDateEpochSource) = resolveSourceDateEpoch()
logger.lifecycle("SOURCE_DATE_EPOCH=$sourceDateEpoch ($sourceDateEpochSource)")

fun cargoLockVersion(crateName: String): String {
    val lockFile = vaultProjectDir.resolve("Cargo.lock")
    if (!lockFile.isFile) {
        throw GradleException("Missing ${lockFile.absolutePath}; run 'cargo fetch' first")
    }
    val pattern = Regex(
        "(?m)^\\[\\[package]]\\s*\\nname = \"${Regex.escape(crateName)}\"\\s*\\nversion = \"([^\"]+)\""
    )
    return pattern.find(lockFile.readText())?.groupValues?.get(1)
        ?: throw GradleException("$crateName not found in ${lockFile.absolutePath}")
}

fun registryCrateDir(registrySrcDirs: List<File>, crateName: String): File {
    val expected = "$crateName-${cargoLockVersion(crateName)}"
    val matches = registrySrcDirs
        .flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
        .filter { it.isDirectory && it.name == expected }
        .distinct()
        .toList()
    return when {
        matches.isEmpty() -> throw GradleException(
            "$expected not found in the cargo registry. Probed: " +
                registrySrcDirs.joinToString(", ") { it.absolutePath }
        )
        matches.size > 1 -> throw GradleException(
            "Multiple copies of $expected found: " + matches.joinToString(", ") { it.absolutePath }
        )
        else -> matches.single()
    }
}

// Patch the exact registry copy of openssl-src named in Cargo.lock. Registry
// crates are immutable, so this is verifiable: the patch below either applies
// to the expected text or the build fails loudly.
fun patchOpenSslSrc(opensslSrcDir: File) {
    val buildInfo = opensslSrcDir.resolve("openssl/crypto/build.info")
    val libRs = opensslSrcDir.resolve("src/lib.rs")

    val compilerOld =
        "GENERATE[buildinf.h]=../util/mkbuildinf.pl \"$(CC) $(LIB_CFLAGS) $(CPPFLAGS_Q)\" \"$(PLATFORM)\""
    val compilerNew =
        "# libre-reproducible: keep the NDK compiler path out of buildinf.h\n" +
            "GENERATE[buildinf.h]=../util/mkbuildinf.pl \"cc $(LIB_CFLAGS) $(CPPFLAGS_Q)\" \"$(PLATFORM)\""

    val prefixOld = """        } else {
            configure.arg(&format!("--prefix={}", install_dir.display()));
        }"""
    val prefixNew = """        } else {
            // libre-reproducible: stable compiled-in prefix; install via DESTDIR
            configure.arg("--prefix=/");
        }"""

    val installOld = """            let mut install = self.cmd_make()?;
            install.arg("install_dev").current_dir(&inner_dir);"""
    val installNew = """            let mut install = self.cmd_make()?;
            // libre-reproducible: install into OUT_DIR without embedding it in the binaries.
            // DESTDIR must be a command-line variable: OpenSSL's Makefile sets DESTDIR=.
            install.arg(format!("DESTDIR={}", install_dir.display())).arg("install_dev").current_dir(&inner_dir);"""

    // Remove an earlier intermediate patch that exported DESTDIR through the
    // environment, which OpenSSL's Makefile ignores.
    val legacyInstallEnv = "            install.env(\"DESTDIR\", &install_dir);\n"
    val legacyInstallComment =
        "            // libre-reproducible: install into OUT_DIR without embedding it in the binaries\n"
    val libRsText = libRs.readText()
    val cleanedLibRs = libRsText
        .replace(legacyInstallEnv, "")
        .replace(legacyInstallComment, "")
    if (cleanedLibRs != libRsText) {
        libRs.writeText(cleanedLibRs)
    }

    fun applyPatch(file: File, old: String, new: String, label: String) {
        val text = file.readText()
        if (text.contains("libre-reproducible") && !text.contains(old)) {
            logger.lifecycle("${file.name} already patched for $label")
            return
        }
        val patched = text.replace(old, new)
        if (patched == text) {
            throw GradleException(
                "Unexpected openssl-src content; cannot apply the $label patch. " +
                    "Crate dir: ${opensslSrcDir.absolutePath}, file: ${file.absolutePath}"
            )
        }
        file.writeText(patched)
        logger.lifecycle("Patched ${opensslSrcDir.name}/${file.name} for $label")
    }

    applyPatch(buildInfo, compilerOld, compilerNew, "a stable compiler string")
    applyPatch(libRs, prefixOld, prefixNew, "a stable install prefix")
    applyPatch(libRs, installOld, installNew, "a DESTDIR-based install")
}

// --- Build Rust library for host (contains UniFFI metadata for bindings) ---
val buildHostRustLib by tasks.registering(Exec::class) {
    description = "Build Rust library for host platform"
    workingDir = vaultProjectDir
    commandLine("cargo", "build", "-p", "vault-native", "--target", hostTarget)
    inputs.files(rustSource)
    outputs.file(hostLibFile)
    ensureCargoOnPath()
}

// --- Fetch crates.io dependencies so the mupdf-sys registry copy can be patched ---
val fetchRustDeps by tasks.registering(Exec::class) {
    description = "Fetch Rust crates.io dependencies into the cargo registry"
    workingDir = vaultProjectDir
    commandLine("cargo", "fetch")
    inputs.files(fileTree(vaultProjectDir) { include("Cargo.toml", "Cargo.lock") })
    outputs.file(vaultProjectDir.resolve("target/.cargo_fetch_complete"))
    ensureCargoOnPath()
    doLast {
        outputs.files.singleFile.parentFile.mkdirs()
        outputs.files.singleFile.writeText("ok")
    }
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
    dependsOn(generateKotlinBindings, fetchRustDeps)
    workingDir = vaultProjectDir
    commandLine("cargo", "build", "-p", "vault-native", "--target", androidTarget, "--release")
    inputs.files(rustSource)
    inputs.property("sourceDateEpoch", sourceDateEpoch)
    inputs.property("rustDepsPatchRevision", rustDepsPatchRevision)
    outputs.file(androidLibFile)
    ensureCargoOnPath()

    doFirst {
        val ndkDir = sequenceOf(
            runCatching { android.ndkDirectory }.getOrNull(),
            configuredNdkDir,
            System.getenv("ANDROID_NDK_HOME")?.let(::File),
            System.getenv("ANDROID_NDK_ROOT")?.let(::File),
            System.getenv("NDK_HOME")?.let(::File),
            localPropertyFile("ndk.dir"),
        ).filterNotNull()
            .plus(sdkDirs.asSequence().flatMap { installedNdks(File(it, "ndk")).asSequence() })
            .firstOrNull { it.isDirectory && ndkRevision(it) == pinnedNdkVersion }
            ?: throw GradleException(
                "Android NDK $pinnedNdkVersion not found. Set ANDROID_NDK_HOME or install it under " +
                    "one of: " + (sdkDirs.map { it.absolutePath } + "local.properties ndk.dir").joinToString(", ") +
                    ". HOME=${System.getenv("HOME")}, user.home=${System.getProperty("user.home")}, " +
                    "ANDROID_NDK_HOME=${System.getenv("ANDROID_NDK_HOME")}, " +
                    "ANDROID_SDK_ROOT=${System.getenv("ANDROID_SDK_ROOT")}, " +
                    "ANDROID_HOME=${System.getenv("ANDROID_HOME")}"
            )
        val actualNdkVersion = ndkRevision(ndkDir)
        if (actualNdkVersion != pinnedNdkVersion) {
            throw GradleException(
                "Refusing to build with NDK ${actualNdkVersion ?: "unknown"} at ${ndkDir.absolutePath}; " +
                    "the reproducibility pin is $pinnedNdkVersion (gradle.properties)."
            )
        }
        logger.lifecycle("Building Rust with NDK $actualNdkVersion (${ndkDir.absolutePath})")

        val prebuiltHost = when {
            isMac && System.getProperty("os.arch").contains("aarch64") -> "darwin-aarch64"
            isMac -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val toolchainDir = ndkDir.resolve("toolchains/llvm/prebuilt/$prebuiltHost")
        if (!toolchainDir.isDirectory) {
            throw GradleException(
                "NDK toolchain not found at $toolchainDir " +
                    "(NDK $actualNdkVersion at ${ndkDir.absolutePath})."
            )
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

        val cargoTargetDir = androidLibDir.parentFile.parentFile
        environment("CARGO_TARGET_DIR", cargoTargetDir.absolutePath)

        // openssl-sys does not declare SOURCE_DATE_EPOCH as a rerun-if-env-changed
        // input, and registry sources may be patched below, so drop the cached
        // vendored OpenSSL build when either changes.
        val currentMarker = "epoch=$sourceDateEpoch\npatch=$rustDepsPatchRevision\n"
        val previousMarker = androidBuildInputsMarker.takeIf { it.isFile }?.readText()
        if (previousMarker != currentMarker) {
            logger.lifecycle("Rust build inputs changed; invalidating vendored OpenSSL artifacts")
            // openssl-src is a build-dependency: patching its src/lib.rs must also
            // rebuild the host-side build script that statically links it. Cargo
            // fingerprints registry crates by checksum, not by source content, so
            // remove both the host and target fingerprints explicitly.
            listOf(
                cargoTargetDir.resolve("build"),
                cargoTargetDir.resolve(".fingerprint"),
                cargoTargetDir.resolve("release/build"),
                cargoTargetDir.resolve("release/.fingerprint"),
            ).forEach { dir ->
                dir.listFiles()
                    ?.filter {
                        it.name.startsWith("openssl-sys-") || it.name.startsWith("openssl-src-")
                    }
                    ?.forEach { it.deleteRecursively() }
            }
            listOf(
                cargoTargetDir.resolve("release/deps"),
                androidLibDir.resolve("deps"),
            ).forEach { dir ->
                dir.listFiles()
                    ?.filter { it.name.contains("openssl_src") || it.name.contains("openssl_sys") }
                    ?.forEach { it.delete() }
            }
            androidBuildInputsMarker.parentFile.mkdirs()
            androidBuildInputsMarker.writeText(currentMarker)
        }

        val cargoHomes = sequenceOf(
            System.getenv("CARGO_HOME"),
            System.getenv("HOME")?.let { File(it, ".cargo").absolutePath },
            File(System.getProperty("user.home"), ".cargo").absolutePath,
        ).filterNotNull().distinct()
        val registrySrcDirs = cargoHomes
            .map { File(it, "registry/src") }
            .filter { it.isDirectory }
            .flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
            .filter { it.isDirectory && it.name.startsWith("index.crates.io-") }
            .distinct()
            .toList()
        val rustFlags = (
            registrySrcDirs.map { "--remap-path-prefix=${it.absolutePath}=/crate-src" } +
                "--remap-path-prefix=${vaultProjectDir.absolutePath}=/src" +
                "--remap-path-prefix=${cargoTargetDir.absolutePath}=/cargo-target" +
                "--remap-path-prefix=${rootProject.projectDir.absolutePath}=/project"
            ).joinToString(" ")
        environment("RUSTFLAGS", rustFlags)
        environment("SOURCE_DATE_EPOCH", sourceDateEpoch.toString())

        val mupdfSysDir = registryCrateDir(registrySrcDirs, "mupdf-sys")
        val mupdfBuildRs = mupdfSysDir.resolve("build.rs")
        if (mupdfBuildRs.readText().contains("// libre-reproducible")) {
            logger.lifecycle("mupdf-sys build.rs already patched for a deterministic wrapper layout")
        } else {
            val old = """fn build_wrapper() -> Result<()> {
    let mut build = cc::Build::new();
    for entry in fs::read_dir("wrapper")? {
        let entry = entry?;
        let path = entry.path();
        if path.extension().is_some_and(|ext| ext == "c") {
            build.file(&path);
        }
    }"""
            val new = """// libre-reproducible: sort wrapper sources for a deterministic link layout
fn build_wrapper() -> Result<()> {
    let mut build = cc::Build::new();
    let mut files = Vec::new();
    for entry in fs::read_dir("wrapper")? {
        let entry = entry?;
        let path = entry.path();
        if path.extension().is_some_and(|ext| ext == "c") {
            files.push(path);
        }
    }
    files.sort();
    for path in files {
        build.file(&path);
    }"""
            val patched = mupdfBuildRs.readText().replace(old, new)
            if (patched == mupdfBuildRs.readText()) {
                throw GradleException(
                    "Unexpected mupdf-sys build.rs content; cannot apply the deterministic wrapper sort. " +
                        "Crate dir: ${mupdfSysDir.absolutePath}"
                )
            }
            mupdfBuildRs.writeText(patched)
            logger.lifecycle("Patched ${mupdfSysDir.name}/build.rs for a deterministic wrapper layout")
        }

        patchOpenSslSrc(registryCrateDir(registrySrcDirs, "openssl-src"))
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
