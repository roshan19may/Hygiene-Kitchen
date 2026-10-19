package com.kitchenlens.app.util

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Kitchen-level hygiene rating over the last 30 days, so customers can choose a kitchen
 * BEFORE they order (the per-order rating is only seen after delivery).
 *
 *   kitchen % = 75% × average order score
 *             + 15% × consistency (share of orders with complete proof and a passed temperature check)
 *             + 10% × spot checks answered
 * (without any spot-check requests yet, the first two are rescaled to 100%).
 * Customer feedback (seal intact, food hot) is shown beside it, not mixed into it.
 * Same rules in the iPhone app (Logic.swift).
 */
data class KitchenStats(
    val orders: Int,
    val avgScore: Int,
    val consistency: Int,
    val spotAnswered: Int,
    val spotRequested: Int,
    val feedbackCount: Int,
    val sealIntactPct: Int?,
    val foodHotPct: Int?,
    /** "improving", "steady", "slipping" or "" (not enough orders to tell). */
    val trend: String,
    val percent: Int,
    val updatedAt: Long,
) {
    /** At least [MIN_ORDERS] sealed orders in 30 days before a kitchen rating is shown. */
    val eligible: Boolean get() = orders >= MIN_ORDERS
    val stars: Double get() = HygieneScore.stars(percent)
    val rating: String get() = HygieneScore.rating(percent)

    fun toMap(): Map<String, Any?> = mapOf(
        "orders" to orders, "avgScore" to avgScore, "consistency" to consistency,
        "spotAnswered" to spotAnswered, "spotRequested" to spotRequested,
        "feedbackCount" to feedbackCount, "sealIntactPct" to sealIntactPct, "foodHotPct" to foodHotPct,
        "trend" to trend, "percent" to percent, "stars" to stars, "rating" to rating,
        "eligible" to eligible, "updatedAt" to updatedAt, "windowDays" to WINDOW_DAYS,
    )

    /** One line for labels and the app: "4.5★ Excellent · 212 orders in 30 days · improving". */
    fun summary(): String = if (!eligible) "New kitchen: rating shown after $MIN_ORDERS sealed orders ($orders so far)"
    else "${stars}★ $rating · $orders orders in 30 days" + (if (trend.isNotBlank()) " · $trend" else "")

    data class OrderStat(val packedAt: Long, val percent: Int, val status: String)

    companion object {
        const val WINDOW_DAYS = 30
        const val MIN_ORDERS = 5
        const val MAX_ENTRIES = 3000
        private const val DAY = 24 * 3_600_000L

        fun encode(packedAt: Long, percent: Int, status: String) = "$packedAt|$percent|${status.take(1)}"

        fun decode(s: String): OrderStat? {
            val p = s.split("|")
            if (p.size < 3) return null
            val at = p[0].toLongOrNull() ?: return null
            val pct = p[1].toIntOrNull() ?: return null
            val status = when (p[2]) { "r" -> "rated"; "w" -> "withheld"; else -> "incomplete" }
            return OrderStat(at, pct, status)
        }

        /** Adds an order to the kitchen's rolling list and drops entries older than 30 days. */
        fun append(list: List<String>, entry: String, now: Long): List<String> =
            (list + entry).filter { (decode(it)?.packedAt ?: 0L) >= now - WINDOW_DAYS * DAY }.takeLast(MAX_ENTRIES)

        fun compute(
            kitchenId: String,
            orderStats: List<String>,
            spotAnswers: List<Long>,
            feedback: List<Pair<Boolean, Boolean>>,
            now: Long,
            tz: TimeZone = TimeZone.getDefault(),
        ): KitchenStats {
            val since = now - WINDOW_DAYS * DAY
            val orders = orderStats.mapNotNull { decode(it) }.filter { it.packedAt in since..now }
            val n = orders.size
            val avg = if (n == 0) 0 else orders.map { it.percent }.average().roundToInt()
            val consistency = if (n == 0) 0 else (orders.count { it.status == "rated" } * 100.0 / n).roundToInt()

            // Spot checks: one request per day the kitchen packed orders, at a time only the app knows
            val days = orders.map { SpotCheck.dayStart(it.packedAt, tz) }.toSet()
            var requested = 0
            var answered = 0
            days.forEach { day ->
                val start = SpotCheck.windowStart(kitchenId, day)
                if (start + SpotCheck.WINDOW_MS > now) return@forEach   // not finished yet
                requested++
                if (spotAnswers.any { it in start..(start + SpotCheck.WINDOW_MS) }) answered++
            }
            val spotRate = if (requested == 0) null else answered * 100.0 / requested

            val percent = if (n == 0) 0 else if (spotRate == null) ((0.75 * avg + 0.15 * consistency) / 0.90).roundToInt()
            else (0.75 * avg + 0.15 * consistency + 0.10 * spotRate).roundToInt()

            val recent = orders.filter { it.packedAt >= now - 7 * DAY }
            val older = orders.filter { it.packedAt < now - 7 * DAY }
            val trend = if (recent.size >= 3 && older.size >= 3) {
                val d = recent.map { it.percent }.average() - older.map { it.percent }.average()
                when { d >= 3 -> "improving"; d <= -3 -> "slipping"; else -> "steady" }
            } else ""

            val fb = feedback.size
            return KitchenStats(
                orders = n, avgScore = avg, consistency = consistency,
                spotAnswered = answered, spotRequested = requested,
                feedbackCount = fb,
                sealIntactPct = if (fb >= 5) (feedback.count { it.first } * 100.0 / fb).roundToInt() else null,
                foodHotPct = if (fb >= 5) (feedback.count { it.second } * 100.0 / fb).roundToInt() else null,
                trend = trend, percent = percent.coerceIn(0, 100), updatedAt = now,
            )
        }
    }
}

/**
 * Random daily spot check: once a day, at a time between 11:00 and 21:30 that only the app
 * knows (derived from the kitchen id and the date), the app asks for a 10-second clip of the
 * wash area and packing table. It counts if recorded within 30 minutes.
 * The same FNV-1a hash is used in the iPhone app, so both apps agree on the time.
 */
object SpotCheck {
    const val WINDOW_MS = 30 * 60_000L
    private const val FIRST_MINUTE = 11 * 60
    private const val SPAN_MINUTES = 10 * 60 + 30

    fun dayStart(millis: Long, tz: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(tz).apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Start of the day's spot-check window. [dayStart] is local midnight. */
    fun windowStart(kitchenId: String, dayStart: Long): Long {
        val dayNumber = (dayStart + 12 * 3_600_000L) / (24 * 3_600_000L)   // stable across time zones
        val minute = FIRST_MINUTE + (fnv1a("$kitchenId|$dayNumber") % SPAN_MINUTES.toUInt()).toInt()
        return dayStart + minute * 60_000L
    }

    /** The open window right now, or null. */
    fun current(kitchenId: String, now: Long, tz: TimeZone = TimeZone.getDefault()): LongRange? {
        val start = windowStart(kitchenId, dayStart(now, tz))
        return if (now in start until start + WINDOW_MS) start until start + WINDOW_MS else null
    }

    fun answered(spotAnswers: List<Long>, window: LongRange) = spotAnswers.any { it in window }

    fun fnv1a(text: String): UInt {
        var h = 2166136261u
        for (b in text.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toUInt() and 0xFFu)
            h *= 16777619u
        }
        return h
    }
}
