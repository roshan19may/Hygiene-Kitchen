package com.kitchenlens.app.util

import com.kitchenlens.app.data.PERISHABLE_CATEGORIES
import com.kitchenlens.app.data.Section
import com.kitchenlens.app.data.latestKey

/**
 * Simple, explainable 0-100 score computed at the moment of packing.
 * Each of five pillars is worth 20 points. Kept rule-based on purpose so a
 * kitchen owner can always see why they lost points.
 */
object HygieneScore {
    private const val HOUR = 3_600_000L

    fun compute(latest: Map<String, Map<String, Any?>>, tempOk: Boolean, now: Long): Int {
        var score = 0
        score += freshness(latest[Section.UTENSILS.name], now, 12, 24)
        score += freshness(latest[Section.STAFF.name], now, 12, 24)
        score += pest(latest[Section.PEST.name], now)
        // Best (most recent) perishable delivery counts
        val perishable = PERISHABLE_CATEGORIES.maxOfOrNull { cat ->
            freshness(latest[latestKey(Section.INVENTORY, cat)], now, 48, 96)
        } ?: 0
        score += perishable
        if (tempOk) score += 20
        return score
    }

    private fun freshness(entry: Map<String, Any?>?, now: Long, fullHours: Int, halfHours: Int): Int {
        val at = (entry?.get("at") as? Number)?.toLong() ?: return 0
        val age = now - at
        return when {
            age <= fullHours * HOUR -> 20
            age <= halfHours * HOUR -> 10
            else -> 0
        }
    }

    private fun pest(entry: Map<String, Any?>?, now: Long): Int {
        val details = entry?.get("details") as? Map<*, *> ?: return 0
        val nextDue = (details["nextDueMillis"] as? String)?.toLongOrNull()
            ?: (details["nextDueMillis"] as? Number)?.toLong() ?: return 0
        return when {
            now <= nextDue -> 20
            now <= nextDue + 7 * 24 * HOUR -> 10
            else -> 0
        }
    }

    /** Hot food should leave the kitchen at 60°C or above; cold food at 5°C or below. */
    fun temperatureOk(foodType: String, tempC: Double): Boolean =
        if (foodType == "Cold") tempC <= 5.0 else tempC >= 60.0
}
