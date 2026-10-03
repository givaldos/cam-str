package com.camsrt.obs

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sonda de handshake SRT para confirmar ouvintes de verdade.
 *
 * O teste UDP simples (1 byte) não distingue SRT aberto de porta
 * filtrada: ambos ficam em silêncio. Esta sonda envia um pedido de
 * handshake conclusion de 64 bytes, copiado de um caller SRT real
 * (validado contra srt-live-transmit 1.5.6 e contra o OBS): ouvinte
 * SRT responde com handshake ecoando ISN e socket id, mais cookie
 * diferente de zero. Serviço que só reflete (echo) devolve cookie
 * zero e não valida.
 *
 * Puro e sem Android, para ter teste de unidade simples.
 */
internal object SrtHandshake {

    const val PROBE_LEN = 64

    /**
     * Monta o pedido. ISN e socket id aleatórios por sonda: os ecos
     * na resposta provam que ela veio do handshake, não de lixo.
     */
    fun buildProbe(isn: Int, socketId: Int, timestamp: Int): ByteArray {
        val buf = ByteBuffer.allocate(PROBE_LEN).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0x8000.toShort()) // pacote de controle, tipo handshake
        buf.putShort(0) // reservado
        buf.putInt(0) // tipo estendido
        buf.putInt(timestamp)
        buf.putInt(0) // destino ainda desconhecido
        buf.putInt(4) // versão
        buf.putInt(2) // criptografia: nenhuma
        buf.putInt(isn)
        buf.putInt(1500) // MTU
        buf.putInt(8192) // janela de fluxo
        buf.putInt(1) // conclusion
        buf.putInt(socketId)
        buf.putInt(0) // sem cookie no pedido
        // 16 bytes de peer zerados: o ouvinte responde assim mesmo.
        return buf.array()
    }

    /**
     * Resposta válida: pacote handshake de 64+ bytes ecoando ISN e
     * socket id (no corpo ou no destino), com cookie não zero.
     */
    fun isHandshakeReply(data: ByteArray, len: Int, isn: Int, socketId: Int): Boolean {
        if (len < PROBE_LEN || data.size < PROBE_LEN) return false
        if (data[0] != 0x80.toByte() || data[1] != 0x00.toByte()) return false
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        if (buf.getInt(24) != isn) return false
        if (buf.getInt(40) != socketId && buf.getInt(12) != socketId) return false
        return buf.getInt(44) != 0
    }
}
