package com.camsrt.obs

/**
 * Traduz mensagens cruas de erro (em inglês, vindas do SRT e da
 * rede) para explicações curtas e acionáveis em português.
 * Puro e sem Android, para ter teste de unidade simples.
 */
internal object SrtErrors {

    /**
     * Explicação para [raw], que é `Throwable.message` ou nulo.
     * Mensagem desconhecida passa como está; sem mensagem, diz que
     * não há detalhe.
     */
    fun describe(raw: String?): String {
        val msg = raw?.lowercase().orEmpty()
        return when {
            "broken" in msg || "lost" in msg || "reset" in msg ||
                "closed" in msg || "shutdown" in msg ->
                "a conexão com o PC caiu " +
                    "(rede mudou, OBS fechado ou IP do PC mudou)"

            "timed out" in msg || "timeout" in msg ->
                "o PC não respondeu a tempo " +
                    "(firewall, IP errado ou outra rede)"

            "refused" in msg ->
                "o PC recusou " +
                    "(listener do OBS parado ou porta errada)"

            "unreachable" in msg || "unroutable" in msg ||
                "no route" in msg ->
                "o PC está inalcançável (IP errado ou outra rede)"

            "unknown host" in msg || "resolve" in msg ->
                "não resolveu o endereço digitado"

            raw.isNullOrBlank() -> "sem detalhe do erro"
            else -> raw.trim()
        }
    }
}
