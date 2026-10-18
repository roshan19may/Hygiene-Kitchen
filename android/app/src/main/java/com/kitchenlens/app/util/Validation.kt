package com.kitchenlens.app.util

import java.text.SimpleDateFormat
import java.util.Locale

/** GST Identification Number (15 characters), e.g. 27AAPFU0939F1ZV. */
object Gstin {
    enum class Result { EMPTY, BAD_FORMAT, BAD_CHECKSUM, OK }

    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    // 2-digit state code + 10-character PAN + entity number + 'Z' + check character
    private val FORMAT = Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")
    private val IN_TEXT = Regex("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]")

    /** Uppercases and strips spaces so "29 abcde1234f1zw" is accepted. */
    fun normalize(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }.take(15)

    fun check(raw: String): Result {
        val g = normalize(raw)
        if (g.isEmpty()) return Result.EMPTY
        if (!FORMAT.matches(g)) return Result.BAD_FORMAT
        return if (g[14] == checkChar(g)) Result.OK else Result.BAD_CHECKSUM
    }

    /** Standard GSTIN check character (mod-36 weighted sum of the first 14 characters). */
    fun checkChar(g: String): Char {
        var sum = 0
        for (i in 0 until 14) {
            val p = CHARS.indexOf(g[i]) * (if (i % 2 == 0) 1 else 2)
            sum += p / 36 + p % 36
        }
        return CHARS[(36 - sum % 36) % 36]
    }

    /** First GSTIN-shaped text on a bill (used by the bill reader). */
    fun findIn(text: String): String? =
        IN_TEXT.findAll(text.uppercase().replace(" ", "")).map { it.value }
            .firstOrNull { check(it) == Result.OK }
            ?: IN_TEXT.find(text.uppercase().replace(" ", ""))?.value

    /** Message for the form, or null when the value can be saved. */
    fun formError(raw: String, required: Boolean): String? = when (check(raw)) {
        Result.EMPTY -> if (required) "Enter the GST number" else null
        Result.BAD_FORMAT -> "GST number must be 15 characters, like 29ABCDE1234F1ZW"
        else -> null
    }

    fun warning(raw: String): String? =
        if (check(raw) == Result.BAD_CHECKSUM) "The last character doesn't match. Please double-check the GST number." else null
}

object Dates {
    private fun fmt() = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).apply { isLenient = false }

    fun parse(text: String): Long? = runCatching { fmt().parse(text.trim())?.time }.getOrNull()

    /** The most recent valid date in the list, formatted dd-MM-yyyy. */
    fun latest(dates: List<String>): String? =
        dates.mapNotNull { d -> parse(d)?.let { d.trim() to it } }.maxByOrNull { it.second }?.first
}
