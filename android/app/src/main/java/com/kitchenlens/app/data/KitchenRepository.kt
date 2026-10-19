package com.kitchenlens.app.data

import com.kitchenlens.app.util.Dates
import com.kitchenlens.app.util.KitchenStats
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Everything the app needs from a backend. Two implementations:
 *  - [FirebaseRepository]: real cloud backend (Mumbai region) used in production.
 *  - [LocalRepository]: "demo mode" that keeps everything on the phone, so the
 *    app can be installed and tested straight from a GitHub build with no setup.
 */
interface KitchenRepository {
    val isDemo: Boolean

    fun signedInUserId(): String?
    suspend fun signIn(email: String, password: String)
    suspend fun signUp(email: String, password: String)
    fun signOut()

    /** Kitchen linked to the signed-in account, or null if setup is pending. */
    suspend fun loadKitchenId(): String?
    suspend fun createKitchen(name: String, locality: String, city: String, fssai: String, gstin: String, tier: String): String
    /** Changes name, locality, city, FSSAI, GSTIN or tier (keys as in [profileMap]). */
    suspend fun updateKitchenProfile(kitchenId: String, profile: Map<String, String>)
    /**
     * Replaces the kitchen's current hygiene records (empty map = clear them for a new order).
     * Seals already created keep their own frozen copy, so they are not affected.
     */
    suspend fun replaceCurrentRecords(kitchenId: String, latest: Map<String, Map<String, Any?>>)
    fun observeKitchen(kitchenId: String): Flow<Kitchen?>
    suspend fun getKitchen(kitchenId: String): Kitchen

    /** Utensils, staff hygiene and pest control: one photo or clip per record. */
    suspend fun saveLog(
        kitchen: Kitchen,
        section: Section,
        key: String,
        staffName: String,
        media: File,
        mediaType: MediaType,
        details: Map<String, String>,
        label: String,
        photoHash: String?,
        onProgress: (Float) -> Unit,
    )

    /**
     * Food inventory arrival: several categories, several photos per category,
     * and any number of optional supplier bills, each covering one or more categories.
     */
    suspend fun saveInventory(
        kitchen: Kitchen,
        staffName: String,
        supplierType: String?,
        photos: Map<String, List<InventoryPhoto>>,
        bills: List<InventoryBill>,
        onProgress: (Float) -> Unit,
    )

    /** Uploads the packing clip, freezes a hygiene snapshot, and returns the new order. */
    suspend fun createOrder(
        kitchen: Kitchen,
        platform: String,
        orderRef: String,
        packerName: String,
        foodType: String,
        tempC: Double,
        clip: File,
        clipRecordedAt: Long,
        onProgress: (Float) -> Unit,
    ): OrderSummary

    suspend fun recentOrders(kitchenId: String): List<OrderSummary>

    /** The seal code already created for this platform order number, if any. */
    suspend fun findOrderCode(platform: String, orderRef: String): String?

    /** The same public record the customer page shows when the QR is scanned. */
    suspend fun getOrder(code: String): Map<String, Any?>?

    /**
     * Permanently deletes the signed-in account and all its data: kitchen profile,
     * hygiene records, photos, clips, orders and seals (Google Play and App Store
     * both require this). [password] re-confirms the user for cloud accounts.
     */
    suspend fun deleteAccount(password: String)

    /**
     * Starts uploading a photo or clip in the background as soon as it is captured, so that
     * pressing Save later only has to wait for whatever is left. Safe to call more than once.
     */
    fun prefetch(kitchen: Kitchen, file: File, video: Boolean) {}

    /** Customer answers from the last 30 days: (seal intact, food at the right temperature). Live mode only. */
    suspend fun recentFeedback(kitchenId: String, since: Long): List<Pair<Boolean, Boolean>>

    /** Publishes the kitchen's public profile and 30-day rating (kitchen page and badge). */
    suspend fun publishKitchen(kitchen: Kitchen, stats: KitchenStats)
}

/** The kitchen's 30-day rating from its rolling order list, spot checks and customer feedback. */
suspend fun KitchenRepository.kitchenStats(kitchen: Kitchen, now: Long = System.currentTimeMillis()): KitchenStats {
    val feedback = runCatching { recentFeedback(kitchen.id, now - KitchenStats.WINDOW_DAYS * 24 * 3_600_000L) }.getOrDefault(emptyList())
    return KitchenStats.compute(kitchen.id, kitchen.orderStats, kitchen.spotAnswers, feedback, now)
}

