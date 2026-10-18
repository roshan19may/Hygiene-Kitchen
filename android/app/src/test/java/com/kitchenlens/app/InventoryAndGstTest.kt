package com.kitchenlens.app

import com.kitchenlens.app.data.InventoryBill
import com.kitchenlens.app.data.MediaType
import com.kitchenlens.app.data.PRIVATE_DETAIL_KEYS
import com.kitchenlens.app.data.buildInventoryRecords
import com.kitchenlens.app.data.publicSummary
import com.kitchenlens.app.util.Dates
import com.kitchenlens.app.util.Gstin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InventoryAndGstTest {

    @Test fun gstinValidation() {
        assertEquals(Gstin.Result.OK, Gstin.check("27AAPFU0939F1ZV"))
        assertEquals(Gstin.Result.OK, Gstin.check(" 29abcde1234f1zw "))      // spaces and lower case are cleaned up
        assertEquals(Gstin.Result.BAD_CHECKSUM, Gstin.check("27AAPFU0939F1ZA"))
        assertEquals(Gstin.Result.BAD_FORMAT, Gstin.check("27AAPFU0939"))
        assertEquals(Gstin.Result.EMPTY, Gstin.check(""))
        assertNull(Gstin.formError("", required = false))
        assertTrue(Gstin.formError("12345", required = false) != null)
        // A checksum mismatch is only a warning, so a real number is never blocked
        assertNull(Gstin.formError("27AAPFU0939F1ZA", required = false))
        assertTrue(Gstin.warning("27AAPFU0939F1ZA") != null)
    }

    @Test fun gstinFoundOnBillText() {
        val text = "SRI BALAJI TRADERS\nGSTIN: 27AAPFU0939F1ZV\nInv 4471 Dt 16/10/2026"
        assertEquals("27AAPFU0939F1ZV", Gstin.findIn(text))
        assertNull(Gstin.findIn("CASH BILL no tax number"))
    }

    @Test fun latestBillDateWins() {
        assertEquals("16-10-2026", Dates.latest(listOf("14-10-2026", "16-10-2026", "", "bad")))
        assertNull(Dates.latest(listOf("", "31-02-2026")))
    }

    @Test fun billsAreSharedAcrossCategories() {
        val f = File("x.jpg")
        val bills = listOf(
            // one bill covering everything
            InventoryBill(f, setOf("Vegetables", "Dairy", "Spices"), "15-10-2026", "27AAPFU0939F1ZV"),
            // a second, newer bill only for vegetables
            InventoryBill(f, setOf("Vegetables"), "16-10-2026", ""),
        )
        val records = buildInventoryRecords(
            photoUrls = mapOf(
                "Vegetables" to listOf("v1", "v2", "v3"),
                "Dairy" to listOf("d1"),
                "Spices" to listOf("s1"),
                "Meat & Fish" to emptyList(), // no photos: skipped
            ),
            bills = bills,
            billUrls = listOf("b1", "b2"),
            supplierType = "Wholesaler",
        ).associateBy { it.category }

        assertEquals(setOf("Vegetables", "Dairy", "Spices"), records.keys)
        val veg = records.getValue("Vegetables")
        assertEquals(listOf("v1", "v2", "v3"), veg.mediaUrls)
        assertEquals("3", veg.details["photoCount"])
        assertEquals("2", veg.details["billCount"])
        assertEquals("16-10-2026", veg.details["billDate"])
        assertEquals("b1,b2", veg.details["billUrls"])
        assertEquals("1", records.getValue("Dairy").details["billCount"])
        assertEquals("15-10-2026", records.getValue("Dairy").details["billDate"])
        assertEquals("registered", records.getValue("Spices").details["supplierGst"])
        assertEquals("INV_VEGETABLES", veg.key)
    }

    @Test fun billsAreOptional() {
        val r = buildInventoryRecords(mapOf("Dairy" to listOf("d1")), emptyList(), emptyList(), null).single()
        assertFalse(r.details.containsKey("billDate"))
        assertFalse(r.details.containsKey("billUrls"))
    }

    @Test fun customersNeverSeeBillPhotosOrSupplierGstin() {
        val details = mapOf("billDate" to "16-10-2026", "billUrls" to "secret", "supplierGstins" to "27AAPFU0939F1ZV", "supplierGst" to "registered")
        val summary = publicSummary(1L, "u", MediaType.PHOTO, "Vegetables received", details, listOf("u", "u2"))
        @Suppress("UNCHECKED_CAST")
        val shown = summary["details"] as Map<String, String>
        PRIVATE_DETAIL_KEYS.forEach { assertFalse(shown.containsKey(it)) }
        assertEquals("16-10-2026", shown["billDate"])
        assertEquals(listOf("u", "u2"), summary["mediaUrls"])
    }
}
