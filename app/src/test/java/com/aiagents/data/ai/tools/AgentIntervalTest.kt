package com.aiagents.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentIntervalTest {

    @Test
    fun `parse interval supports s m h d`() {
        assertEquals(90_000L, parseIntervalMillis("90s"))
        assertEquals(30 * 60_000L, parseIntervalMillis("30m"))
        assertEquals(2 * 3_600_000L, parseIntervalMillis("2h"))
        assertEquals(86_400_000L, parseIntervalMillis("1d"))
    }

    @Test
    fun `parse interval tolerates spaces and case variants`() {
        assertEquals(30 * 60_000L, parseIntervalMillis(" 30 m "))
        assertEquals(60_000L, parseIntervalMillis("1M"))
    }

    @Test
    fun `parse interval rejects garbage`() {
        assertNull(parseIntervalMillis(""))
        assertNull(parseIntervalMillis("abc"))
        assertNull(parseIntervalMillis("every 30 minutes"))
        assertNull(parseIntervalMillis("30x"))
    }

    @Test
    fun `format interval renders human readable`() {
        assertEquals("90s", formatInterval(90_000L))
        assertEquals("30m", formatInterval(30 * 60_000L))
        assertEquals("2h", formatInterval(2 * 3_600_000L))
        assertEquals("1d", formatInterval(86_400_000L))
    }
}
