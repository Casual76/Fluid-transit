package dev.antigravity.fluidtransit.data.net

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * La rete di adesso e' a consumo? Tre risposte, non due.
 *
 * `ConnectivityManager.isActiveNetworkMetered` ne da' due, e sulla terza —
 * "non c'e' ancora una rete per quest'app" — risponde `true`. E' proprio il
 * caso dell'avvio: nell'`onCreate` dell'Application il processo non e'
 * ancora in primo piano, e il sistema tiene la rete chiusa al suo uid finche'
 * non lo diventa. Visto su un Galaxy S25 collegato a un Wi-Fi non a consumo:
 * due avvii su tre la schermata di benvenuto diceva "Sei su rete mobile" e
 * chiedeva il permesso di scaricare sei mega, e il terzo scaricava da solo.
 * Con la stessa domanda sbagliata il controllo notturno in sottofondo e i
 * luoghi saltavano il giro.
 */
object Metered {

    /**
     * true a consumo, false no, null se una rete per noi ancora non c'e'.
     *
     * `activeNetwork` e' null anche quando la rete c'e' ma e' ancora chiusa a
     * quest'app, ed e' la ragione per cui si chiede a lui e non alle
     * callback: una callback di rete arriva anche per una rete bloccata, e
     * la prima versione di [await] si fidava di quella — il download partiva
     * subito e moriva su "Unable to resolve host github.com".
     */
    fun now(cm: ConnectivityManager): Boolean? {
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        return isMetered(caps)
    }

    /**
     * Come [now], ma se la risposta e' "non lo so" aspetta che la rete si
     * apra, fino a [timeoutMs]. Null anche dopo: niente rete davvero, e chi
     * chiama decide — il download provera' e dira' perche' non riesce, che
     * e' vero, invece di chiedere il permesso per una rete mobile che non
     * c'e'.
     *
     * Chiede di nuovo ogni due decimi di secondo: la rete si apre quando il
     * processo passa in primo piano, cioe' fra una frazione di secondo e
     * qualche secondo, e non c'e' una callback che dica "adesso e' aperta
     * per te" su tutte le versioni di Android che l'app supporta.
     */
    suspend fun await(cm: ConnectivityManager, timeoutMs: Long = WAIT_MS): Boolean? {
        now(cm)?.let { return it }
        return withTimeoutOrNull(timeoutMs) {
            var risposta = now(cm)
            while (risposta == null) {
                delay(POLL_MS)
                risposta = now(cm)
            }
            risposta
        }
    }

    fun isMetered(caps: NetworkCapabilities): Boolean {
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
        // Un Wi-Fi a consumo che l'operatore apre per un po' (e il 5G "senza
        // limiti" di qualcuno): per quel tempo e' come se non lo fosse.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        ) {
            return false
        }
        return true
    }

    /**
     * Quanto si aspetta la rete all'avvio: il tempo che il sistema ci mette
     * ad aprirla a un processo appena nato, non quello di un download.
     */
    private const val WAIT_MS = 5_000L
    private const val POLL_MS = 200L
}
