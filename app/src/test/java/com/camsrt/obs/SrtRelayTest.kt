package com.camsrt.obs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class SrtRelayTest {

    private fun freePort(): Int {
        DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)).use {
            return it.localPort
        }
    }

    @Test
    fun forwardsBothWays() {
        // Ponta "alvo": ecoa o que chega.
        val targetPort = freePort()
        val target = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), targetPort))
        target.soTimeout = 5000
        val relay = SrtRelay(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), targetPort)
        )
        val localPort = relay.start()
        assertTrue(localPort > 0)
        try {
            val client = DatagramSocket()
            client.soTimeout = 5000
            try {
                client.send(
                    DatagramPacket(
                        "ping".toByteArray(), 4,
                        InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort)
                    )
                )
                // O alvo recebe o que o cliente mandou via relay.
                val buf = ByteArray(2048)
                val atTarget = DatagramPacket(buf, buf.size)
                target.receive(atTarget)
                assertEquals("ping", String(atTarget.data, 0, atTarget.length))
                // O alvo responde; a resposta volta ao cliente.
                target.send(
                    DatagramPacket(
                        "pong".toByteArray(), 4, atTarget.socketAddress
                    )
                )
                val backBuf = ByteArray(2048)
                val back = DatagramPacket(backBuf, backBuf.size)
                client.receive(back)
                assertEquals("pong", String(back.data, 0, back.length))
                assertTrue(relay.forwardedToTarget >= 1)
                assertTrue(relay.forwardedToClient >= 1)
            } finally {
                client.close()
            }
        } finally {
            relay.stop()
            target.close()
        }
    }

    @Test
    fun forwardsBothWaysToIpv6() {
        // Perna IPv6 do relay (a usada com alvo só IPv6): sem ::1, pula.
        try {
            DatagramSocket(InetSocketAddress(InetAddress.getByName("::1"), 0)).close()
        } catch (_: Throwable) {
            assumeTrue(false)
        }
        val loop4 = InetAddress.getByName("127.0.0.1")
        val loop6 = InetAddress.getByName("::1")
        val target = DatagramSocket(InetSocketAddress(loop6, 0))
        target.soTimeout = 5000
        val relay = SrtRelay(target.localSocketAddress as InetSocketAddress)
        val localPort = relay.start()
        try {
            DatagramSocket().use { client ->
                client.soTimeout = 5000
                client.send(
                    DatagramPacket(
                        "ping6".toByteArray(), 5, InetSocketAddress(loop4, localPort)
                    )
                )
                val buf = ByteArray(2048)
                val atTarget = DatagramPacket(buf, buf.size)
                target.receive(atTarget)
                assertEquals("ping6", String(atTarget.data, 0, atTarget.length))
                target.send(
                    DatagramPacket(
                        "pong6".toByteArray(), 5, atTarget.socketAddress
                    )
                )
                val back = DatagramPacket(buf, buf.size)
                client.receive(back)
                assertEquals("pong6", String(back.data, 0, back.length))
                assertTrue(relay.forwardedToTarget >= 1)
                assertTrue(relay.forwardedToClient >= 1)
            }
        } finally {
            relay.stop()
            target.close()
        }
    }

    @Test
    fun stopFreesLocalPort() {
        val relay = SrtRelay(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), freePort())
        )
        val localPort = relay.start()
        relay.stop()
        // Se a porta não foi liberada, este bind falha.
        DatagramSocket(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort)
        ).use {
            assertEquals(localPort, it.localPort)
        }
    }

    @Test
    fun startIsIdempotent() {
        val relay = SrtRelay(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), freePort())
        )
        try {
            assertEquals(relay.start(), relay.start())
        } finally {
            relay.stop()
        }
    }

    @Test
    fun pinsFirstClient() {
        // Só o primeiro cliente é atendido; outro não injeta nada.
        val target = DatagramSocket(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)
        )
        target.soTimeout = 1000
        val relay = SrtRelay(target.localSocketAddress as InetSocketAddress)
        val localPort = relay.start()
        val loop = InetAddress.getByName("127.0.0.1")
        DatagramSocket().use { clientA ->
            DatagramSocket().use { clientB ->
                try {
                    clientA.send(
                        DatagramPacket(
                            "A".toByteArray(), 1, InetSocketAddress(loop, localPort)
                        )
                    )
                    val buf = ByteArray(2048)
                    val first = DatagramPacket(buf, buf.size)
                    target.receive(first)
                    assertEquals("A", String(first.data, 0, first.length))
                    clientB.send(
                        DatagramPacket(
                            "B".toByteArray(), 1, InetSocketAddress(loop, localPort)
                        )
                    )
                    try {
                        target.receive(DatagramPacket(buf, buf.size))
                        org.junit.Assert.fail("relay encaminhou cliente estranho")
                    } catch (_: java.net.SocketTimeoutException) {
                        // esperado: nada do B chega ao alvo
                    }
                } finally {
                    relay.stop()
                    target.close()
                }
            }
        }
    }
}
