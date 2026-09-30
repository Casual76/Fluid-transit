package dev.antigravity.fluidtransit.data.routines

import android.content.Context
import dev.antigravity.fluidtransit.data.store.Durable
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Le routine ricorrenti, decise cosi': nascono dal dettaglio di un viaggio
 * ("Fanne una routine"), hanno i giorni della settimana, e ognuna sceglie
 * il suo ancoraggio — "arriva entro" o "parti alle". Nei giorni giusti
 * l'app ricalcola il viaggio coi ritardi live e manda "esci tra X minuti".
 *
 * `lastAdvice*` e' l'ultimo consiglio calcolato dalle sveglie: la scheda
 * Oggi lo mostra senza rifare il calcolo.
 */
class Routines(private val file: File) {

    /** Quello di tutti i giorni; l'altro prende il file, e si puo' provare. */
    constructor(context: Context) : this(File(context.filesDir, "routines.json"))


    class Routine(
        val id: Long,
        val label: String,
        val fromLat: Double,
        val fromLon: Double,
        val toLat: Double,
        val toLon: Double,
        val toName: String,
        /** Lunedi' = 1 ... Domenica = 7, come java.time.DayOfWeek. */
        val days: Set<Int>,
        val anchor: String, // "arrive" | "depart"
        /** Minuti dalla mezzanotte locale dell'orario di ancoraggio. */
        val anchorMinutes: Int,
        val enabled: Boolean,
        val lastAdviceEpoch: Long = 0, // quando USCIRE, epoch s (0 = nessuna uscita)
        val lastAdviceText: String = "",
        /**
         * Quando e' stato fatto l'ultimo calcolo, riuscito o no. Senza, "non
         * ancora calcolato" e "calcolato, e nessun bus utile" erano lo stesso
         * zero: vedi [RoutineText].
         */
        val lastComputeEpoch: Long = 0,
    )

    val version = MutableStateFlow(0)

