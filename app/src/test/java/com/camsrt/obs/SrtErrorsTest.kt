package com.camsrt.obs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtErrorsTest {

    @Test
    fun brokenConnectionExplainsDrop() {
        val out = SrtErrors.describe("Connection was broken")
        assertTrue(out.contains("caiu"))
        assertTrue(out.contains("OBS"))
    }

    @Test
    fun matchingIgnoresCase() {
        assertEquals(
            SrtErrors.describe("connection was lost"),
            SrtErrors.describe("CONNECTION WAS LOST")
        )
    }

    @Test
    fun timeoutMentionsFirewall() {
        assertTrue(SrtErrors.describe("Connection timed out").contains("firewall"))
    }

    @Test
    fun refusedMentionsListener() {
        assertTrue(SrtErrors.describe("Connection refused").contains("listener"))
    }

    @Test
    fun unreachableMentionsNetwork() {
        assertTrue(SrtErrors.describe("No route to host").contains("outra rede"))
    }

    @Test
    fun unknownHostMentionsResolve() {
        assertTrue(SrtErrors.describe("Unknown host: x").contains("resolveu"))
    }

    @Test
    fun unknownMessagePassesThrough() {
        assertEquals("algo estranho", SrtErrors.describe("algo estranho"))
    }

    @Test
    fun nullOrBlankHasFallback() {
        assertEquals("sem detalhe do erro", SrtErrors.describe(null))
        assertEquals("sem detalhe do erro", SrtErrors.describe("  "))
    }
}
