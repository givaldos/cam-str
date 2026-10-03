package com.camsrt.obs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class SrtHandshakeTest {

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    @Test
    fun probeIs64BytesWithHandshakeHeader() {
        val p = SrtHandshake.buildProbe(isn = 123, socketId = 456, timestamp = 789)
        assertEquals(64, p.size)
        assertEquals(0x80.toByte(), p[0])
        assertEquals(0x00.toByte(), p[1])
        val buf = ByteBuffer.wrap(p)
        assertEquals(789, buf.getInt(8)) // timestamp
        assertEquals(0, buf.getInt(12)) // destino desconhecido
        assertEquals(4, buf.getInt(16)) // versão
        assertEquals(1, buf.getInt(36)) // conclusion
        assertEquals(123, buf.getInt(24)) // ISN ecoável
        assertEquals(456, buf.getInt(40)) // socket id ecoável
        assertEquals(0, buf.getInt(44)) // sem cookie no pedido
    }

    @Test
    fun realListenerReplyValidates() {
        // Resposta real de srt-live-transmit 1.5.6 ao pedido capturado.
        val reply = hex(
            "80000000000000000023406e1ead5f2f0000000500004a1748ef7183" +
                "000005dc00002000000000011ead5f2fd3bdbe2c0100007f" +
                "000000000000000000000000"
        )
        assertEquals(64, reply.size)
        assertTrue(SrtHandshake.isHandshakeReply(reply, reply.size, 1223651715, 514678575))
    }

    @Test
    fun echoOfOwnProbeDoesNotValidate() {
        // Serviço echo devolve o pedido com cookie zero: não é SRT.
        val p = SrtHandshake.buildProbe(isn = 123, socketId = 456, timestamp = 789)
        assertFalse(SrtHandshake.isHandshakeReply(p, p.size, 123, 456))
    }

    @Test
    fun wrongEchoDoesNotValidate() {
        val reply = testReply(isn = 111, socketId = 222, cookie = 333)
        assertFalse(SrtHandshake.isHandshakeReply(reply, reply.size, 999, 222))
        assertFalse(SrtHandshake.isHandshakeReply(reply, reply.size, 111, 999))
    }

    @Test
    fun shortOrGarbageDoesNotValidate() {
        assertFalse(SrtHandshake.isHandshakeReply(ByteArray(10), 10, 1, 2))
        val garbage = ByteArray(64) { 0x41 }
        assertFalse(SrtHandshake.isHandshakeReply(garbage, 64, 1, 2))
    }

    companion object {
        fun testReply(isn: Int, socketId: Int, cookie: Int): ByteArray {
            val buf = ByteBuffer.allocate(64)
            buf.putShort(0, 0x8000.toShort())
            buf.putInt(12, socketId)
            buf.putInt(16, 5)
            buf.putInt(24, isn)
            buf.putInt(28, 1500)
            buf.putInt(36, 1)
            buf.putInt(40, socketId)
            buf.putInt(44, cookie)
            return buf.array()
        }
    }
}
