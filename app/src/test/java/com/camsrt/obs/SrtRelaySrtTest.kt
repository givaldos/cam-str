package com.camsrt.obs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * Prova de que SRT atravessa o [SrtRelay] até alvo IPv6, usando os
 * binários de referência (srt-live-transmit). Roda onde eles e ::1
 * existirem; fora disso o teste pula (assume).
 */
class SrtRelaySrtTest {

    private fun hasBinary(name: String): Boolean {
        return try {
            val p = ProcessBuilder("sh", "-c", "command -v $name")
                .redirectErrorStream(true).start()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasIpv6Loopback(): Boolean {
        return try {
            DatagramSocket(InetSocketAddress(InetAddress.getByName("::1"), 0)).close()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun freePortV4(): Int {
        DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)).use {
            return it.localPort
        }
    }

    private fun startSilent(vararg cmd: String): Process {
        val log = java.io.File.createTempFile("srt-relay-test", ".log")
        log.deleteOnExit()
        return ProcessBuilder(*cmd)
            .redirectOutput(log)
            .redirectErrorStream(true)
            .start()
    }

    private fun waitFor(condition: () -> Boolean, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    @Test
    fun srtLiveThroughRelayToIpv6() {
        assumeTrue(hasBinary("srt-live-transmit"))
        assumeTrue(hasIpv6Loopback())
        val loop4 = InetAddress.getByName("127.0.0.1")
        val loop6 = InetAddress.getByName("::1")

        val srtPort = freePortV4()
        val udpInPort = freePortV4()
        // Saída UDP do listener: o teste é dono do socket (sem perda).
        val udpOut = DatagramSocket(InetSocketAddress(loop4, 0))
        udpOut.soTimeout = 1000
        val udpOutPort = udpOut.localPort

        val listener = startSilent(
            "srt-live-transmit",
            "srt://[::1]:$srtPort?mode=listener",
            "udp://127.0.0.1:$udpOutPort"
        )
        try {
            Thread.sleep(1200)
            assumeTrue("listener SRT morreu", listener.isAlive)
            val relay = SrtRelay(InetSocketAddress(loop6, srtPort))
            val relayPort = relay.start()
            try {
                val caller = startSilent(
                    "srt-live-transmit",
                    "udp://127.0.0.1:$udpInPort",
                    "srt://127.0.0.1:$relayPort"
                )
                try {
                    // Handshake completo = tráfego voltando pelo relay.
                    assertTrue(
                        "SRT não conectou via relay",
                        waitFor({ relay.forwardedToClient > 0 }, 10000)
                    )
                    assumeTrue("caller SRT morreu", caller.isAlive)
                    // Alimenta UDP e confere a volta pelo SRT+relay.
                    val sent = (0 until 10).map { "RELAY6-$it" }
                    DatagramSocket().use { feeder ->
                        for (m in sent) {
                            val b = m.toByteArray()
                            feeder.send(
                                DatagramPacket(
                                    b, b.size, InetSocketAddress(loop4, udpInPort)
                                )
                            )
                            Thread.sleep(50)
                        }
                    }
                    val got = mutableSetOf<String>()
                    val end = System.currentTimeMillis() + 10000
                    val buf = ByteArray(2048)
                    while (System.currentTimeMillis() < end && got.size < sent.size) {
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            udpOut.receive(pkt)
                            got += String(pkt.data, pkt.offset, pkt.length)
                        } catch (_: java.net.SocketTimeoutException) {
                        }
                    }
                    assertEquals(sent.toSet(), got)
                } finally {
                    caller.destroy()
                    caller.waitFor(3, TimeUnit.SECONDS)
                }
            } finally {
                relay.stop()
            }
        } finally {
            listener.destroy()
            listener.waitFor(3, TimeUnit.SECONDS)
            udpOut.close()
        }
    }
}
