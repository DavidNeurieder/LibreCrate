package com.librecrate.app.data.db

import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `dateToTimestamp then fromTimestamp roundtrips`() {
        val original = Date()
        val timestamp = converters.dateToTimestamp(original)
        val result = converters.fromTimestamp(timestamp)
        assertEquals(original, result)
    }

    @Test
    fun `fromTimestamp returns null for null input`() {
        assertNull(converters.fromTimestamp(null))
    }

    @Test
    fun `dateToTimestamp returns null for null input`() {
        assertNull(converters.dateToTimestamp(null))
    }

    @Test
    fun `fromTimestamp with known value`() {
        val date = Date(1700000000000L)
        val timestamp = converters.dateToTimestamp(date)
        val result = converters.fromTimestamp(timestamp)
        assertEquals(date, result)
    }
}
