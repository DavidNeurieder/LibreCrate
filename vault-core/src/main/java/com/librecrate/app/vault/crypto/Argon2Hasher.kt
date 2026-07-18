package com.librecrate.app.vault.crypto

interface Argon2Hasher {
    fun hash(
        password: ByteArray,
        salt: ByteArray,
        tCostInIterations: Int,
        mCostInKibibyte: Int,
        parallelism: Int,
        hashLengthInBytes: Int,
    ): ByteArray
}
