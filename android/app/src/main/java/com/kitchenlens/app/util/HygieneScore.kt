package com.kitchenlens.app.util

import com.kitchenlens.app.data.PERISHABLE_CATEGORIES
import com.kitchenlens.app.data.Section
import com.kitchenlens.app.data.latestKey
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * KitchenLens hygiene rating, version 2 (see docs/hygiene-scoring-rubric.md).
 *
 *  - Points come from PROOF (photos, clips, checklist, bills, temperature), not typed fields.
 *    Names, shift and supplier are still required, but earn no points.
 *  - Records EXPIRE: full credit inside the window, half credit up to twice the window, then 0.
 *    Utensils and staff: 12 h (one shift). Perishable inventory: 48 h. Dry goods: 7 days.
 *    Pest control: until the next-due date, half credit for 7 days after it.
 *  - Inventory categories a kitchen marks "not stocked" are left out and the total is rescaled.
 *  - A failed packing temperature WITHHOLDS the rating (no percentage is shown to customers).
 *
 * The same rules are implemented in the iPhone app (Logic.swift). Keep both in step.
 * This measures recorded process evidence; it is not a food-safety certificate.
 */
object HygieneScore {
    const val VERSION = 2
    const val HOT_MIN_C = 65.0
    const val COLD_MAX_C = 5.0

    /** Inventory points per category (total 25) + supplier bills (7) = 32. */
    val CATEGORY_WEIGHTS: Map<String, Int> = linkedMapOf(
        "Vegetables" to 5, "Meat & Fish" to 5, "Dairy" to 3, "Spices" to 3, "Cooking oil" to 6, "Grains & Pulses" to 3,
    )
    const val BILL_POINTS = 7
    const val CLEANING_POINTS = 5
    const val UTENSIL_VIDEO_POINTS = 10
    const val STAFF_CHECK_POINTS = 1   // per check, 5 checks
    const val STAFF_VIDEO_POINTS = 10
    const val PEST_VENDOR_POINTS = 3
    const val PEST_RECENCY_POINTS = 4
    const val PEST_PHOTO_POINTS = 8
    const val TEMP_POINTS = 8
    const val CLIP_POINTS = 15

    // Expiry windows in hours: full credit until the first number, half credit until the second.
    const val SHIFT_FULL_H = 12; const val SHIFT_HALF_H = 24
    const val PERISHABLE_FULL_H = 48; const val PERISHABLE_HALF_H = 96
    const val DRY_FULL_H = 168; const val DRY_HALF_H = 336

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    data class ItemResult(val name: String, val earned: Int, val maximum: Int, val note: String) {
        fun toMap(): Map<String, Any?> = mapOf("name" to name, "earned" to earned, "maximum" to maximum, "note" to note)
    }

    data class SectionResult(val key: String, val name: String, val earned: Int, val maximum: Int, val items: List<ItemResult>) {
        fun toMap(): Map<String, Any?> = mapOf(
            "key" to key, "name" to name, "earned" to earned, "maximum" to maximum, "items" to items.map { it.toMap() },
        )
    }

    data class Result(
        val percent: Int,
        val earned: Int,
        val maximum: Int,
        val sections: List<SectionResult>,
        val complete: Boolean,
        val safetyGatePassed: Boolean,
        val issues: List<String>,
        val notStocked: List<String>,
        val foodType: String,
    ) {
        /** "rated", "incomplete" (a section was never recorded) or "withheld" (temperature failed). */
        val status: String get() = when { !complete -> "incomplete"; !safetyGatePassed -> "withheld"; else -> "rated" }
        val rating: String get() = rating(percent)
        val stars: Double get() = stars(percent)

        /** Items worth the most points that are not fully earned: what to record next. */
        fun tips(limit: Int = 3): List<String> = sections.flatMap { s -> s.items.map { s to it } }
            .filter { (_, i) -> i.earned < i.maximum }
            .sortedByDescending { (_, i) -> i.maximum - i.earned }
            .take(limit)
            .map { (s, i) -> "${s.name}: ${i.name} (+${i.maximum - i.earned}) – ${i.note}" }

        fun toMap(): Map<String, Any?> = mapOf(
            "version" to VERSION,
            "percent" to percent, "earned" to earned, "maximum" to maximum,
            "sections" to sections.map { it.toMap() },
            "complete" to complete, "safetyGatePassed" to safetyGatePassed,
            "status" to status, "rating" to rating, "stars" to stars,
            "issues" to issues, "notStocked" to notStocked,
        )
    }

