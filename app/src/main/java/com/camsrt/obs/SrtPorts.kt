package com.camsrt.obs

/**
 * Parsing de porta/faixa para conexão e procura de SRT.
 * Puro e sem Android, para ter teste de unidade simples.
 */
internal object SrtPorts {

    val defaultScanPorts = listOf(
        8888, 9000, 9001, 9020, 9100, 9998, 9999, 5000, 5001, 6000, 6001
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

    /** Porta para conectar: número direto ou o início da faixa. */
    fun parsePortOrFirst(text: String): Int {
        text.toIntOrNull()?.let { return it }
        val first = Regex("""(\d{1,5})\s*-""").find(text)
            ?.groupValues?.get(1)?.toIntOrNull()
        return first ?: 9998
    }
}
