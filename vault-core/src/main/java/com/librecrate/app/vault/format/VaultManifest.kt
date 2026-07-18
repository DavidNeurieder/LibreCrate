package com.librecrate.app.vault.format

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class VaultManifest(
    val version: Int = 1,
    val kdf: String = "argon2id",
    val salt: String,
    val argon2Memory: Int = 19456,
    val argon2Iterations: Int = 2,
    val argon2Parallelism: Int = 2,
    val createdAt: Long = System.currentTimeMillis(),
    val documentCount: Int = 0,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun serialize(manifest: VaultManifest): String =
            json.encodeToString(manifest)

        fun deserialize(data: String): VaultManifest =
            json.decodeFromString(data)
    }
}
