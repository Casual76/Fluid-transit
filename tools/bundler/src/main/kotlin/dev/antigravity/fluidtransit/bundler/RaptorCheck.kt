package dev.antigravity.fluidtransit.bundler

import dev.antigravity.fluidtransit.routing.BundleReader
import dev.antigravity.fluidtransit.routing.Certainty
import dev.antigravity.fluidtransit.routing.LiveTimes
import dev.antigravity.fluidtransit.routing.Raptor
import java.io.File
import java.time.Instant
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Gli invarianti del motore dei viaggi, sul bundle vero.
 *
 * I test di `:core-routing` girano su una rete di prova con quattro fermate:
 * dicono che il motore fa la cosa giusta quando la rete e' quella. Non
 * possono dire niente su trentaquattromila fermate, duecentottantasettemila
 * corse e le geometrie che il job notturno ha appena costruito — ed e'
 * proprio li' che un bundle sbagliato si vede: un profilo con gli offset
 * fuori ordine, un pattern senza fermate, un servizio che non copre nessun
 * giorno.
 *
 * Qui si pianificano centinaia di viaggi su coppie di fermate a caso e si
 * controllano le regole che devono valere SEMPRE, qualunque cosa il feed
 * abbia pubblicato stanotte:
 *
 * - nessun viaggio parte prima di adesso;
 * - nessuna tappa finisce prima di cominciare;
 * - due tappe di fila non si sovrappongono;
 * - la stessa proposta non compare due volte;
 * - i viaggi in bus escono in ordine di partenza.
 *
 * I ritardi sono finti e ostili di proposito — salgono e scendono lungo la
 * stessa corsa, cosa che il feed vero fa di rado ma fa — perche' da quando
 * il motore usa le previsioni fermata per fermata la monotonia lungo la
 * corsa non e' piu' garantita dai dati: e' garantita dal codice, e questo e'
 * il posto dove chiederglielo su scala vera.
 *
 * Uso: `bundler raptorcheck <bundle.ftb> [coppie]`
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("uso: raptorcheck <bundle.ftb> [coppie]")
        exitProcess(2)
    }
    val coppie = args.getOrNull(1)?.toIntOrNull() ?: 60
    val file = File(args[0])
    require(file.isFile) { "non e' un file: $file" }

    BundleReader(file).use { reader ->
        val raptor = Raptor(reader)
        val rnd = Random(20260916)
        val now = Instant.now()

        // Le previsioni, e ostili.
        //
        // Coprono TUTTE le corse, non una su cento come prima: con una su
        // cento un viaggio a caso non ne incontrava quasi mai una, e gli
        // invarianti che riguardano i ritardi — la monotonia lungo la corsa,
        // l'assenza di buchi fra una tratta e la camminata che segue — si
        // controllavano quasi solo sugli orari di tabella, cioe' proprio nel
        // caso in cui non possono rompersi.
        val live = object : LiveTimes {
            override fun at(
                tripIndex: Int,
                position: Int,
                stopCount: Int,
                nowEpoch: Long,
            ): LiveTimes.At {
                val d = ((tripIndex * 31L + position * 137L) % 1200 - 300).toInt()
                return LiveTimes.At(d, Certainty.DECLARED)
            }

            override fun covers(tripIndex: Int) = true
        }
        val rt = Raptor.Realtime(
            delayByTrip = emptyMap(),
            observedAtEpoch = now.epochSecond,
            live = live,
        )

        var viaggi = 0
        var conBus = 0
        val problemi = ArrayList<String>()
        repeat(coppie) { giro ->
            val a = rnd.nextInt(reader.stopCount)
            val b = rnd.nextInt(reader.stopCount)
            val da = Raptor.Place(reader.stopLat(a), reader.stopLon(a))
            val a2 = Raptor.Place(reader.stopLat(b), reader.stopLon(b))
            val js = raptor.plan(da, a2, now, rt)
            val firme = HashSet<String>()
            var partenzaPrecedente = Long.MIN_VALUE
            for (j in js) {
                viaggi++
                if (!j.isWalkOnly) conBus++
                if (j.departure.epochSecond < now.epochSecond - 1) {
                    problemi.add("giro $giro: parte prima di adesso")
                }
                if (j.durationSeconds < 0) problemi.add("giro $giro: durata negativa")
                for ((k, l) in j.legs.withIndex()) {
                    if (l.arrival < l.departure) problemi.add("giro $giro: tappa $k al contrario")
                    if (k > 0 && l.departure < j.legs[k - 1].arrival) {
                        problemi.add("giro $giro: tappe $k e ${k - 1} sovrapposte")
                    }
                    // Dopo una tratta si cammina SUBITO.
                    //
                    // Aspettare ha senso prima di salire su un bus, non dopo
                    // essere scesi: un buco fra l'arrivo e la camminata che
                    // segue e' tempo che il viaggio si prende senza dire
                    // perche'. Sul telefono si vedeva cosi': "10:36
                    // PANCIATICHI TRE PIETRE" e sotto "Cammina 2 min fino a
                    // destinazione — 10:40", con quattro minuti in mezzo che
                    // nessuna riga spiegava.
                    if (k > 0 && l is Raptor.Leg.Walk && j.legs[k - 1] is Raptor.Leg.Ride) {
                        val buco = l.departure.epochSecond - j.legs[k - 1].arrival.epochSecond
                        if (buco != 0L) {
                            problemi.add("giro $giro: ${buco}s fermi fra la discesa e la camminata")
                        }
                    }
                }
                val firma = j.legs.joinToString("|") {
                    "${it.departure.epochSecond}-${it.arrival.epochSecond}"
                }
                if (!firme.add(firma)) problemi.add("giro $giro: viaggio ripetuto")
                if (!j.isWalkOnly) {
                    if (j.departure.epochSecond < partenzaPrecedente) {
                        problemi.add("giro $giro: viaggi fuori ordine")
                    }
                    partenzaPrecedente = j.departure.epochSecond
                }
            }
        }

        println("raptorcheck: $coppie coppie, $viaggi viaggi ($conBus in bus)")
        if (problemi.isEmpty()) {
            println("TUTTI GLI INVARIANTI PASSATI")
        } else {
            problemi.take(20).forEach { System.err.println("  $it") }
            System.err.println("invarianti violati: ${problemi.size}")
            exitProcess(1)
        }
    }
}
