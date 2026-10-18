package com.kitchenlens.app.util

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

/**
 * Demo mode has no cloud storage, so the order's details are packed INTO the QR
 * link itself (compressed). The scan page can then show the real order data
 * (kitchen, order number, temperature, score, hygiene timeline) without a server.
 *
 * Photos and videos are far too large for a QR code (a 20 s clip is ~4 MB; a QR
 * holds under 3 KB), so they appear only once Firebase cloud storage is connected.
 */
object OfflineSeal {

    /** Scan link for [code], with the order packed into a "d" parameter. */
    fun url(base: String, code: String, order: Map<String, Any?>): String =
        base + code + (if (base.contains("?")) "&" else "?") + "d=" + payload(order)

    /** "z" + base64url(deflate(json)). */
    fun payload(order: Map<String, Any?>): String {
        val json = compactJson(order).toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(json)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return "z" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** Short-key JSON so the QR stays small enough to print on a 58 mm label. */
    fun compactJson(o: Map<String, Any?>): String {
        val packedAt = (o["packedAtMillis"] as? Number)?.toLong() ?: 0L
        val snap: Map<*, *> = o["snapshot"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val rows = snap.entries.mapNotNull { (k, v) ->
            val key = k as? String ?: return@mapNotNull null
            val e = v as? Map<*, *> ?: return@mapNotNull null
            val at = (e["at"] as? Number)?.toLong() ?: return@mapNotNull null
            val d: Map<*, *> = e["details"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val code = when {
                key == "UTENSILS" -> "U"
                key == "STAFF" -> "S"
                key == "PEST" -> "P"
                key.startsWith("INV_") -> "I" + ((d["category"] as? String) ?: key.removePrefix("INV_"))
                else -> return@mapNotNull null
            }
            Triple(code, (packedAt - at) / 60_000, rowDetail(code, d))
        }.sortedBy { it.second }

        val sb = StringBuilder("{")
        fun field(name: String, value: Any?) {
            if (value == null || (value is String && value.isBlank())) return
            if (sb.length > 1) sb.append(',')
            sb.append('"').append(name).append("\":")
            when (value) {
                is Number -> sb.append(value)
                is Boolean -> sb.append(if (value) 1 else 0)
                else -> sb.append(str(value.toString()))
            }
        }
        field("v", 1)
        field("k", o["kitchenName"])
        field("a", listOf(o["kitchenLocality"], o["kitchenCity"]).filterIsInstance<String>().filter { it.isNotBlank() }.joinToString(", "))
        field("f", o["fssai"])
        field("g", o["gstin"])
        field("p", o["platform"])
        field("o", o["orderRef"])
        field("n", o["packerName"])
        field("ft", if (o["foodType"] == "Cold") "C" else "H")
        field("t", (o["tempC"] as? Number)?.toDouble()?.let { Math.round(it * 10) / 10.0 })
        field("tk", o["tempOk"] as? Boolean ?: false)
        field("s", o["score"] as? Number)
        field("pt", packedAt / 60_000)
        sb.append(",\"r\":[")
        rows.forEachIndexed { i, (c, m, det) ->
            if (i > 0) sb.append(',')
            sb.append('[').append(str(c)).append(',').append(m).append(',').append(str(det)).append(']')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun rowDetail(code: String, d: Map<*, *>): String {
        fun s(k: String) = (d[k] as? String)?.takeIf { it.isNotBlank() }
        val parts = when {
            code == "U" -> listOfNotNull(s("cleaningMedium"))
            code == "S" -> listOfNotNull(s("checklist")?.let { c -> "${c.split(",").size} checks" }, s("shift"))
            code == "P" -> listOfNotNull(s("nextDueDate")?.let { "next due $it" })
            else -> listOfNotNull(
                s("photoCount")?.let { if (it == "1") "1 photo" else "$it photos" },
                s("billDate")?.let { "bill $it" },
                s("supplierGst")?.let { "GST" },
            )
        }
        return parts.joinToString(", ").take(36)
    }

    private fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
