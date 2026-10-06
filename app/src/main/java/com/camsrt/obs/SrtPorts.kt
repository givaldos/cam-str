package com.camsrt.obs

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Parsing de porta/faixa para conexão e procura de SRT.
 * Puro e sem Android, para ter teste de unidade simples.
 */
internal object SrtPorts {

    /**
     * Como alcançar o alvo: direto (a biblioteca SRT fala IPv4) ou
     * via relay local (alvo só tem IPv6, que a biblioteca não fala).
     */
    sealed interface StreamRoute {
        data class Direct(val hostForUrl: String) : StreamRoute
        data class Relay(val addr: InetAddress) : StreamRoute
    }

    /** Faixa padrão do sniff de rede (cobre o listener do OBS). */
    val networkSniffPorts = (9990..9999).toList()

    val defaultScanPorts = networkSniffPorts + listOf(
        8888, 9000, 9001, 9020, 9100, 5000, 5001, 6000, 6001
    )

    /**
     * "9998" procura a digitada + as comuns de SRT; "9900-9910"
     * procura a faixa (teto de 50 portas para não demorar).
     */
    fun parseScanPorts(text: String): List<Int> {
        val range = Regex("""(\d{1,5})\s*-\s*(\d{1,5})""").find(text)
        if (range != null) {
            val a = range.groupValues[1].toInt().coerceIn(1, 65535)
            val b = range.groupValues[2].toInt().coerceIn(1, 65535)
            val (lo, hi) = if (a <= b) a to b else b to a
            return (lo..minOf(hi, lo + 49)).toList()
        }
        val single = text.toIntOrNull()?.takeIf { it in 1..65535 }
        return ((if (single != null) listOf(single) else emptyList()) + defaultScanPorts)
            .distinct()
    }

    /**
     * Portas do sniff de rede (IP vazio): faixa digitada vale como
     * está (teto de 50); porta única soma a faixa 9990-9999; qualquer
     * outra coisa varre só 9990-9999.
     */
    fun parseNetworkScanPorts(text: String): List<Int> {
        val range = Regex("""(\d{1,5})\s*-\s*(\d{1,5})""").find(text)
        if (range != null) {
            val a = range.groupValues[1].toInt().coerceIn(1, 65535)
            val b = range.groupValues[2].toInt().coerceIn(1, 65535)
            val (lo, hi) = if (a <= b) a to b else b to a
            return (lo..minOf(hi, lo + 49)).toList()
        }
        val single = text.trim().toIntOrNull()?.takeIf { it in 1..65535 }
        return ((if (single != null) listOf(single) else emptyList()) + networkSniffPorts)
            .distinct()
    }

