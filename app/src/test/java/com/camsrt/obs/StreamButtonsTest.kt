package com.camsrt.obs

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamButtonsTest {

    @Test
    fun idleShowsStartOnly() {
        val s = StreamButtons.resolve(
            isStreaming = false, connecting = false, retryPending = false
        )
        assertEquals(true, s.startEnabled)
        assertEquals(false, s.stopEnabled)
        assertEquals("Parar", s.stopText)
    }

    @Test
    fun streamingShowsStop() {
        val s = StreamButtons.resolve(
            isStreaming = true, connecting = false, retryPending = false
        )
        assertEquals(false, s.startEnabled)
        assertEquals(true, s.stopEnabled)
        assertEquals("Parar", s.stopText)
    }

    @Test
    fun connectingShowsCancel() {
        val s = StreamButtons.resolve(
            isStreaming = false, connecting = true, retryPending = false
        )
        assertEquals(false, s.startEnabled)
        assertEquals(true, s.stopEnabled)
        assertEquals("Cancelar", s.stopText)
    }

    @Test
    fun retryWaitShowsCancel() {
        val s = StreamButtons.resolve(
            isStreaming = false, connecting = false, retryPending = true
        )
        assertEquals(false, s.startEnabled)
        assertEquals(true, s.stopEnabled)
        assertEquals("Cancelar", s.stopText)
    }
}
