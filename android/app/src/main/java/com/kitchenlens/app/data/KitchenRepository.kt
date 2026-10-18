package com.kitchenlens.app.data

import com.kitchenlens.app.util.Dates
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
        onProgress: (Float) -> Unit,
    ): OrderSummary

    suspend fun recentOrders(kitchenId: String): List<OrderSummary>

    /** The same public record the customer page shows when the QR is scanned. */
    suspend fun getOrder(code: String): Map<String, Any?>?
}

/** Detail keys that are kept in the private log only, never shown to customers. */
val PRIVATE_DETAIL_KEYS = setOf("billUrl", "billUrls", "supplierGstins")

fun profileMap(name: String, locality: String, city: String, fssai: String, gstin: String, tier: String) = mapOf(
    "name" to name.trim(), "locality" to locality.trim(), "city" to city,
    "fssai" to fssai.trim(), "gstin" to gstin.trim(), "tier" to tier,
)

/** Public summary saved under kitchen.latest; supplier bill photos and GSTINs stay private. */
fun publicSummary(
    at: Long,
    mediaUrl: String,
    mediaType: MediaType,
    label: String,
    details: Map<String, String>,
    mediaUrls: List<String> = emptyList(),
): Map<String, Any> {
    val m = mutableMapOf<String, Any>(
        "at" to at,
        "mediaUrl" to mediaUrl,
        "mediaType" to mediaType.value,
        "label" to label,
        "details" to details.filterKeys { it !in PRIVATE_DETAIL_KEYS },
    )
    if (mediaUrls.isNotEmpty()) m["mediaUrls"] = mediaUrls
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
)

fun orderSummaryFromMap(code: String, m: Map<String, Any?>) = OrderSummary(
    code = code,
    platform = m["platform"] as? String ?: "",
    orderRefLast4 = m["orderRefLast4"] as? String ?: "",
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
): Map<String, Any?> = mapOf(
    "kitchenId" to kitchen.id,
    "kitchenName" to kitchen.name,
    "kitchenLocality" to kitchen.locality,
    "kitchenCity" to kitchen.city,
    "fssai" to kitchen.fssai,
    "gstin" to kitchen.gstin,
    "platform" to platform,
    "orderRefLast4" to orderRef.filter { it.isLetterOrDigit() }.takeLast(4),
    // First name only, for staff privacy
    "packerName" to packerName.trim().split(" ").first(),
    "foodType" to foodType,
    "tempC" to tempC,
    "tempOk" to tempOk,
    "clipUrl" to clipUrl,
    "packedAtMillis" to packedAt,
    "score" to score,
    "snapshot" to kitchen.latest,
)
