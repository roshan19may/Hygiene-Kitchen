package com.kitchenlens.app.data

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
    suspend fun createKitchen(name: String, locality: String, city: String, fssai: String, tier: String): String
    fun observeKitchen(kitchenId: String): Flow<Kitchen?>
    suspend fun getKitchen(kitchenId: String): Kitchen

    suspend fun saveLog(
        kitchen: Kitchen,
        section: Section,
        key: String,
        staffName: String,
        media: File,
        mediaType: MediaType,
        billPhoto: File?,
        details: Map<String, String>,
        label: String,
        photoHash: String?,
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

/** Public summary saved under kitchen.latest; supplier bill photos stay private. */
fun publicSummary(at: Long, mediaUrl: String, mediaType: MediaType, label: String, details: Map<String, String>) =
    mapOf(
        "at" to at,
        "mediaUrl" to mediaUrl,
        "mediaType" to mediaType.value,
        "label" to label,
        "details" to details.filterKeys { it != "billUrl" },
    )

@Suppress("UNCHECKED_CAST")
fun kitchenFromMap(id: String, m: Map<String, Any?>): Kitchen = Kitchen(
    id = id,
    name = m["name"] as? String ?: "",
    locality = m["locality"] as? String ?: "",
    city = m["city"] as? String ?: "",
    fssai = m["fssai"] as? String ?: "",
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
