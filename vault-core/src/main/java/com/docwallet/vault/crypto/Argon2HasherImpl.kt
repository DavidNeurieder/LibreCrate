package com.docwallet.vault.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

class Argon2HasherImpl : Argon2Hasher {
    override fun hash(
        password: ByteArray,
        salt: ByteArray,
        tCostInIterations: Int,
        mCostInKibibyte: Int,
        parallelism: Int,
        hashLengthInBytes: Int,
    ): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withParallelism(parallelism)
            .withMemoryAsKB(mCostInKibibyte)
            .withIterations(tCostInIterations)

        val gen = Argon2BytesGenerator()
        gen.init(builder.build())
        val result = ByteArray(hashLengthInBytes)
        gen.generateBytes(password, result)
        return result
    }
}
