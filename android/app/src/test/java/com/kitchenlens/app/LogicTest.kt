package com.kitchenlens.app

import com.kitchenlens.app.data.Kitchen
import com.kitchenlens.app.data.Section
import com.kitchenlens.app.data.buildOrderRecord
import com.kitchenlens.app.data.latestKey
import com.kitchenlens.app.util.BillReader
import com.kitchenlens.app.util.HygieneScore
import com.kitchenlens.app.util.ImageTools
import com.kitchenlens.app.util.QrLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Runs on GitHub Actions with every push (no phone needed). */
class LogicTest {
    private val hour = 3_600_000L
    private val now = 1_760_000_000_000L

    private fun entry(ageHours: Long, details: Map<String, Any?> = emptyMap()) =
        mapOf("at" to now - ageHours * hour, "details" to details)

    @Test fun fullScoreWhenEverythingIsFresh() {
        val latest = mapOf(
            Section.UTENSILS.name to entry(2),
            Section.STAFF.name to entry(3),
            Section.PEST.name to entry(240, mapOf("nextDueMillis" to (now + 20 * 24 * hour).toString())),
            latestKey(Section.INVENTORY, "Vegetables") to entry(5),
        )
        assertEquals(100, HygieneScore.compute(latest, tempOk = true, now = now))
    }

    @Test fun scoreDropsForStaleRecordsAndBadTemperature() {
        val latest = mapOf(
            Section.UTENSILS.name to entry(18),   // half points
            Section.STAFF.name to entry(30),      // zero
            latestKey(Section.INVENTORY, "Spices") to entry(1), // not perishable: zero
        )
        assertEquals(10, HygieneScore.compute(latest, tempOk = false, now = now))
    }

    @Test fun overduePestControlLosesPoints() {
        val overdue = mapOf(Section.PEST.name to entry(960, mapOf("nextDueMillis" to (now - 10 * 24 * hour).toString())))
        assertEquals(0, HygieneScore.compute(overdue, tempOk = false, now = now))
    }

    @Test fun temperatureRules() {
        assertTrue(HygieneScore.temperatureOk("Hot", 65.0))
        assertFalse(HygieneScore.temperatureOk("Hot", 52.0))
        assertTrue(HygieneScore.temperatureOk("Cold", 4.0))
        assertFalse(HygieneScore.temperatureOk("Cold", 9.0))
    }

    @Test fun readsIndianDateFromBillText() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 18, 12, 0) }
        val text = "SRI BALAJI TRADERS\nInv No 4471  Date: 16/10/2026\nOnion 10kg 380.00"
        assertEquals("16-10-2026", BillReader.pickDate(text, cal.timeInMillis))
        assertEquals("15-10-2026", BillReader.pickDate("Dt 15-10-26 GSTIN 29ABCDE", cal.timeInMillis))
        assertNull(BillReader.pickDate("Date 01/01/2020", cal.timeInMillis)) // too old
    }

    @Test fun duplicatePhotoDetection() {
        val a = "f0f0f0f0f0f0f0f0"
        val nearlySame = "f0f0f0f0f0f0f0f1" // 1 bit different
        val different = "0f0f0f0f0f0f0f0f"
        assertTrue(ImageTools.isDuplicate(nearlySame, listOf(a)))
        assertFalse(ImageTools.isDuplicate(different, listOf(a)))
    }

    @Test fun orderCodesAreRandomAndReadable() {
        val codes = (1..2000).map { QrLabel.newCode() }.toSet()
        assertEquals(2000, codes.size)
        codes.forEach { c ->
            assertEquals(8, c.length)
            assertTrue(c.none { it in "01OIL" })
        }
    }

    @Test fun publicOrderHidesFullOrderNumberAndSurname() {
        val k = Kitchen(id = "k1", name = "Biryani Box", city = "Bengaluru")
        val rec = buildOrderRecord(k, "Swiggy", "SW-12345678", "Ravi Kumar", "Hot", 72.0, true, "url", now, 95)
        assertEquals("5678", rec["orderRefLast4"])
        assertEquals("Ravi", rec["packerName"])
    }
}
