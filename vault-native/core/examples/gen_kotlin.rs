fn main() {
    let lib_path = std::env::args().nth(1).expect("Usage: gen_kotlin <path-to-libvault_native.so> [out-dir]");
    let out_dir = std::env::args().nth(2).unwrap_or_else(|| "generated/kotlin".into());

    let library_path = camino::Utf8Path::new(&lib_path);
    let out_path = camino::Utf8Path::new(&out_dir);

    let config_supplier =
        uniffi_bindgen::EmptyCrateConfigSupplier;

    uniffi_bindgen::library_mode::generate_bindings(
        library_path,
        None,
        &uniffi_bindgen::bindings::KotlinBindingGenerator,
        &config_supplier,
        None,
        out_path,
        false,
    )
    .expect("Failed to generate Kotlin bindings");

    println!("Kotlin bindings generated in: {out_dir}");
}
