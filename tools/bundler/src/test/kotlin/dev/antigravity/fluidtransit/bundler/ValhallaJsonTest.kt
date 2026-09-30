package dev.antigravity.fluidtransit.bundler

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Le polilinee di Valhalla, lette senza regex.
 *
 * La regex di prima si ricorreva a ogni carattere e sulle tratte lunghe
 * finiva la pila: il 30/09/2026 2.346 tracce su 9.211 sono rimaste GPS per
 * questo, e il log dava la colpa al grafo. Il test che conta e' quello
 * lungo: con la regex di prima fallisce con uno StackOverflowError.
 */
class ValhallaJsonTest {

    @Test
    fun `le barre rovesciate protette da JSON tornano una sola`() {
        // Nel JSON la polilinea `a\b` arriva come `a\\b`.
        val json = """{"trip":{"legs":[{"shape":"ab\\cd"}]}}"""
        assertEquals(listOf("ab\\cd"), ValhallaJson.shapes(json))
    }

    @Test
    fun `una risposta con piu' tratti le da' tutte, nell'ordine`() {
        val json = """{"legs":[{"shape":"uno"},{"shape":"due"}]}"""
        assertEquals(listOf("uno", "due"), ValhallaJson.shapes(json))
    }

    @Test
    fun `una risposta senza polilinee non ne inventa`() {
        assertEquals(emptyList(), ValhallaJson.shapes("""{"error":"no path"}"""))
    }

    @Test
    fun `una polilinea di duecentomila caratteri non finisce la pila`() {
        // Caratteri veri di una polilinea (da '?' a '~'), con una barra
        // rovesciata ogni tanto: e' proprio l'alternanza che la regex non
        // reggeva.
        val pezzo = "_p~iF~ps|U_ulLnnqC_mqNvxq`@\\\\"
        val polilinea = pezzo.repeat(200_000 / pezzo.length)
        val json = "{\"shape\":\"$polilinea\"}"
        val out = ValhallaJson.shapes(json)
        assertEquals(1, out.size)
        assertEquals(polilinea.replace("\\\\", "\\"), out[0])
    }
}