    /** 1.0 inside the window, 0.5 up to the half window, else 0. */
    fun freshness(at: Long?, now: Long, fullHours: Int, halfHours: Int): Double {
        if (at == null || at <= 0) return 0.0
        val age = now - at
        return when {
            age <= fullHours * HOUR -> 1.0
            age <= halfHours * HOUR -> 0.5
            else -> 0.0
        }
    }

    private fun ageText(at: Long, now: Long): String {
        val h = (now - at) / HOUR
        return when {
            h < 1 -> "under 1 h ago"
            h < 48 -> "$h h ago"
            else -> "${h / 24} days ago"
        }
    }

    private fun freshNote(at: Long?, now: Long, factor: Double, fullHours: Int, what: String): String = when {
        at == null || at <= 0 -> "$what not recorded"
        factor >= 1.0 -> "$what recorded ${ageText(at, now)}"
        factor > 0 -> "Half credit: $what recorded ${ageText(at, now)} (full credit for ${windowText(fullHours)})"
        else -> "Expired: $what recorded ${ageText(at, now)} (counts for ${windowText(fullHours)})"
    }

    private fun windowText(h: Int) = if (h >= 48) "${h / 24} days" else "$h h"

    private fun pts(weight: Int, factor: Double): Int = (weight * factor).roundToInt().coerceIn(0, weight)

