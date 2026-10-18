package com.kitchenlens.app

import com.kitchenlens.app.data.Kitchen
import com.kitchenlens.app.data.buildOrderRecord
import com.kitchenlens.app.data.orderRefKey
import com.kitchenlens.app.util.OfflineSeal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.Inflater

class OrderLinkTest {
    private val now = 1_760_780_000_000L

    private fun order(): Map<String, Any?> {
        val k = Kitchen(
            id = "k1", name = "Biryani Box \"Express\"", locality = "HSR Layout", city = "Bengaluru",
            fssai = "11223344556677", gstin = "29ABCDE1234F1ZW",
            latest = mapOf(
                "UTENSILS" to mapOf("at" to now - 125 * 60_000L, "details" to mapOf("cleaningMedium" to "Detergent, Hot water")),
                "STAFF" to mapOf("at" to now - 150 * 60_000L, "details" to mapOf("checklist" to "Gloves, Cap / hairnet", "shift" to "Night")),
                "PEST" to mapOf("at" to now - 14 * 24 * 60 * 60_000L, "details" to mapOf("nextDueDate" to "16-11-2026")),
                "INV_MEAT___FISH" to mapOf("at" to now - 330 * 60_000L, "details" to mapOf(
                    "category" to "Meat & Fish", "photoCount" to "2", "billDate" to "16-10-2026", "supplierGst" to "registered")),
            ),
        )
        return buildOrderRecord(k, "Swiggy", "1588-2334-4471", "Asha Rao", "Hot", 71.0, true, "file:///clip.mp4", now, 90)
    }

    @Test fun orderNumberKeyIgnoresSpacesDashesAndCase() {
        assertEquals("SWIGGY_158823344471", orderRefKey("Swiggy", "1588-2334 4471"))
        assertEquals("DIRECTOTHER_A12", orderRefKey("Direct / Other", "a12"))
        val rec = order()
        assertEquals("1588-2334-4471", rec["orderRef"])
        assertEquals("SWIGGY_158823344471", rec["orderRefKey"])
    }

    @Test fun offlineSealCarriesTheOrderDetails() {
        val json = OfflineSeal.compactJson(order())
        listOf(
            "\"k\":\"Biryani Box \\\"Express\\\"\"", "\"o\":\"1588-2334-4471\"", "\"p\":\"Swiggy\"",
            "\"g\":\"29ABCDE1234F1ZW\"", "\"t\":71.0", "\"tk\":1", "\"s\":90", "\"n\":\"Asha\"",
            "[\"U\",125,\"Detergent, Hot water\"]", "[\"S\",150,\"2 checks, Night\"]",
            "[\"IMeat & Fish\",330,\"2 photos, bill 16-10-2026, GST\"]",
        ).forEach { assertTrue("missing $it in $json", json.contains(it)) }
    }

    @Test fun offlineSealRoundTripsAndFitsOnALabel() {
        val payload = OfflineSeal.payload(order())
        assertTrue(payload.startsWith("z"))
        val bytes = Base64.getUrlDecoder().decode(payload.substring(1))
        val inflater = Inflater(true)
        inflater.setInput(bytes)
        val out = ByteArray(8192)
        val n = inflater.inflate(out)
        inflater.end()
        assertEquals(OfflineSeal.compactJson(order()), String(out, 0, n, Charsets.UTF_8))
        val url = OfflineSeal.url("https://example.github.io/Hygiene-Kitchen/?c=", "AB12CD34", order())
        assertTrue(url.startsWith("https://example.github.io/Hygiene-Kitchen/?c=AB12CD34&d=z"))
        assertTrue("QR link too long: ${url.length}", url.length < 700)
    }

    @Test fun offlineSealCarriesCloudMediaIds() {
        @Suppress("UNCHECKED_CAST")
        val base = order()
        val snap = (base["snapshot"] as Map<String, Map<String, Any?>>).mapValues { (k, e) ->
            e + ("cloudIds" to if (k == "INV_MEAT___FISH") listOf("klab12cd34", "klzz99yy88") else listOf("kl" + k.take(6).lowercase()))
        }
        val withCloud = base + mapOf("snapshot" to snap, "cloudName" to "demo-kitchen", "clipCloudId" to "klpack1234")
        val json = OfflineSeal.compactJson(withCloud)
        listOf("\"cl\":\"demo-kitchen\"", "\"c\":\"klpack1234\"",
            "[\"IMeat & Fish\",330,\"2 photos, bill 16-10-2026, GST\",[\"klab12cd34\",\"klzz99yy88\"]]",
            "[\"U\",125,\"Detergent, Hot water\",[\"klutensi\"]]",
        ).forEach { assertTrue("missing $it in $json", json.contains(it)) }
        val url = OfflineSeal.url("https://example.github.io/Hygiene-Kitchen/?c=", "AB12CD34", withCloud)
        assertTrue("QR link too long: ${url.length}", url.length < 800)
    }
}
