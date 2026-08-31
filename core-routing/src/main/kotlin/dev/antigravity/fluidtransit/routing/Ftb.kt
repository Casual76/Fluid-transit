package dev.antigravity.fluidtransit.routing

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Layout del container `.ftb`, formato 2.
 *
 * Il file e' pensato per essere aperto in mmap: l'apertura e' a tempo
 * costante e la residenza in memoria la decide la page cache del kernel, non
 * l'heap ART. Da qui discendono i vincoli del formato:
 *
 *  - tutto little-endian, come ogni ARM e x86 su cui girera';
 *  - ogni sezione allineata a 4096 byte, cioe' alla pagina, cosi' che
 *    toccare una sezione non ne faccia entrare un'altra per sbaglio;
 *  - nessun offset relativo: ogni indice e' assoluto dentro la sua sezione,
 *    percio' una sezione si puo' leggere senza averne caricate altre;
 *  - CRC32 per sezione, non per file: un bundle corrotto va scoperto
 *    all'apertura della sezione, non dopo aver letto 15 MB.
 *
 * Il build deve essere byte-deterministico: nessun timestamp finisce nelle
 * sezioni e ogni ordinamento e' stabile e definito. Serve gia' oggi per
 * "pubblica solo se cambiato", e domani abilita i patch differenziali senza
 * toccare il formato.
 *
 * ## Cosa e' cambiato dalla versione 1 (lo spike)
 *
 * La v1 conservava i `trip_id` in chiaro (1,56 MB, il 12% del download) e
 * buttava via tutti gli altri identificatori del feed. Era il contrario di
 * quel che serve: il realtime aggancia per hash, non per stringa, mentre il
 * matcher secondario e i preferiti hanno bisogno dell'identita' di linee e
 * fermate, che la v1 non aveva.
 *
 *  - TRIPS perde il campo `tripIdStr` (20 -> 16 byte): il feed realtime si
 *    aggancia tramite TRIP_ID_INDEX, che lavora su hash.
 *  - STOPS guadagna `stopIdHash` (20 -> 28 byte) e nasce STOP_ID_INDEX: i
 *    preferiti sopravvivono al bundle di domani solo se ricordano una chiave
 *    del feed, non un indice interno che ogni notte cambia.
 *  - ROUTES guadagna `routeIdHash` e il colore diventa leggibile: e' cio' che
 *    serve al matcher secondario `(route_id, start_time, direction_id)`
 *    quando i trip_id del realtime non risolvono.
 *
 * Gli hash sono FNV-1a a 64 bit. Il builder verifica a ogni build che non ci
 * siano collisioni fra gli id reali (misurato: zero su 213.583 trip_id) e
 * fallisce il gate notturno se mai ne comparisse una: per questo il lettore
 * puo' fidarsi dell'uguaglianza degli hash senza conservare le stringhe.
 */
object Ftb {

    /** "FTB1" in little-endian. */
    const val MAGIC = 0x31425446

    /**
     * Cambia solo quando il layout non e' piu' leggibile da un reader vecchio.
     * v3: ROUTES guadagna il colore di visualizzazione assegnato dal bundler
     * (la colorazione del grafo di sovrapposizione) - lo stesso colore che
     * l'overlay scrive nelle tile, cosi' mappa e schede dicono la stessa cosa.
     */
    const val FORMAT_VERSION = 3

    const val HEADER_SIZE = 4096
    const val SECTION_ALIGN = 4096
    const val SECTION_ENTRY_SIZE = 24

    // --- offset dentro l'header -------------------------------------------
    const val OFF_MAGIC = 0
    const val OFF_FORMAT_VERSION = 4
    const val OFF_SECTION_COUNT = 6
    const val OFF_BUILD_ID = 8
    const val OFF_FEED_START = 16
    const val OFF_FEED_END = 20
    const val OFF_DAY_COUNT = 24

    /**
     * Ultimo istante raggiunto da una corsa, in secondi dall'inizio del giorno
     * di servizio. Dice quanto indietro deve guardare una query: una costante
     * scelta a occhio fa sparire le corse notturne piu' lunghe, e in questo
     * feed una corsa arriva a 30:10 - le 06:10 del mattino dopo, ancora
     * dentro il giorno di servizio precedente.
     */
    const val OFF_MAX_TRIP_END = 28
    const val OFF_SECTION_TABLE = 32

    // --- identificatori di sezione ----------------------------------------
    const val S_STRINGS = 1
    const val S_STOPS = 2
    const val S_STOP_GRID = 3
    const val S_ROUTES = 4
    const val S_PATTERNS = 5
    const val S_PATTERN_STOPS = 6
    const val S_TRIPS = 7
    const val S_PROFILES = 8
    const val S_DWELL = 9
    const val S_TRIP_ID_INDEX = 10
    const val S_STOP_PATTERNS = 11
    const val S_SERVICES = 12
    const val S_TRANSFERS = 13
    const val S_POLYLINES = 14 // riservata: arriva con l'anteprima itinerari
    const val S_SEARCH = 15 // riservata: arriva con la ricerca
    const val S_STOP_ID_INDEX = 16

