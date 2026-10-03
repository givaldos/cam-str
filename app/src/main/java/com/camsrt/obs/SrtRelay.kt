package com.camsrt.obs

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Retransmissor UDP local para alvo só IPv6.
 *
 * A biblioteca SRT do app só cria socket IPv4, então ela fala com
 * este relay em 127.0.0.1 e o relay repassa datagrama a datagrama
 * ao alvo real (IPv6 ou qualquer outro). O SRT passa transparente:
 * handshake, números de sequência e retransmissões não mudam, só
 * entra 1 salto local no caminho.
 *
 * Puro e sem Android, para ter teste de unidade simples.
 */
internal class SrtRelay(private val target: InetSocketAddress) {

    private var down: DatagramSocket? = null
    private var up: DatagramSocket? = null
    private var downThread: Thread? = null
    private var upThread: Thread? = null

    @Volatile
    private var client: InetSocketAddress? = null

    @Volatile
    var running = false
        private set

    var localPort = -1
        private set

    private val _toTarget = AtomicLong(0)
    private val _toClient = AtomicLong(0)

    val forwardedToTarget: Long get() = _toTarget.get()
    val forwardedToClient: Long get() = _toClient.get()

    /**
     * Sobe o relay e devolve a porta local (127.0.0.1) para a
     * biblioteca SRT discar. Chamar de novo não duplica.
     */
    @Synchronized
    fun start(): Int {
        if (running) return localPort
        val loop = InetAddress.getByName("127.0.0.1")
        val downSocket = DatagramSocket(InetSocketAddress(loop, 0))
        val upSocket = if (target.address is Inet6Address) {
            DatagramSocket(InetSocketAddress(InetAddress.getByName("::"), 0))
        } else {
            DatagramSocket()
        }
        try {
            upSocket.connect(target)
        } catch (t: Throwable) {
            try {
                downSocket.close()
            } catch (_: Throwable) {
            }
            try {
                upSocket.close()
            } catch (_: Throwable) {
            }
            throw t
        }
        down = downSocket
        up = upSocket
        running = true
        localPort = downSocket.localPort
        downThread = relayThread("srt-relay-down") { pumpDown(downSocket, upSocket) }
        upThread = relayThread("srt-relay-up") { pumpUp(downSocket, upSocket) }
        downThread?.start()
        upThread?.start()
        return localPort
    }

    @Synchronized
    fun stop() {
        running = false
        client = null
        try {
            down?.close()
        } catch (_: Throwable) {
        }
        try {
            up?.close()
        } catch (_: Throwable) {
        }
        // Fechar o socket já desbloqueia o receive; o join é rápido.
        try {
            downThread?.join(1000)
        } catch (_: Throwable) {
        }
        try {
            upThread?.join(1000)
        } catch (_: Throwable) {
        }
        downThread = null
        upThread = null
        down = null
        up = null
        localPort = -1
    }

    private fun relayThread(name: String, body: () -> Unit): Thread {
        val t = Thread({ body() }, name)
        t.isDaemon = true
        return t
    }

    /** Cliente (biblioteca) -> alvo. Aprende o 1º cliente local. */
    private fun pumpDown(downSocket: DatagramSocket, upSocket: DatagramSocket) {
        downSocket.soTimeout = PUMP_TIMEOUT_MS
        val buf = ByteArray(PACKET_BUF)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                downSocket.receive(pkt)
                val sender = pkt.socketAddress as? InetSocketAddress ?: continue
                if (sender.address?.isLoopbackAddress != true) continue
                val known = client
                if (known == null) {
                    client = sender
                } else if (known != sender) {
                    continue // fixo no 1º cliente: ignora estranho
                }
                upSocket.send(DatagramPacket(pkt.data, pkt.offset, pkt.length))
                _toTarget.incrementAndGet()
            } catch (_: SocketTimeoutException) {
            } catch (_: PortUnreachableException) {
                // alvo fechou a porta: segue vivo para o retry
            } catch (_: SocketException) {
                if (!running) return
            } catch (_: Throwable) {
                if (!running) return
            }
        }
    }

    /** Alvo -> cliente. Sem cliente aprendido, descarta. */
    private fun pumpUp(downSocket: DatagramSocket, upSocket: DatagramSocket) {
        upSocket.soTimeout = PUMP_TIMEOUT_MS
        val buf = ByteArray(PACKET_BUF)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                upSocket.receive(pkt)
                val peer = client ?: continue
                downSocket.send(DatagramPacket(pkt.data, pkt.offset, pkt.length, peer))
                _toClient.incrementAndGet()
            } catch (_: SocketTimeoutException) {
            } catch (_: PortUnreachableException) {
            } catch (_: SocketException) {
                if (!running) return
            } catch (_: Throwable) {
                if (!running) return
            }
        }
    }

    companion object {
        private const val PUMP_TIMEOUT_MS = 500
        private const val PACKET_BUF = 2048
    }
}
