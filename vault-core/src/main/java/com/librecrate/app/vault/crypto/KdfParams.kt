package com.librecrate.app.vault.crypto

data class KdfParams(
    val memoryCost: Int = 19456,
    val iterations: Int = 2,
    val parallelism: Int = 2,
    val hashLengthInBytes: Int = 32,
)