    fun list(): List<Routine> = runCatching {
        if (!file.isFile) return emptyList()
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Routine(
                id = o.getLong("id"),
                label = o.optString("label"),
                fromLat = o.getDouble("fromLat"),
                fromLon = o.getDouble("fromLon"),
                toLat = o.getDouble("toLat"),
                toLon = o.getDouble("toLon"),
                toName = o.optString("toName"),
                days = o.getJSONArray("days").let { d -> (0 until d.length()).map { d.getInt(it) } }.toSet(),
                anchor = o.optString("anchor", "arrive"),
                anchorMinutes = o.getInt("anchorMinutes"),
                enabled = o.optBoolean("enabled", true),
                lastAdviceEpoch = o.optLong("adviceEpoch"),
                lastAdviceText = o.optString("adviceText"),
                lastComputeEpoch = o.optLong("computeEpoch"),
            )
        }
    }.getOrElse { emptyList() }

    // Sincronizzati: adesso l'archivio e' uno solo per tutta l'app (lo
    // scheduler scrive dal suo thread mentre Oggi scrive dal principale), e un
    // leggi-modifica-scrivi interrotto a meta' perderebbe l'una o l'altra.
    @Synchronized
    fun add(r: Routine) = write(list().filter { it.id != r.id } + r)

    @Synchronized
    fun remove(id: Long) = write(list().filter { it.id != id })

    @Synchronized
    fun update(id: Long, transform: (Routine) -> Routine) {
        write(list().map { if (it.id == id) transform(it) else it })
    }

    private fun write(routines: List<Routine>) {
        runCatching {
            val a = JSONArray()
            for (r in routines) {
                a.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("label", r.label)
                        .put("fromLat", r.fromLat)
                        .put("fromLon", r.fromLon)
                        .put("toLat", r.toLat)
                        .put("toLon", r.toLon)
                        .put("toName", r.toName)
                        .put("days", JSONArray().apply { r.days.sorted().forEach { put(it) } })
                        .put("anchor", r.anchor)
                        .put("anchorMinutes", r.anchorMinutes)
                        .put("enabled", r.enabled)
                        .put("adviceEpoch", r.lastAdviceEpoch)
                        .put("adviceText", r.lastAdviceText)
                        .put("computeEpoch", r.lastComputeEpoch),
                )
            }
            Durable.write(file, a.toString())
        }
        version.value++
        changes.update { it + 1 }
    }

    companion object {

        /**
         * Sale a ogni scrittura, da QUALUNQUE istanza.
         *
         * [version] e' di una sola istanza, e lo scheduler delle sveglie ne
         * apre di sue: quello che scrive il consiglio non faceva muovere il
         * `version` di `app.routines`, e chi ascolta quello — Oggi — non lo
         * sapeva. Il widget vive in una sessione Glance che resta aperta
         * tre quarti di minuto e ignora gli `updateAll` di quel tempo: senza
         * un segnale che arrivi da tutte le istanze, un consiglio appena
         * calcolato non si vedeva sulla home fino al giro dopo.
         */
        val changes = MutableStateFlow(0)

        /**
         * Quanto un consiglio resta buono dopo l'ora di uscire.
         *
         * Cinque minuti: chi guarda il telefono appena uscito di casa vuole
         * ancora vedere qual era il piano.
         */
        const val GRAZIA_CONSIGLIO_S = 5 * 60L

        /**
         * L'ora di una routine in un giorno dato, come la segna l'orologio.
         *
         * Si faceva in tre modi — mezzanotte piu' i minuti in secondi, e
         * `ZonedDateTime.plusMinutes` in due posti — e tutti e tre contano il
         * tempo trascorso, non l'ora scritta: nei due giorni del cambio
         * d'ora una routine delle 08:00 cadeva alle 07:00 o alle 09:00, e il
         * widget diceva "Per oggi e' andata" un'ora prima. `LocalDateTime`
         * somma sull'orologio, e con 1440 minuti (un arrivo arrotondato alla
         * mezzanotte) passa al giorno dopo invece di lanciare un'eccezione.
         */
        /** A che punto e' una routine oggi: vedi [adviceState]. */
        enum class AdviceState { NOT_YET, NO_BUS, GOOD, PASSED, DONE }

        /**
         * A che punto e' la routine del giorno [day], adesso.
         *
         * - GOOD: c'e' un consiglio e la sua ora di uscita non e' passata
         *   (con la grazia di [GRAZIA_CONSIGLIO_S]);
         * - NO_BUS: il calcolo di oggi c'e' stato e non ha trovato bus;
         * - PASSED: l'ora di uscire e' passata, l'ora della routine no;
         * - NOT_YET: prima dell'ora della routine, e niente ancora calcolato;
         * - DONE: l'ora della routine e' passata.
         */
        fun adviceState(r: Routine, day: java.time.LocalDate, nowEpoch: Long): AdviceState {
            val anchor = anchorEpoch(day, r.anchorMinutes)
            val calcolataOggi = RoutineTiming.adviceBelongsTo(r.lastComputeEpoch, anchor)
            if (adviceStillGood(r, nowEpoch) && RoutineTiming.adviceBelongsTo(r.lastAdviceEpoch, anchor)) {
                return AdviceState.GOOD
            }
            if (nowEpoch >= anchor) return AdviceState.DONE
            if (calcolataOggi && r.lastAdviceEpoch == 0L) return AdviceState.NO_BUS
            if (RoutineTiming.adviceBelongsTo(r.lastAdviceEpoch, anchor)) return AdviceState.PASSED
            return AdviceState.NOT_YET
        }

        /**
         * L'ora della prossima occorrenza: oggi se non e' ancora passata e
         * oggi e' uno dei suoi giorni, se no il prossimo giorno buono. E' il
         * viaggio che si apre toccando la routine; null senza giorni.
         */
        fun nextAnchorEpoch(r: Routine, nowEpoch: Long): Long? {
            val oggi = java.time.Instant.ofEpochSecond(nowEpoch)
                .atZone(dev.antigravity.fluidtransit.routing.Ftb.ROME).toLocalDate()
            for (offset in 0..7) {
                val day = oggi.plusDays(offset.toLong())
                if (day.dayOfWeek.value !in r.days) continue
                val a = anchorEpoch(day, r.anchorMinutes)
                if (a > nowEpoch) return a
            }
            return null
        }

        /** Dopo l'ancora, per quanto un tocco ancora "e' di oggi". */
        const val FINESTRA_TOCCO_S = 2 * 3600L

        /**
         * A che ora aprire il viaggio toccando una notifica o il widget.
         *
         * Con [day] (il giorno della routine che ha prodotto l'avviso) si
         * resta su quel giorno: una "parti alle 07:25" col bus in ritardo si
         * rifinisce alle 07:27, dopo l'ancora, e "la prossima occorrenza"
         * era il viaggio di domani, non il bus di cui parlava la notifica.
         * Per una "parti alle" gia' passata, da poco, si parte da adesso; per
         * un "arriva entro" passato non c'e' niente di oggi da mostrare.
         */
        fun tapEpoch(r: Routine, day: java.time.LocalDate?, nowEpoch: Long): Long? {
            if (day != null) {
                val a = anchorEpoch(day, r.anchorMinutes)
                if (a > nowEpoch) return a
                if (r.anchor == "depart" && nowEpoch <= a + FINESTRA_TOCCO_S) return nowEpoch
            }
            return nextAnchorEpoch(r, nowEpoch)
        }

        /** Il viaggio della routine, per aprirlo sulla mappa. */
        fun journeyIntent(
            r: Routine,
            nowEpoch: Long,
            day: java.time.LocalDate? = null,
        ): dev.antigravity.fluidtransit.ui.map.MapIntent.Journey {
            val at = tapEpoch(r, day, nowEpoch)
            return dev.antigravity.fluidtransit.ui.map.MapIntent.Journey(
                fromLat = r.fromLat,
                fromLon = r.fromLon,
                toLat = r.toLat,
                toLon = r.toLon,
                toName = r.toName.ifEmpty { r.label.ifEmpty { "Arrivo" } },
                timeMode = if (at == null) "now" else if (r.anchor == "arrive") "arrive" else "depart",
                timeEpoch = at ?: 0L,
            )
        }

        fun anchorEpoch(date: java.time.LocalDate, anchorMinutes: Int): Long =
            date.atStartOfDay().plusMinutes(anchorMinutes.toLong())
                .atZone(dev.antigravity.fluidtransit.routing.Ftb.ROME).toEpochSecond()

        /**
         * Il consiglio di oggi vale ancora?
         *
         * `lastAdviceEpoch` e' l'ora a cui USCIRE, non l'ora in cui il
         * consiglio e' stato calcolato, e chi lo leggeva come "e' di oggi"
         * lo teneva in vetrina fino a mezzanotte: alle 09:47 la scheda Oggi
         * e il widget dicevano tutti e due "Esci alle 07:25". Due copie
         * della stessa svista, quindi la regola sta qui.
         */
        fun adviceStillGood(routine: Routine, nowEpoch: Long): Boolean =
            routine.lastAdviceEpoch > 0 &&
                routine.lastAdviceText.isNotEmpty() &&
                nowEpoch <= routine.lastAdviceEpoch + GRAZIA_CONSIGLIO_S

        /**
         * La routine di oggi che conta adesso.
         *
         * Il widget prendeva la prima della lista, e con due routine nello
         * stesso giorno — andata la mattina, ritorno la sera — passata la
         * prima diceva "Per oggi e' andata" per tutto il resto della
         * giornata, anche con la seconda ancora da venire. L'ordine qui e'
         * quello di chi guarda: prima un consiglio ancora buono, poi la
         * prossima di oggi, e solo se sono passate tutte l'ultima.
         *
         * @param date oggi, nel fuso di Roma.
         */
        fun relevantToday(
            routines: List<Routine>,
            date: java.time.LocalDate,
            nowEpoch: Long,
        ): Routine? {
            val today = date.dayOfWeek.value
            val diOggi = routines.filter { it.enabled && today in it.days }
            fun ancora(r: Routine) = anchorEpoch(date, r.anchorMinutes)
            return diOggi.filter { adviceStillGood(it, nowEpoch) }.minByOrNull(::ancora)
                ?: diOggi.filter { nowEpoch < ancora(it) }.minByOrNull(::ancora)
                ?: diOggi.maxByOrNull(::ancora)
        }
    }
}