/** Public kitchen profile shown on the kitchen page (no private data). */
fun publicKitchenMap(k: Kitchen, stats: KitchenStats): Map<String, Any?> = mapOf(
    "name" to k.name, "locality" to k.locality, "city" to k.city, "fssai" to k.fssai, "gstin" to k.gstin,
    "fssaiHygieneRating" to k.fssaiHygieneRating, "fssaiAuditDate" to k.fssaiAuditDate,
    "notStocked" to k.notStocked.toList(), "stats" to stats.toMap(), "updatedAt" to stats.updatedAt,
)

/** Thrown when a QR seal already exists for the same platform order number. */
class DuplicateOrderException(val existingCode: String, platform: String, orderRef: String) :
    Exception("A QR seal already exists for $platform order $orderRef (code $existingCode). Open it from Recent orders to reprint.")

/**
 * Lookup key that links a seal to the platform order number, e.g. "SWIGGY_158823341".
 * The scan page builds the same key so customers can also find their order by number.
 */
fun orderRefKey(platform: String, orderRef: String): String =
    platform.uppercase().filter { it.isLetterOrDigit() } + "_" + orderRef.uppercase().filter { it.isLetterOrDigit() }

/** Detail keys that are kept in the private log only, never shown to customers. */
val PRIVATE_DETAIL_KEYS = setOf("billUrl", "billUrls", "supplierGstins")

fun profileMap(
    name: String, locality: String, city: String, fssai: String, gstin: String, tier: String,
    fssaiHygieneRating: String = "", fssaiAuditDate: String = "", notStocked: Set<String> = emptySet(),
) = mapOf(
    "name" to name.trim(), "locality" to locality.trim(), "city" to city,
    "fssai" to fssai.trim(), "gstin" to gstin.trim(), "tier" to tier,
    "fssaiHygieneRating" to fssaiHygieneRating.trim(), "fssaiAuditDate" to fssaiAuditDate.trim(),
    // Kept as text so the profile stays a simple map of strings
    "notStocked" to notStocked.sorted().joinToString("|"),
)

/** Reads a list stored either as a real list or as "a|b|c" text. */
fun stringSet(v: Any?): Set<String> = when (v) {
    is List<*> -> v.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }.toSet()
    is String -> v.split("|").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    else -> emptySet()
}

/** Public summary saved under kitchen.latest; supplier bill photos and GSTINs stay private. */
fun publicSummary(
    at: Long,
    mediaUrl: String,
    mediaType: MediaType,
    label: String,
    details: Map<String, String>,
    mediaUrls: List<String> = emptyList(),
    mediaThumbs: List<String> = emptyList(),
): Map<String, Any> {
    val m = mutableMapOf<String, Any>(
        "at" to at,
        "mediaUrl" to mediaUrl,
        "mediaType" to mediaType.value,
        "label" to label,
        "details" to details.filterKeys { it !in PRIVATE_DETAIL_KEYS },
    )
    if (mediaUrls.isNotEmpty()) m["mediaUrls"] = mediaUrls
    // Small preview copies, in the same order as the photos (fast thumbnails on the report)
    if (mediaThumbs.isNotEmpty() && mediaThumbs.any { it.isNotBlank() }) m["mediaThumbs"] = mediaThumbs
    return m
}

/** What gets saved for one inventory category. */
data class InventoryRecord(
    val category: String,
    val key: String,
    val label: String,
    val mediaUrls: List<String>,
    /** Full details for the private log (includes bill photos and supplier GSTINs). */
    val details: Map<String, String>,
)

/**
 * Turns uploaded photo and bill links into one record per category.
 * [billUrls] lines up with [bills]. Pure function, covered by unit tests.
 */
fun buildInventoryRecords(
    photoUrls: Map<String, List<String>>,
    bills: List<InventoryBill>,
    billUrls: List<String>,
    supplierType: String?,
): List<InventoryRecord> = photoUrls.filterValues { it.isNotEmpty() }.map { (cat, urls) ->
    val idx = bills.indices.filter { cat in bills[it].categories }
    val details = mutableMapOf("category" to cat, "photoCount" to urls.size.toString())
    if (!supplierType.isNullOrBlank()) details["supplierType"] = supplierType
    if (idx.isNotEmpty()) {
        details["billCount"] = idx.size.toString()
        Dates.latest(idx.map { bills[it].billDate })?.let { details["billDate"] = it }
        val gstins = idx.map { bills[it].supplierGstin }.filter { it.isNotBlank() }.distinct()
        if (gstins.isNotEmpty()) {
            details["supplierGst"] = "registered"
            details["supplierGstins"] = gstins.joinToString(",")
        }
        details["billUrls"] = idx.mapNotNull { billUrls.getOrNull(it) }.joinToString(",")
    }
    InventoryRecord(cat, latestKey(Section.INVENTORY, cat), "$cat received", urls, details)
}

