package com.jarvis.assistant.license

/**
 * Small strict JSON parser (objects, arrays, strings, numbers, booleans, null) used for the licence
 * API responses, the public-key JWK set and JWT headers/payloads. It has no Android dependencies, so
 * it is unit-tested on the JVM. Objects become Map<String, Any?>, arrays List<Any?>, integers Long,
 * other numbers Double.
 */
internal object MiniJson {
    private const val MAX_DEPTH = 24

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue(0)
        parser.skipWhitespace()
        require(parser.atEnd()) { "Unexpected trailing characters in JSON." }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?>? = try {
        parse(text) as? Map<String, Any?>
    } catch (error: Exception) {
        null
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun readValue(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "JSON is nested too deeply." }
            require(i < s.length) { "Unexpected end of JSON." }
            return when (val c = s[i]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else throw IllegalArgumentException("Unexpected character '$c' in JSON.")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "Invalid JSON literal." }
            i += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skipWhitespace()
            if (peek() == '}') {
                i++
                return map
            }
            while (true) {
                skipWhitespace()
                require(peek() == '"') { "Expected a string key in JSON object." }
                val key = readString()
                skipWhitespace()
                require(peek() == ':') { "Expected ':' in JSON object." }
                i++
                skipWhitespace()
                map[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' in JSON object.")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // [
            skipWhitespace()
            if (peek() == ']') {
                i++
                return list
            }
            while (true) {
                skipWhitespace()
                list.add(readValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' in JSON array.")
                }
            }
        }

        private fun readString(): String {
            i++ // opening quote
            val out = StringBuilder()
            while (true) {
                require(i < s.length) { "Unterminated JSON string." }
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        require(i < s.length) { "Bad escape in JSON string." }
                        when (val e = s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "Bad unicode escape in JSON string." }
                                out.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape '\\$e' in JSON string.")
                        }
                    }
                    c < ' ' -> throw IllegalArgumentException("Control character in JSON string.")
                    else -> out.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = i
            while (i < s.length && (s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] in '0'..'9')) i++
            val text = s.substring(start, i)
            return if (text.any { it == '.' || it == 'e' || it == 'E' }) {
                text.toDouble()
            } else {
                text.toLongOrNull() ?: text.toDouble()
            }
        }

        private fun peek(): Char {
            require(i < s.length) { "Unexpected end of JSON." }
            return s[i]
        }
    }
}
