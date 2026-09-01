package dev.antigravity.fluidtransit.bundler

/**
 * Parser JSON minimo per il geojsonseq di osmium: una riga = una Feature.
 * Ricorsivo-discendente, zero dipendenze — nel bundler i parser si scrivono
 * (CSV, protobuf) perche' portarsi una libreria per leggere quattro campi
 * costa piu' del parser.
 *
 * Ritorna Map/List/String/Double/Boolean/null. Numeri sempre Double.
 */
object Json {

    fun parse(s: String): Any? {
        val p = P(s)
        p.ws()
        val v = p.value()
        return v
    }

    private class P(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }

        fun obj(): Map<String, Any?> {
            val out = HashMap<String, Any?>()
            i++ // {
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws()
                val key = str()
                ws()
                i++ // :
                out[key] = value()
                ws()
                if (s[i] == ',') { i++; continue }
                i++ // }
                return out
            }
        }

        fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                ws()
                if (s[i] == ',') { i++; continue }
                i++ // ]
                return out
            }
        }

        fun str(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append(12.toChar()) // form feed
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}
