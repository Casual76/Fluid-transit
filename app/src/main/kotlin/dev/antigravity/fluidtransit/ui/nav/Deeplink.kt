package dev.antigravity.fluidtransit.ui.nav

/**
 * Gli indirizzi interni dell'app.
 *
 * Fino a ieri niente in Fluid Transit sapeva nominare un posto preciso. Il
 * widget della fermata apriva l'app e basta: toccavi "SODERINI 12 fra 3
 * minuti" e ti ritrovavi la Toscana intera a zoom 7,6, con la fermata da
 * ricercare a mano. La notifica delle routine non aveva nemmeno un
 * `contentIntent`: toccarla non faceva *assolutamente niente*, e quel nulla
 * e' peggio di un errore, perche' sembra che l'app si sia rotta. La notifica
 * della navigazione riapriva l'app, ma non la navigazione.
 *
 * Un deep link e' il nome di una schermata scritto in una riga di testo. Chi
 * sta fuori dal processo — un widget, una sveglia, una notifica, un domani
 * un link in un messaggio — puo' dire dove vuole andare senza conoscere i
 * pannelli, la camera o lo stato della mappa.
 *
 * Si legge e si scrive **senza `android.net.Uri`**: cosi' il parser e' una
 * funzione pura con i suoi test, e non serve Robolectric per provarlo.
 *
 * Il vocabolario, `fluidtransit://`:
 *
 * ```
 * stop/<idHashHex>?name=<nome>   una fermata
 * route/<idHashHex>              una linea
 * journey/<routineId>            il viaggio di una routine
 * nav                            la navigazione in corso
 * today                          la scheda Oggi
 * data-status                    lo stato dei dati
 * ```
 *
 * Gli hash sono quelli del bundle (`Ftb.hash64`, esadecimale senza `0x`):
 * sopravvivono al cambio notturno di bundle, mentre gli indici no.
 */
sealed interface Deeplink {

    class Stop(val idHashHex: String, val name: String) : Deeplink
    class Route(val idHashHex: String) : Deeplink
    class Journey(val routineId: Long) : Deeplink
    data object Nav : Deeplink
    data object Today : Deeplink
    data object DataStatus : Deeplink

    companion object {
        const val SCHEME = "fluidtransit"

        fun stop(idHashHex: String, name: String): String =
            "$SCHEME://stop/$idHashHex?name=" + encode(name)

        fun route(idHashHex: String): String = "$SCHEME://route/$idHashHex"

        fun journey(routineId: Long): String = "$SCHEME://journey/$routineId"

        fun nav(): String = "$SCHEME://nav"

        fun today(): String = "$SCHEME://today"

        fun dataStatus(): String = "$SCHEME://data-status"

        /**
         * Legge un indirizzo, o restituisce null.
         *
         * Null e' una risposta legittima e frequente: l'app si apre quasi
         * sempre dal launcher, senza dati nell'intent. E un indirizzo
         * malformato deve valere quanto nessun indirizzo — mai "apri
         * qualcosa di simile": aprire il pannello sbagliato e' esattamente
         * il genere di comportamento che fa dire "si comporta in modo
         * diverso ogni volta".
         */
        fun parse(uri: String?): Deeplink? {
            val raw = uri?.trim().orEmpty()
            val sep = raw.indexOf("://")
            // Lo schema per intero, non un suo prefisso: "fluid://" non e'
            // nostro, e nemmeno "fluidtransito://".
            if (sep != SCHEME.length) return null
            if (!raw.regionMatches(0, SCHEME, 0, sep, ignoreCase = true)) return null

            var rest = raw.substring(sep + 3)
            val queryAt = rest.indexOf('?')
            val query = if (queryAt >= 0) rest.substring(queryAt + 1) else ""
            if (queryAt >= 0) rest = rest.substring(0, queryAt)
            rest = rest.substringBefore('#').trim('/')

            val parts = rest.split('/')
            val host = parts[0].lowercase()
            val arg = parts.getOrNull(1).orEmpty()

            return when (host) {
                "stop" -> hex(arg)?.let { Stop(it, param(query, "name")) }
                "route" -> hex(arg)?.let { Route(it) }
                "journey" -> arg.toLongOrNull()?.let { Journey(it) }
                "nav" -> Nav
                "today" -> Today
                "data-status" -> DataStatus
                else -> null
            }
        }

        /**
         * Un hash a 64 bit in esadecimale, o niente.
         *
         * Il controllo non e' pedanteria: `findStopByIdHash` di un hash
         * inventato restituisce -1 e il pannello si apre vuoto, senza nome
         * e senza orari. Meglio non aprirlo.
         */
        private fun hex(s: String): String? {
            if (s.isEmpty() || s.length > 16) return null
            for (c in s) {
                val ok = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
                if (!ok) return null
            }
            return s.lowercase()
        }

        /** Il valore di un parametro della query, gia' decodificato. */
        private fun param(query: String, key: String): String {
            if (query.isEmpty()) return ""
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                if (eq < 0) continue
                if (pair.substring(0, eq) == key) return decode(pair.substring(eq + 1))
            }
            return ""
        }

        private const val UNRESERVED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

        private fun encode(s: String): String {
            val out = StringBuilder(s.length + 8)
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                if (c in UNRESERVED) {
                    out.append(c)
                } else {
                    out.append('%').append("%02X".format(b.toInt() and 0xff))
                }
            }
            return out.toString()
        }

        /**
         * Decodifica `%XX`, e lascia stare tutto il resto.
         *
         * In particolare `+` resta un piu': e' la convenzione dei form HTML,
         * non degli URI, e qui a scrivere gli indirizzi siamo noi. Una
         * tripletta monca (`%A` a fine stringa, `%ZZ`) non fa saltare
         * niente: si tiene il testo com'e'.
         */
        private fun decode(s: String): String {
            if ('%' !in s) return s
            val bytes = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 3 <= s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) {
                        bytes.add(v.toByte())
                        i += 3
                        continue
                    }
                }
                for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes.add(b)
                i++
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }
}
