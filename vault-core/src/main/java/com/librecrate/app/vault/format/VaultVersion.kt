package com.librecrate.app.vault.format

enum class VaultVersion(val value: Int) {
    V1(1);

    companion object {
        fun from(value: Int): VaultVersion? =
            entries.firstOrNull { it.value == value }
    }
}
