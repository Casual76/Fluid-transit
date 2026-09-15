package dev.antigravity.fluidtransit.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Le banchine della stessa fermata, riunite.
 *
 * I due errori da non fare sono opposti e tutti e due visibili: tenere
 * separate due righe identiche (e allora l'utente tira a indovinare), oppure
 * riunire due paesi omonimi a cento chilometri (e allora il tabellone mostra
 * bus che non passeranno mai di li').
 */
class StopGroupsTest {

    private val lat0 = 43.5
    private val lon0 = 11.0
    private val dLat = 1.0 / 110_540.0

    /** Una rete di prova: nomi e posizioni, in metri verso nord da lat0. */
    private class Rete(val nomi: List<String>, val metri: List<Double>)

    private fun build(rete: Rete) = StopGroups.build(
        count = rete.nomi.size,
        name = { rete.nomi[it] },
        lat = { lat0 + dLat * rete.metri[it] },
        lon = { lon0 },
    )

    @Test
    fun `due banchine vicine con lo stesso nome sono una fermata`() {
        val g = build(Rete(listOf("TORRE GALLI", "TORRE GALLI"), listOf(0.0, 40.0)))

        assertEquals(1, g.size)
        assertEquals(g.groupOf(0), g.groupOf(1))
        assertTrue(g.siblings(0).contentEquals(intArrayOf(0, 1)))
    }

    @Test
    fun `due paesi omonimi lontani restano due fermate`() {
        // "SAN MARTINO" quindici volte in centosettantacinque chilometri sono
        // quindici paesi diversi.
        val g = build(Rete(listOf("SAN MARTINO", "SAN MARTINO"), listOf(0.0, 90_000.0)))

        assertEquals(2, g.size)
        assertTrue(g.groupOf(0) != g.groupOf(1))
        assertTrue(g.siblings(0).contentEquals(intArrayOf(0)))
    }

    @Test
    fun `nomi diversi non si toccano, per quanto vicini`() {
        val g = build(Rete(listOf("Via Roma", "Via Milano"), listOf(0.0, 5.0)))

        assertEquals(2, g.size)
        assertTrue(g.groupOf(0) != g.groupOf(1))
    }

    @Test
    fun `il nome si confronta come lo confronta la ricerca`() {
        // Maiuscole e accenti non fanno due fermate: sono lo stesso nome
        // scritto in due modi, e succede davvero nei feed.
        val g = build(Rete(listOf("Citta'", "CITTA'"), listOf(0.0, 30.0)))
        assertEquals(1, g.size)
    }

    @Test
    fun `una fila lungo una statale non si incolla tutta insieme`() {
        // Ogni fermata dista cento metri dalla successiva: senza il tetto sul
        // diametro si incollerebbero saltando di vicino in vicino, e "Strada
        // Statale 324" diventerebbe un'unica fermata lunga mezzo chilometro.
        val g = build(
            Rete(
                List(6) { "Strada Statale 324" },
                listOf(0.0, 100.0, 200.0, 300.0, 400.0, 500.0),
            ),
        )

        assertTrue(g.size > 1, "si sono incollate tutte in una: ${g.size} gruppi")
        for (i in 0 until 6) {
            val members = g.siblings(i)
            val spread = members.maxOf { m -> members.minOf { n -> kotlin.math.abs(m - n) } }
            assertTrue(spread >= 0)
            assertTrue(
                members.size <= 4,
                "gruppo da ${members.size} fermate: il tetto sul diametro non tiene",
            )
        }
    }

    @Test
    fun `una fermata sola e' un gruppo di una`() {
        val g = build(Rete(listOf("Unica"), listOf(0.0)))

        assertEquals(1, g.size)
        assertTrue(g.siblings(0).contentEquals(intArrayOf(0)))
    }

    @Test
    fun `ogni fermata sta in esattamente un gruppo`() {
        val nomi = listOf("A", "A", "A", "B", "B", "C", "A")
        val metri = listOf(0.0, 50.0, 100.0, 0.0, 10_000.0, 0.0, 200_000.0)
        val g = build(Rete(nomi, metri))

        val visti = HashSet<Int>()
        for (group in 0 until g.size) {
            for (stop in g.members(group)) {
                assertTrue(visti.add(stop), "la fermata $stop sta in due gruppi")
                assertEquals(group, g.groupOf(stop))
            }
        }
        assertEquals(nomi.size, visti.size, "qualche fermata non sta in nessun gruppo")
    }

    @Test
    fun `un indice fuori scala non fa saltare niente`() {
        val g = build(Rete(listOf("A"), listOf(0.0)))

        assertEquals(-1, g.groupOf(99))
        assertEquals(0, g.members(99).size)
        assertEquals(0, g.siblings(99).size)
    }

    @Test
    fun `sulla rete di prova le fermate in fila non si fondono`() {
        // A, B e C stanno a un chilometro l'una dall'altra e hanno nomi
        // diversi: nessun raggruppamento, ed e' giusto cosi'.
        val tmp = ArrayList<java.io.File>()
        try {
            BundleReader(TestBundle.write(tmp)).use { r ->
                val g = StopGroups.build(r)
                assertEquals(r.stopCount, g.size)
            }
        } finally {
            tmp.forEach { it.delete() }
        }
    }
}