@Suppress("UNCHECKED_CAST")
fun kitchenFromMap(id: String, m: Map<String, Any?>): Kitchen = Kitchen(
    id = id,
    name = m["name"] as? String ?: "",
    locality = m["locality"] as? String ?: "",
    city = m["city"] as? String ?: "",
    fssai = m["fssai"] as? String ?: "",
    gstin = m["gstin"] as? String ?: "",
    tier = m["tier"] as? String ?: "",
    latest = (m["latest"] as? Map<String, Map<String, Any?>>) ?: emptyMap(),
    recentHashes = (m["recentHashes"] as? List<String>) ?: emptyList(),
    notStocked = stringSet(m["notStocked"]),
    fssaiHygieneRating = m["fssaiHygieneRating"] as? String ?: "",
    fssaiAuditDate = m["fssaiAuditDate"] as? String ?: "",
    spotAnswers = (m["spotAnswers"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() } ?: emptyList(),
    orderStats = (m["orderStats"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
)

fun orderSummaryFromMap(code: String, m: Map<String, Any?>) = OrderSummary(
    code = code,
    platform = m["platform"] as? String ?: "",
    orderRefLast4 = m["orderRefLast4"] as? String ?: "",
    orderRef = m["orderRef"] as? String ?: "",
    tempC = (m["tempC"] as? Number)?.toDouble() ?: 0.0,
    score = (m["score"] as? Number)?.toInt() ?: 0,
    packedAtMillis = (m["packedAtMillis"] as? Number)?.toLong() ?: 0L,
)

/** Builds the public order record shared by both backends. */
fun buildOrderRecord(
    kitchen: Kitchen,
    platform: String,
    orderRef: String,
    packerName: String,
    foodType: String,
    tempC: Double,
    tempOk: Boolean,
    clipUrl: String,
    packedAt: Long,
    score: Int,
    clipRecordedAt: Long = packedAt,
    scoreBreakdown: Map<String, Any?> = emptyMap(),
    kitchenStats: Map<String, Any?> = emptyMap(),
): Map<String, Any?> = mapOf(
    "clipRecordedAt" to clipRecordedAt,
    "kitchenId" to kitchen.id,
    "kitchenName" to kitchen.name,
    "kitchenLocality" to kitchen.locality,
    "kitchenCity" to kitchen.city,
    "fssai" to kitchen.fssai,
    "gstin" to kitchen.gstin,
    "platform" to platform,
    "orderRef" to orderRef.trim(),
    "orderRefKey" to orderRefKey(platform, orderRef),
    "orderRefLast4" to orderRef.filter { it.isLetterOrDigit() }.takeLast(4),
    // First name only, for staff privacy
    "packerName" to packerName.trim().split(" ").first(),
    "foodType" to foodType,
    "tempC" to tempC,
    "tempOk" to tempOk,
    "clipUrl" to clipUrl,
    "packedAtMillis" to packedAt,
    "score" to score,
    "scoreBreakdown" to scoreBreakdown,
    "scoreComplete" to (scoreBreakdown["complete"] as? Boolean ?: false),
    "safetyGatePassed" to (scoreBreakdown["safetyGatePassed"] as? Boolean ?: false),
    "scoreStatus" to (scoreBreakdown["status"] as? String ?: "incomplete"),
    "rating" to (scoreBreakdown["rating"] as? String ?: ""),
    "stars" to (scoreBreakdown["stars"] as? Number ?: 0.0),
    "notStocked" to kitchen.notStocked.toList(),
    "fssaiHygieneRating" to kitchen.fssaiHygieneRating,
    "fssaiAuditDate" to kitchen.fssaiAuditDate,
    // The kitchen's 30-day rating at the moment of packing (the report also loads the live one)
    "kitchenStats" to kitchenStats,
    // Spot-check answers are not shown in the order timeline
    "snapshot" to kitchen.latest.filterKeys { it != "SPOT" },
)
