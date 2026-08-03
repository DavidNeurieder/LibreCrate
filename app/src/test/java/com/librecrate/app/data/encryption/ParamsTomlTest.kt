package com.librecrate.app.data.encryption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParamsTomlTest {

    @Test
    fun `buildParamsToml matches the desktop format`() {
        val toml = buildParamsToml(16_384u, 3u, 2u)
        assertEquals("memory_cost = 16384\niterations = 3\nparallelism = 2\nhash_length = 32\n", toml)
    }

    @Test
    fun `parseParamsToml reads the phone constants`() {
        val toml = "memory_cost = 16384\niterations = 3\nparallelism = 2\nhash_length = 32\n"
        assertEquals(Triple(16_384u, 3u, 2u), parseParamsToml(toml))
    }

    @Test
    fun `parseParamsToml reads the desktop defaults`() {
        val toml = "memory_cost = 19456\niterations = 2\nparallelism = 2\nhash_length = 32\n"
        assertEquals(Triple(19_456u, 2u, 2u), parseParamsToml(toml))
    }

    @Test
    fun `parseParamsToml returns null for missing fields`() {
        assertNull(parseParamsToml("memory_cost = 16384\n"))
        assertNull(parseParamsToml(""))
    }

    @Test
    fun `round trip build then parse`() {
        val toml = buildParamsToml(16_384u, 3u, 2u)
        assertEquals(Triple(16_384u, 3u, 2u), parseParamsToml(toml))
    }
}