    fun computeWeighted(
        latest: Map<String, Map<String, Any?>>,
        foodType: String,
        tempC: Double,
        packerName: String,
        packingClipUrl: String,
        now: Long,
        notStocked: Set<String> = emptySet(),
    ): Result {
        fun details(e: Map<String, Any?>?): Map<*, *> = e?.get("details") as? Map<*, *> ?: emptyMap<Any?, Any?>()
        fun value(e: Map<String, Any?>?, key: String): String = details(e)[key]?.toString()?.trim().orEmpty()
        fun at(e: Map<String, Any?>?): Long? = (e?.get("at") as? Number)?.toLong()
        fun isVideo(e: Map<String, Any?>?) = e != null && e["mediaType"]?.toString() == "video"
        fun isPhoto(e: Map<String, Any?>?) = e != null && e["mediaType"]?.toString() != "video"
        fun section(key: String, name: String, rows: List<ItemResult>) =
            SectionResult(key, name, rows.sumOf { it.earned }, rows.sumOf { it.maximum }, rows)

        val skipped = CATEGORY_WEIGHTS.keys.filter { it in notStocked }

        // 1. Food inventory (32, less any categories not stocked)
        val invRows = mutableListOf<ItemResult>()
        var bestBillFactor = 0.0
        var billAt: Long? = null
        CATEGORY_WEIGHTS.forEach { (cat, weight) ->
            val e = latest[latestKey(Section.INVENTORY, cat)]
            val perishable = cat in PERISHABLE_CATEGORIES
            val full = if (perishable) PERISHABLE_FULL_H else DRY_FULL_H
            val f = freshness(at(e), now, full, if (perishable) PERISHABLE_HALF_H else DRY_HALF_H)
            if ((value(e, "billCount").toIntOrNull() ?: 0) > 0 && f > bestBillFactor) { bestBillFactor = f; billAt = at(e) }
            if (cat in notStocked) return@forEach
            val f2 = if (e != null && isPhoto(e)) f else 0.0
            invRows += ItemResult(cat, pts(weight, f2), weight, freshNote(at(e), now, f2, full, "Photos"))
        }
        invRows += ItemResult(
            "Supplier bills", pts(BILL_POINTS, bestBillFactor), BILL_POINTS,
            if (billAt == null) "No supplier bill linked to a current delivery" else freshNote(billAt, now, bestBillFactor, PERISHABLE_FULL_H, "Bill"),
        )
        val inventory = section("INVENTORY", "Food inventory details", invRows)

        // 2. Utensils cleaning (15)
        val ut = latest[Section.UTENSILS.name]
        val utF = freshness(at(ut), now, SHIFT_FULL_H, SHIFT_HALF_H)
        val methods = value(ut, "cleaningMedium").split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct().size
        val methodPts = when { methods >= 3 -> 5; methods == 2 -> 3; methods == 1 -> 1; else -> 0 }
        val utensils = section("UTENSILS", "Cooking utensils cleaning", listOf(
            ItemResult("Cleaning methods (3 or more for full points)", pts(methodPts, utF), CLEANING_POINTS,
                if (ut == null) "Not recorded" else "$methods method(s) · " + freshNote(at(ut), now, utF, SHIFT_FULL_H, "record")),
            ItemResult("Wash video", if (isVideo(ut)) pts(UTENSIL_VIDEO_POINTS, utF) else 0, UTENSIL_VIDEO_POINTS,
                freshNote(at(ut), now, if (isVideo(ut)) utF else 0.0, SHIFT_FULL_H, "Video")),
        ))

        // 3. Staff hygiene (15)
        val st = latest[Section.STAFF.name]
        val stF = freshness(at(st), now, SHIFT_FULL_H, SHIFT_HALF_H)
        val checks = value(st, "checklist").split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct().size.coerceAtMost(5)
        val staff = section("STAFF", "Chef and support-staff hygiene", listOf(
            ItemResult("Hygiene checks shown (1 point each)", pts(checks * STAFF_CHECK_POINTS, stF), 5 * STAFF_CHECK_POINTS,
                if (st == null) "Not recorded" else "$checks of 5 checks · " + freshNote(at(st), now, stF, SHIFT_FULL_H, "record")),
            ItemResult("Hand-wash and kit video", if (isVideo(st)) pts(STAFF_VIDEO_POINTS, stF) else 0, STAFF_VIDEO_POINTS,
                freshNote(at(st), now, if (isVideo(st)) stF else 0.0, SHIFT_FULL_H, "Video")),
        ))

        // 4. Pest control (15): valid until next due date, half credit for 7 days after
        val pe = latest[Section.PEST.name]
        val serviced = parseServiceDate(value(pe, "serviceDate"))
        val due = value(pe, "nextDueMillis").toLongOrNull() ?: serviced?.plus(30 * DAY)
        val peF = when {
            pe == null || due == null -> 0.0
            now <= due -> 1.0
            now <= due + 7 * DAY -> 0.5
            else -> 0.0
        }
        val dueNote = when {
            pe == null -> "Not recorded"
            due == null -> "Next service date missing"
            peF >= 1.0 -> "Valid until next service on ${Dates.format(due)}"
            peF > 0 -> "Half credit: next service was due ${Dates.format(due)}"
            else -> "Expired: next service was due ${Dates.format(due)}"
        }
        val pest = section("PEST", "Pest control", listOf(
            ItemResult("Pest-control vendor", if (value(pe, "vendor").isNotBlank()) pts(PEST_VENDOR_POINTS, peF) else 0, PEST_VENDOR_POINTS, dueNote),
            ItemResult("Service recency", if (peF > 0) pestDatePoints(serviced, now) else 0, PEST_RECENCY_POINTS, pestDateNote(serviced, now)),
            ItemResult("Service report photo", if (isPhoto(pe)) pts(PEST_PHOTO_POINTS, peF) else 0, PEST_PHOTO_POINTS,
                if (pe == null) "Not recorded" else dueNote),
        ))

        // 5. Packing (23)
        val tempOk = temperatureOk(foodType, tempC)
        val packing = section("PACKING", "Food packing and temperature", listOf(
            ItemResult("Food temperature at packing", if (tempOk) TEMP_POINTS else 0, TEMP_POINTS,
                if (tempOk) "Guideline met (${tempC.roundToInt()}°C)" else withheldReason(foodType)),
            ItemResult("Packing video", if (packingClipUrl.isNotBlank()) CLIP_POINTS else 0, CLIP_POINTS,
                if (packingClipUrl.isNotBlank()) "Packing video linked" else "Packing video missing"),
        ))

        val sections = listOf(inventory, utensils, staff, pest, packing)
        val earned = sections.sumOf { it.earned }
        val maximum = sections.sumOf { it.maximum }
        val percent = if (maximum == 0) 0 else (earned * 100.0 / maximum).roundToInt()

        val inventoryPresent = latest.keys.any { it.startsWith("INV_") }
        val complete = inventoryPresent && ut != null && st != null && pe != null &&
            packerName.isNotBlank() && packingClipUrl.isNotBlank()
        val issues = buildList {
            if (!inventoryPresent) add("Food inventory not recorded")
            if (ut == null) add("Utensils cleaning not recorded")
            if (st == null) add("Staff hygiene not recorded")
            if (pe == null) add("Pest control not recorded")
            if (packerName.isBlank()) add("Packer name missing")
            if (packingClipUrl.isBlank()) add("Packing video missing")
            if (!tempOk) add(withheldReason(foodType))
            sections.flatMap { it.items }.filter { it.note.startsWith("Expired") }.forEach { add("${it.name}: ${it.note}") }
        }
        return Result(percent, earned, maximum, sections, complete, tempOk, issues, skipped, foodType)
    }

