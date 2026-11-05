package com.jarvis.assistant.license

/**
 * HOPE licence key format: HOPE-XXXXX-XXXXX-XXXXX-XXXXX
 * 20 symbols from the Crockford base32 alphabet (0-9 A-Z without I, L, O, U).
 * Like the server, we read O as 0 and I / L as 1 so pasting is forgiving.
 */
object LicenseKeyFormat {
    const val PREFIX = "HOPE"
    const val SYMBOL_COUNT = 20
    const val GROUP_SIZE = 5
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /**
     * Formats whatever the user typed or pasted into HOPE-XXXXX-XXXXX-XXXXX-XXXXX (as far as it goes).
     * Safe to call on every keystroke.
     */
    fun format(raw: String): String {
        val cleaned = raw.uppercase().filter { it in '0'..'9' || it in 'A'..'Z' }
        if (cleaned.isEmpty()) return ""
        // The user is still typing the "HOPE" prefix itself (H, HO, HOP).
        if (cleaned.length < PREFIX.length && PREFIX.startsWith(cleaned)) return cleaned

        val body = if (cleaned.startsWith(PREFIX)) cleaned.substring(PREFIX.length) else cleaned
        val symbols = body
            .map { c ->
                when (c) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> c
                }
            }
            .joinToString("")
            .take(SYMBOL_COUNT)
        val groups = symbols.chunked(GROUP_SIZE)
        return if (groups.isEmpty()) "$PREFIX-" else "$PREFIX-" + groups.joinToString("-")
    }

    /** The 20 key symbols of an already formatted key (no prefix, no dashes). */
    fun symbols(formatted: String): String = formatted.removePrefix("$PREFIX-").replace("-", "")

    /** True only for a complete key with 20 valid symbols. */
    fun isComplete(formatted: String): Boolean {
        if (!formatted.startsWith("$PREFIX-")) return false
        val symbols = symbols(formatted)
        return symbols.length == SYMBOL_COUNT && symbols.all { it in ALPHABET }
    }

    fun isValid(raw: String): Boolean = isComplete(format(raw))
}