    val SECTION_NAMES = mapOf(
        S_STRINGS to "STRINGS",
        S_STOPS to "STOPS",
        S_STOP_GRID to "STOP_GRID",
        S_ROUTES to "ROUTES",
        S_PATTERNS to "PATTERNS",
        S_PATTERN_STOPS to "PATTERN_STOPS",
        S_TRIPS to "TRIPS",
        S_PROFILES to "PROFILES",
        S_DWELL to "DWELL",
        S_TRIP_ID_INDEX to "TRIP_ID_INDEX",
        S_STOP_PATTERNS to "STOP_PATTERNS",
        S_SERVICES to "SERVICES",
        S_TRANSFERS to "TRANSFERS",
        S_POLYLINES to "POLYLINES",
        S_SEARCH to "SEARCH",
        S_STOP_ID_INDEX to "STOP_ID_INDEX",
    )

    // --- dimensioni dei record --------------------------------------------
    /** lat i32 · lon i32 · nameIdx i32 · codeIdx i32 · parent i32 · idHash i64. */
    const val STOP_RECORD = 28

    /** shortName i32 · longName i32 · agency i32 · type i32 · colorFeed i32 · colorDisplay i32 · idHash i64. */
    const val ROUTE_RECORD = 32

    /** route i32 · firstStop i32 · stopCount u16 · dir u8 · pad u8 · firstTrip i32 · tripCount i32. */
    const val PATTERN_RECORD = 20

    /** pattern i32 · service u16 · pad u16 · dep0 i32 · profile i32. */
    const val TRIP_RECORD = 16

    /** targetStop i32 · seconds u16 · pad u16. Voce del CSR di TRANSFERS. */
    const val TRANSFER_RECORD = 8

    /**
     * Le coordinate sono i32 in milionesimi di grado. Il feed pubblica sei
     * decimali, quindi la conversione e' esatta e non c'e' arrotondamento da
     * documentare: 11 cm di risoluzione, piu' che sufficienti per fermate.
     */
    const val COORD_SCALE = 1_000_000.0

    /** Lato della cella di STOP_GRID, in gradi. Circa 1,1 km in latitudine. */
    const val GRID_DEGREES = 0.01

    val ROME: ZoneId = ZoneId.of("Europe/Rome")

    /**
     * Istante di inizio del giorno di servizio [date].
     *
     * Non e' la mezzanotte locale, ed e' il punto in cui questa categoria di
     * app sbaglia piu' spesso. GTFS definisce i tempi come scostamenti da
     * "mezzogiorno meno dodici ore": nel giorno in cui l'ora legale finisce,
     * la mezzanotte locale dista 25 ore dalla successiva, e prendere la
     * mezzanotte come origine sposta di un'ora tutte le corse dopo le 03:00.
     * L'ultima domenica di ottobre cade dentro ogni finestra di validita' del
     * feed, quindi non e' un caso raro: e' un caso annuale garantito.
     *
     * La trappola dentro la trappola: il giorno di servizio da 25 ore e'
     * quello *precedente* al ritorno all'ora solare, perche' l'ora ripetuta
     * cade nella sua estensione 24:00-27:00. Nel 2026 e' il 24 ottobre, non
     * il 25 - il 25 e' un giorno normale di 24 ore.
     */
    fun serviceDayStart(date: LocalDate, zone: ZoneId = ROME): Instant =
        date.atTime(LocalTime.NOON).atZone(zone).minusHours(12).toInstant()

    /** Durata reale del giorno di servizio in secondi: 23, 24 o 25 ore. */
    fun serviceDayLength(date: LocalDate, zone: ZoneId = ROME): Long =
        serviceDayStart(date.plusDays(1), zone).epochSecond - serviceDayStart(date, zone).epochSecond

    /** `20260830` -> LocalDate. Il formato delle date GTFS non ha separatori. */
    fun parseGtfsDate(yyyymmdd: Int): LocalDate =
        LocalDate.of(yyyymmdd / 10000, (yyyymmdd / 100) % 100, yyyymmdd % 100)

    fun formatGtfsDate(date: LocalDate): Int =
        date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

    /**
     * Indice di Hilbert a 16 livelli. Ordinare le fermate lungo la curva
     * mette vicine nel file le fermate vicine sulla mappa: una query "cosa
     * c'e' intorno" tocca poche pagine invece di spargersi su tutta la
     * sezione.
     */
    fun hilbertD(xIn: Int, yIn: Int, order: Int = 16): Long {
        var x = xIn
        var y = yIn
        var rx: Int
        var ry: Int
        var d = 0L
        var s = 1 shl (order - 1)
        while (s > 0) {
            rx = if ((x and s) > 0) 1 else 0
            ry = if ((y and s) > 0) 1 else 0
            d += s.toLong() * s.toLong() * ((3 * rx) xor ry)
            // rotazione del quadrante
            if (ry == 0) {
                if (rx == 1) {
                    x = s - 1 - x
                    y = s - 1 - y
                }
                val t = x
                x = y
                y = t
            }
            s /= 2
        }
        return d
    }

    // Le costanti FNV vivono qui e non nel parser CSV del bundler: l'hash e'
    // parte del *formato* (e' la chiave con cui realtime e preferiti
    // agganciano il bundle), il parser e' solo uno dei posti che lo calcola.
    const val FNV_OFFSET = -0x340d631b7bdddcdbL // 14695981039346656037
    const val FNV_PRIME = 0x100000001b3L // 1099511628211

    /** Hash FNV-1a a 64 bit di un identificatore del feed. */
    fun hash64(s: String): Long = hash64(s.toByteArray(Charsets.UTF_8))

    fun hash64(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Long {
        var h = FNV_OFFSET
        for (i in from until to) {
            h = h xor (bytes[i].toLong() and 0xff)
            h *= FNV_PRIME
        }
        return h
    }
}