    /**
     * What the NEXT order would score with the current records, assuming the temperature
     * meets the guideline and a packing clip is recorded. Shown on the kitchen's Home screen.
     */
    fun readiness(latest: Map<String, Map<String, Any?>>, now: Long, notStocked: Set<String>): Result =
        computeWeighted(latest, "Hot", HOT_MIN_C, "packer", "clip", now, notStocked)

    fun withheldReason(foodType: String): String =
        if (foodType == "Cold") "Food above 5°C at packing – rating withheld"
        else "Food below 65°C at packing – rating withheld"

    private fun pestDatePoints(at: Long?, now: Long): Int {
        at ?: return 0
        val days = ((now - at) / DAY).toInt()
        return when { days < 0 -> 0; days < 7 -> 4; days < 14 -> 3; days < 30 -> 2; else -> 1 }
    }

    private fun pestDateNote(at: Long?, now: Long): String {
        at ?: return "Service date missing or invalid"
        val days = ((now - at) / DAY).toInt()
        return when {
            days < 0 -> "Service date is in the future"
            days < 7 -> "Serviced within the last 7 days"
            days < 14 -> "Serviced within the last 14 days"
            days < 30 -> "Serviced within the last 30 days"
            else -> "Serviced more than 30 days ago"
        }
    }

    fun parseServiceDate(value: String): Long? {
        if (value.isBlank()) return null
        for (pattern in listOf("dd-MM-yyyy", "d-M-yyyy", "yyyy-MM-dd")) {
            try {
                val f = SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false }
                f.parse(value)?.let { return it.time }
            } catch (_: Exception) { }
        }
        return null
    }

    fun rating(percent: Int): String = when {
        percent >= 90 -> "Excellent"
        percent >= 80 -> "Very good"
        percent >= 70 -> "Good"
        percent >= 50 -> "Fair"
        else -> "Needs improvement"
    }

    /** Percentage to stars out of 5, in half stars (90% = 4.5). */
    fun stars(percent: Int): Double = (percent.coerceIn(0, 100) / 10.0).roundToInt() / 2.0

    /** "★★★★½" style text for labels. */
    fun starText(stars: Double): String {
        val full = stars.toInt()
        val half = stars - full >= 0.5
        return "★".repeat(full) + (if (half) "½" else "") + "☆".repeat(5 - full - (if (half) 1 else 0))
    }

    fun temperatureOk(foodType: String, tempC: Double): Boolean =
        if (foodType == "Cold") tempC <= COLD_MAX_C else tempC >= HOT_MIN_C
}
