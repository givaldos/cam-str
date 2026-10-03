package com.camsrt.obs

/**
 * Estado dos botões Iniciar/Parar conforme o estado da conexão.
 * Puro e sem Android, para ter teste de unidade simples.
 */
internal object StreamButtons {

    data class State(
        val startEnabled: Boolean,
        val stopEnabled: Boolean,
        /** Texto do botão de parada: "Parar" ou "Cancelar". */
        val stopText: String
    )

    const val STOP = "Parar"
    const val CANCEL = "Cancelar"

    /**
     * isStreaming: transmitindo; connecting: tentativa de conexão em
     * curso; retryPending: aguardando a próxima tentativa de reconexão.
     * Conectando ou em retry, o botão vira Cancelar habilitado: antes
     * ficava tudo desabilitado e não dava para interromper o retry.
     */
    fun resolve(isStreaming: Boolean, connecting: Boolean, retryPending: Boolean): State {
        if (isStreaming) {
            return State(startEnabled = false, stopEnabled = true, stopText = STOP)
        }
        if (connecting || retryPending) {
            return State(startEnabled = false, stopEnabled = true, stopText = CANCEL)
        }
        return State(startEnabled = true, stopEnabled = false, stopText = STOP)
    }
}