    /**
     * Decide a rota: com IPv4 disponível vai direto (nome com os dois
     * vira o literal IPv4, para a biblioteca nunca pegar o IPv6); só
     * com IPv6 vai via relay local. Sem resolver, passa direto como
     * antes e deixa a biblioteca reportar o erro.
     */
    fun routeFor(
        host: String,
        resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }
    ): StreamRoute {
        val bare = bareHost(host)
        val addrs = try {
            resolve(bare)
        } catch (_: Throwable) {
            return StreamRoute.Direct(host.trim())
        }
        addrs.filterIsInstance<Inet4Address>().firstOrNull()?.let {
            val literal = it.hostAddress ?: bare
            return StreamRoute.Direct(if (bare == literal) host.trim() else literal)
        }
        addrs.filterIsInstance<Inet6Address>().firstOrNull()?.let {
            return StreamRoute.Relay(it)
        }
        return StreamRoute.Direct(host.trim())
    }

    /** Porta para conectar: número direto ou o início da faixa. */
    fun parsePortOrFirst(text: String): Int {
        text.toIntOrNull()?.let { return it }
        val first = Regex("""(\d{1,5})\s*-""").find(text)
            ?.groupValues?.get(1)?.toIntOrNull()
        return first ?: 9998
    }

    /**
     * Completa o último octeto ("10" ou ".10") com o /24 do IPv4
     * local ("192.168.0.20" + "10" vira "192.168.0.10"). IP cheio,
     * nome, IPv6, octeto inválido ou sem IP local voltam como estão.
     */
    fun expandLastOctet(host: String, localIpv4: String?): String {
        val t = host.trim()
        val last = Regex("""^\.?(\d{1,3})$""").matchEntire(t)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 0..255 } ?: return t
        val o = localIpv4?.let { parseIpv4(it) } ?: return t
        return "${o[0]}.${o[1]}.${o[2]}.$last"
    }

    /** Tira espaços e colchetes: "[fe80::1]" vira "fe80::1". */
    fun bareHost(host: String): String {
        val t = host.trim()
        if (t.length >= 2 && t.startsWith("[") && t.endsWith("]")) {
            return t.substring(1, t.length - 1)
        }
        return t
    }

    /**
     * URL SRT de discagem. A latência aqui vai em MILISSEGUNDOS, que
     * é a unidade do parâmetro latency no SRT nativo (srtdroid lê
     * como latencyInMs). NÃO multiplicar por 1000: microssegundos
     * valem só no FFmpeg/OBS. O SRT negocia o maior valor entre os
     * dois lados, então ?latency=120000 vira 120 s de buffer, com
     * atraso gigante, memória estourada e vídeo travando. O valor é
     * limitado a 20..5000 ms para um erro de digitação não derrubar
     * a live (no app o campo é ms; no OBS o mesmo valor é µs).
     */
    fun callerSrtUrl(host: String, port: Int, latencyMs: Int): String =
        "srt://${formatSrtHost(host)}:$port" +
            "?latency=${latencyMs.coerceIn(20, 5000)}&connect_timeout=15000"

    /**
     * Host pronto para a URL SRT: IPv6 literal vai entre colchetes
     * (zona %wlan0 vira %25wlan0, formato de URI); IPv4 e nome ficam
     * como estão.
     */
    fun formatSrtHost(host: String): String {
        val bare = bareHost(host)
        if (!bare.contains(':')) return bare
        return "[" + bare.replace("%", "%25") + "]"
    }

    private fun parseIpv4(ip: String): IntArray? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n
        }
        return out
    }

    /**
     * Só varre rede local (não faz sentido sondar a internet): 10/8,
     * 172.16/12, 192.168/16 e link-local 169.254/16.
     */
    fun isSweepableIpv4(ip: String): Boolean {
        val o = parseIpv4(ip) ?: return false
        return when {
            o[0] == 10 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 169 && o[1] == 254 -> true
            else -> false
        }
    }

    /**
     * Preferência de interface para IPv6 link-local: Wi-Fi e cabo (0)
     * antes de resto (1) e dados móveis por último (2). Evita mandar
     * sonda e stream pela rmnet quando o Wi-Fi está ligado.
     */
    fun interfaceNameScore(name: String): Int = when {
        name.startsWith("wlan") || name.startsWith("eth") ||
            name.startsWith("wl") || name.startsWith("en") ||
            name.startsWith("ap") -> 0
        name.startsWith("rmnet") || name.startsWith("ccinet") -> 2
        else -> 1
    }

    /**
     * Hosts do /24 do IP local (gateway .1 primeiro, depois o resto
     * em ordem), sem o próprio aparelho. Entrada inválida dá vazio.
     */
    fun ipv4SweepHosts(localIp: String): List<String> {
        val o = parseIpv4(localIp) ?: return emptyList()
        val base = "${o[0]}.${o[1]}.${o[2]}"
        val self = o[3]
        val hosts = ArrayList<String>(253)
        if (self != 1) hosts += "$base.1"
        for (last in 2..254) {
            if (last != self) hosts += "$base.$last"
        }
        return hosts
    }
}
