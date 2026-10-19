package com.kitchenlens.app

import com.kitchenlens.app.data.Kitchen
import com.kitchenlens.app.data.Section
import com.kitchenlens.app.data.buildOrderRecord
import com.kitchenlens.app.data.latestKey
import com.kitchenlens.app.util.BillReader
import com.kitchenlens.app.util.HygieneScore
import com.kitchenlens.app.util.ImageTools
import com.kitchenlens.app.util.KitchenStats
import com.kitchenlens.app.util.SpotCheck
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

    @Test fun temperatureRules() {
        assertTrue(HygieneScore.temperatureOk("Hot", 65.0))
        assertTrue(HygieneScore.temperatureOk("Hot", 72.0))
        assertFalse(HygieneScore.temperatureOk("Hot", 64.5))
        assertFalse(HygieneScore.temperatureOk("Hot", 50.0))
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
    // ---------- Hygiene rating v2 ----------

    private fun media(type: String, ageHours: Long, details: Map<String, Any?>) =
        mapOf("at" to now - ageHours * hour, "mediaUrl" to "u", "mediaType" to type, "label" to "t", "details" to details)

    private fun allFresh(): MutableMap<String, Map<String, Any?>> {
        val latest = mutableMapOf<String, Map<String, Any?>>()
        HygieneScore.CATEGORY_WEIGHTS.keys.forEach { c ->
            latest[latestKey(Section.INVENTORY, c)] = media("photo", 2, mapOf("category" to c, "billCount" to "1", "supplierType" to "Wholesaler"))
        }
        latest[Section.UTENSILS.name] = media("video", 2, mapOf("cleaningMedium" to "Detergent, Hot water, Food-grade sanitiser", "shift" to "Morning", "recordedBy" to "Asha"))
        latest[Section.STAFF.name] = media("video", 2, mapOf("checklist" to "Hand-wash with soap, Cap / hairnet, Gloves, Clean apron, Mask", "staffOnShift" to "4", "shift" to "Morning", "recordedBy" to "Asha"))
        latest[Section.PEST.name] = media("photo", 72, mapOf("vendor" to "Test Pest", "serviceDate" to java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.ROOT).format(java.util.Date(now - 3 * 24 * hour)), "nextDueMillis" to (now + 27 * 24 * hour).toString(), "recordedBy" to "Asha"))
        return latest
    }

    @Test fun freshProofScoresFullMarks() {
        val r = HygieneScore.computeWeighted(allFresh(), "Hot", 70.0, "Asha", "clip", now)
        assertEquals(listOf(32, 15, 15, 15, 23), r.sections.map { it.maximum })
        assertEquals(100, r.percent)
        assertEquals("rated", r.status)
        assertEquals(5.0, r.stars, 0.0)
        assertEquals("Excellent", r.rating)
    }

    @Test fun typedFieldsEarnNoPoints() {
        val latest = allFresh().mapValues { (_, e) ->
            @Suppress("UNCHECKED_CAST")
            val d = (e["details"] as Map<String, Any?>).filterKeys { it !in setOf("recordedBy", "shift", "supplierType") }
            e + ("details" to d)
        }
        assertEquals(100, HygieneScore.computeWeighted(latest, "Hot", 70.0, "Asha", "clip", now).percent)
    }

    @Test fun notStockedCategoriesAreLeftOutAndRescaled() {
        val latest = allFresh().apply { remove(latestKey(Section.INVENTORY, "Meat & Fish")) }
        val withMissing = HygieneScore.computeWeighted(latest, "Hot", 70.0, "Asha", "clip", now)
        assertEquals(95, withMissing.percent)   // 95 of 100
        val veg = HygieneScore.computeWeighted(latest, "Hot", 70.0, "Asha", "clip", now, setOf("Meat & Fish"))
        assertEquals(27, veg.sections[0].maximum)
        assertEquals(100, veg.percent)
        assertEquals(listOf("Meat & Fish"), veg.notStocked)
    }

    @Test fun oldRecordsExpire() {
        val latest = allFresh()
        latest[Section.UTENSILS.name] = media("video", 18, mapOf("cleaningMedium" to "Detergent, Hot water, Scrub pad"))   // half
        latest[Section.STAFF.name] = media("video", 30, mapOf("checklist" to "Gloves, Mask"))                              // expired
        latest[latestKey(Section.INVENTORY, "Vegetables")] = media("photo", 60, mapOf("category" to "Vegetables"))          // half (48 h)
        latest[latestKey(Section.INVENTORY, "Spices")] = media("photo", 100, mapOf("category" to "Spices"))                 // dry goods: still full
        val r = HygieneScore.computeWeighted(latest, "Hot", 70.0, "Asha", "clip", now)
        assertEquals(8, r.sections[1].earned)   // 3 + 5
        assertEquals(0, r.sections[2].earned)
        assertEquals(3, r.sections[0].items.first { it.name == "Vegetables" }.earned)
        assertEquals(3, r.sections[0].items.first { it.name == "Spices" }.earned)
        assertTrue(r.issues.any { it.contains("Expired") })
    }

    @Test fun overduePestControlEarnsNothing() {
        val latest = allFresh()
        latest[Section.PEST.name] = media("photo", 50 * 24, mapOf("vendor" to "X", "serviceDate" to "01-01-2020",
            "nextDueMillis" to (now - 10 * 24 * hour).toString()))
        assertEquals(0, HygieneScore.computeWeighted(latest, "Hot", 70.0, "Asha", "clip", now).sections[3].earned)
    }

    @Test fun failedTemperatureWithholdsRating() {
        val r = HygieneScore.computeWeighted(allFresh(), "Hot", 60.0, "Asha", "clip", now)
        assertEquals("withheld", r.status)
        assertTrue(r.issues.any { it.contains("below 65°C") })
        assertEquals("withheld", r.toMap()["status"])
        val cold = HygieneScore.computeWeighted(allFresh(), "Cold", 9.0, "Asha", "clip", now)
        assertTrue(cold.issues.any { it.contains("above 5°C") })
    }

    @Test fun missingSectionMeansNotRated() {
        assertEquals("incomplete", HygieneScore.computeWeighted(emptyMap(), "Hot", 70.0, "Asha", "clip", now).status)
    }

    @Test fun starsAndWords() {
        assertEquals(4.5, HygieneScore.stars(90), 0.0)
        assertEquals(4.5, HygieneScore.stars(85), 0.0)
        assertEquals(4.0, HygieneScore.stars(84), 0.0)
        assertEquals(0.0, HygieneScore.stars(0), 0.0)
        assertEquals("Very good", HygieneScore.rating(80))
        assertEquals("Fair", HygieneScore.rating(50))
        assertEquals("Needs improvement", HygieneScore.rating(49))
        assertEquals("★★★★½", HygieneScore.starText(4.5))
    }

    @Test fun readinessTellsKitchenWhatToRecordNext() {
        val latest = allFresh().apply { remove(Section.UTENSILS.name) }
        val r = HygieneScore.readiness(latest, now, emptySet())
        assertEquals(85, r.percent)
        assertTrue(r.tips().first().contains("Wash video"))
    }

    // ---------- Kitchen 30-day rating ----------

    private val utc = java.util.TimeZone.getTimeZone("UTC")

    @Test fun kitchenRatingNeedsFiveOrders() {
        val few = (1..4).map { KitchenStats.encode(now - it * hour, 90, "rated") }
        assertFalse(KitchenStats.compute("k", few, emptyList(), emptyList(), now, utc).eligible)
        val ten = (1..10).map { KitchenStats.encode(now - it * hour, 90, "rated") }
        val s = KitchenStats.compute("k", ten, emptyList(), emptyList(), now, utc)
        assertTrue(s.eligible)
        assertEquals(100, s.consistency)
        assertEquals(10, s.orders)
    }

    @Test fun kitchenRatingRewardsConsistencyAndSpotChecks() {
        val day = SpotCheck.dayStart(now - 2 * 24 * hour, utc)
        val start = SpotCheck.windowStart("k", day)
        val orders = (0 until 10).map { KitchenStats.encode(day + 9 * hour + it * 60_000L, 80, if (it < 8) "rated" else "withheld") }
        val missed = KitchenStats.compute("k", orders, emptyList(), emptyList(), now, utc)
        assertEquals(1, missed.spotRequested)
        assertEquals(0, missed.spotAnswered)
        assertEquals(80, missed.consistency)
        assertEquals(72, missed.percent)   // 0.75*80 + 0.15*80 + 0.10*0
        val answered = KitchenStats.compute("k", orders, listOf(start + 5 * 60_000L), emptyList(), now, utc)
        assertEquals(1, answered.spotAnswered)
        assertEquals(82, answered.percent)
    }

    @Test fun rollingListDropsOldOrders() {
        val list = listOf(KitchenStats.encode(now - 40 * 24 * hour, 50, "rated"), KitchenStats.encode(now - hour, 90, "rated"))
        val out = KitchenStats.append(list, KitchenStats.encode(now, 95, "rated"), now)
        assertEquals(2, out.size)
    }

    @Test fun spotCheckTimeIsDeterministicAndInWorkingHours() {
        assertEquals(0xE40C292Cu, SpotCheck.fnv1a("a"))
        val day = SpotCheck.dayStart(now, utc)
        val a = SpotCheck.windowStart("kitchen-1", day)
        assertEquals(a, SpotCheck.windowStart("kitchen-1", day))
        assertTrue(a - day >= 11 * hour && a - day <= 21 * hour + 30 * 60_000L)
    }

    @Test fun feedbackShownOnlyWithEnoughAnswers() {
        val orders = (1..6).map { KitchenStats.encode(now - it * hour, 90, "rated") }
        val few = KitchenStats.compute("k", orders, emptyList(), listOf(true to true), now, utc)
        assertNull(few.sealIntactPct)
        val many = KitchenStats.compute("k", orders, emptyList(), List(4) { true to true } + (false to true), now, utc)
        assertEquals(80, many.sealIntactPct)
        assertEquals(100, many.foodHotPct)
    }
}
