package com.kitchenlens.app.data

import android.content.Context
import android.net.Uri
import com.kitchenlens.app.util.CloudMedia
import com.kitchenlens.app.util.HygieneScore
import com.kitchenlens.app.util.KitchenStats
import com.kitchenlens.app.util.QrLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Demo mode: no internet or Firebase needed. Data and clips are stored on the
 * phone only, so customers cannot scan these QR codes, but the kitchen can use
 * "Preview customer view" to see exactly what a customer would see.
 */
class LocalRepository(context: Context) : KitchenRepository {
    private val dir = File(context.filesDir, "demo").apply { mkdirs() }
    private val mediaDir = File(dir, "media").apply { mkdirs() }
    private val stateFile = File(dir, "state.json")
    private val state: JSONObject =
        if (stateFile.exists()) JSONObject(stateFile.readText()) else JSONObject()
    private val kitchenFlow = MutableStateFlow<Kitchen?>(null)

    /** Optional free cloud storage so photos and clips show when a seal is scanned. */
    val cloud = CloudMedia(context)

    /** Best-effort early upload, so creating the QR later is quick. Errors are retried at QR time. */
    private suspend fun preUpload(urls: List<String>, video: Boolean) {
        if (!cloud.isConfigured) return
        urls.forEach { u -> runCatching { cloud.ensureUploaded(u, video) } }
    }

    /** Adds Cloudinary IDs to every photo/clip in the hygiene snapshot (uploads what's missing). */
    private suspend fun withCloudIds(latest: Map<String, Map<String, Any?>>, onStep: () -> Unit): Map<String, Map<String, Any?>> =
        latest.mapValues { (_, e) ->
            @Suppress("UNCHECKED_CAST")
            val urls = (e["mediaUrls"] as? List<*>)?.filterIsInstance<String>()?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(e["mediaUrl"] as? String)
            val video = e["mediaType"] == MediaType.VIDEO.value
            val ids = urls.map { u -> cloud.ensureUploaded(u, video).also { onStep() } }
            e + ("cloudIds" to ids)
        }

    override val isDemo = true

    private fun save() = stateFile.writeText(state.toString())

    override fun signedInUserId(): String? = state.optString("user").ifBlank { null }

    override suspend fun signIn(email: String, password: String) {
        require(email.contains("@")) { "Enter an email address" }
        state.put("user", email.trim()); save()
    }

    override suspend fun signUp(email: String, password: String) = signIn(email, password)

    override fun signOut() { state.remove("user"); save() }

    override suspend fun loadKitchenId(): String? =
        state.optJSONObject("kitchen")?.optString("id")?.ifBlank { null }

    override suspend fun createKitchen(name: String, locality: String, city: String, fssai: String, gstin: String, tier: String): String {
        val id = "demo-" + UUID.randomUUID().toString().take(8)
        state.put("kitchen", toJson(mapOf(
            "id" to id, "latest" to emptyMap<String, Any>(), "recentHashes" to emptyList<String>(),
        ) + profileMap(name, locality, city, fssai, gstin, tier)))
        save()
        return id
    }

    @Suppress("UNCHECKED_CAST")
    private fun kitchenMap(): MutableMap<String, Any?> =
        (fromJson(state.getJSONObject("kitchen")) as Map<String, Any?>).toMutableMap()

    override suspend fun updateKitchenProfile(kitchenId: String, profile: Map<String, String>) {
        val k = kitchenMap()
        k.putAll(profile)
        state.put("kitchen", toJson(k))
        save()
        kitchenFlow.value = kitchenFromMap(kitchenId, k)
    }

    override suspend fun replaceCurrentRecords(kitchenId: String, latest: Map<String, Map<String, Any?>>) {
        val k = kitchenMap()
        k["latest"] = latest
        state.put("kitchen", toJson(k))
        save()
        kitchenFlow.value = kitchenFromMap(kitchenId, k)
    }

    override fun observeKitchen(kitchenId: String): Flow<Kitchen?> {
        kitchenFlow.value = kitchenFromMap(kitchenId, kitchenMap())
        return kitchenFlow
    }

    override suspend fun getKitchen(kitchenId: String): Kitchen = kitchenFromMap(kitchenId, kitchenMap())

    private fun copyIn(file: File, ext: String): String {
        val dest = File(mediaDir, UUID.randomUUID().toString() + "." + ext)
        file.copyTo(dest, overwrite = true)
        return Uri.fromFile(dest).toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun commit(kitchen: Kitchen, summaries: Map<String, Any>, newHashes: List<String>, logEntries: List<Map<String, Any?>>) {
        val k = kitchenMap()
        val latest = ((k["latest"] as? Map<String, Any?>) ?: emptyMap()).toMutableMap()
        latest.putAll(summaries)
        k["latest"] = latest
        if (newHashes.isNotEmpty()) k["recentHashes"] = (newHashes + kitchen.recentHashes).take(60)
        state.put("kitchen", toJson(k))
        val logs = state.optJSONArray("logs") ?: JSONArray()
        logEntries.forEach { logs.put(toJson(it)) }
        state.put("logs", logs)
        save()
        kitchenFlow.value = kitchenFromMap(kitchen.id, k)
    }

    override suspend fun saveLog(
        kitchen: Kitchen, section: Section, key: String, staffName: String, media: File,
        mediaType: MediaType, details: Map<String, String>, label: String,
        photoHash: String?, onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val mediaUrl = copyIn(media, if (mediaType == MediaType.VIDEO) "mp4" else "jpg")
        preUpload(listOf(mediaUrl), mediaType == MediaType.VIDEO)
        onProgress(1f)
        val now = System.currentTimeMillis()
        if (section == Section.SPOT) {
            // Spot checks count towards the kitchen rating; they don't replace a hygiene record
            val k = kitchenMap()
            k["spotAnswers"] = (kitchenFromMap(kitchen.id, k).spotAnswers + now).takeLast(60)
            state.put("kitchen", toJson(k))
            save()
            kitchenFlow.value = kitchenFromMap(kitchen.id, k)
            return@withContext
        }
        commit(
            kitchen,
            mapOf(key to publicSummary(now, mediaUrl, mediaType, label, details + mapOf("recordedBy" to staffName.trim().substringBefore(" ")))),
            listOfNotNull(photoHash),
            listOf(mapOf("section" to section.name, "key" to key, "staffName" to staffName,
                "mediaUrl" to mediaUrl, "details" to details, "label" to label, "capturedAtMillis" to now)),
        )
    }

    override suspend fun saveInventory(
        kitchen: Kitchen, staffName: String, supplierType: String?,
        photos: Map<String, List<InventoryPhoto>>, bills: List<InventoryBill>,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val photoUrls = photos.mapValues { (_, list) -> list.map { copyIn(it.file, "jpg") } }
        val billUrls = bills.map { copyIn(it.file, "jpg") }
        preUpload(photoUrls.values.flatten(), video = false)
        onProgress(1f)
        val now = System.currentTimeMillis()
        val records = buildInventoryRecords(photoUrls, bills, billUrls, supplierType)
        commit(
            kitchen,
            records.associate { r ->
                r.key to publicSummary(now, r.mediaUrls.first(), MediaType.PHOTO, r.label, r.details + mapOf("recordedBy" to staffName.trim().substringBefore(" ")), r.mediaUrls)
            },
            photos.values.flatten().map { it.hash },
            records.map { r ->
                mapOf("section" to Section.INVENTORY.name, "key" to r.key, "staffName" to staffName,
                    "mediaUrls" to r.mediaUrls, "details" to r.details, "label" to r.label, "capturedAtMillis" to now)
            },
        )
    }

    override suspend fun createOrder(
        kitchen: Kitchen, platform: String, orderRef: String, packerName: String,
        foodType: String, tempC: Double, clip: File, clipRecordedAt: Long, onProgress: (Float) -> Unit,
    ): OrderSummary = withContext(Dispatchers.IO) {
        findOrderCode(platform, orderRef)?.let { throw DuplicateOrderException(it, platform, orderRef) }
        val code = QrLabel.newCode()
        val clipUrl = copyIn(clip, "mp4")
        var fresh = getKitchen(kitchen.id)
        val now = System.currentTimeMillis()
        val tempOk = HygieneScore.temperatureOk(foodType, tempC)
        var cloudExtras = emptyMap<String, Any?>()
        if (cloud.isConfigured) {
            // Upload the packing clip and any not-yet-uploaded hygiene photos/clips
            val total = 1 + fresh.latest.values.sumOf { e ->
                ((e["mediaUrls"] as? List<*>)?.size?.takeIf { it > 0 } ?: 1)
            }
            var done = 0
            val step = { done++; onProgress(done.toFloat() / total) }
            val clipId = cloud.ensureUploaded(clipUrl, video = true).also { step() }
            fresh = fresh.copy(latest = withCloudIds(fresh.latest) { step() })
            cloudExtras = mapOf("cloudName" to cloud.cloudName, "clipCloudId" to clipId)
        }
        onProgress(1f)
        val scoreResult = HygieneScore.computeWeighted(fresh.latest, foodType, tempC, packerName, clipUrl, now, fresh.notStocked)
        val score = scoreResult.percent
        val stats = kitchenStats(fresh, now)
        val record = buildOrderRecord(fresh, platform, orderRef, packerName, foodType, tempC, tempOk, clipUrl, now, score,
            clipRecordedAt, scoreResult.toMap(), stats.toMap()) + cloudExtras
        val k = kitchenMap()
        k["orderStats"] = KitchenStats.append(fresh.orderStats, KitchenStats.encode(now, score, scoreResult.status), now)
        state.put("kitchen", toJson(k))
        kitchenFlow.value = kitchenFromMap(kitchen.id, k)
        val orders = state.optJSONObject("orders") ?: JSONObject()
        orders.put(code, toJson(record))
        state.put("orders", orders)
        val refs = state.optJSONObject("orderRefs") ?: JSONObject()
        if (orderRef.isNotBlank()) refs.put(orderRefKey(platform, orderRef), code)
        state.put("orderRefs", refs)
        save()
        orderSummaryFromMap(code, record)
    }

    /**
     * For a seal created before cloud storage was connected: uploads its photos and
     * clips now and returns the updated order, so a fresh QR can be printed.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun attachCloudMedia(code: String, onProgress: (Float) -> Unit): Map<String, Any?> = withContext(Dispatchers.IO) {
        require(cloud.isConfigured) { "Connect cloud storage in Kitchen details first." }
        val o = getOrder(code) ?: error("Order not found on this phone.")
        val snapshot = (o["snapshot"] as? Map<String, Map<String, Any?>>) ?: emptyMap()
        val total = 1 + snapshot.values.sumOf { e -> ((e["mediaUrls"] as? List<*>)?.size?.takeIf { it > 0 } ?: 1) }
        var done = 0
        val step = { done++; onProgress(done.toFloat() / total) }
        val clipId = cloud.ensureUploaded(o["clipUrl"] as? String ?: error("Packing clip missing"), video = true).also { step() }
        val updated = o + mapOf(
            "snapshot" to withCloudIds(snapshot) { step() },
            "cloudName" to cloud.cloudName,
            "clipCloudId" to clipId,
        )
        val orders = state.optJSONObject("orders") ?: JSONObject()
        orders.put(code, toJson(updated))
        state.put("orders", orders)
        save()
        updated
    }

    override suspend fun deleteAccount(password: String) = withContext(Dispatchers.IO) {
        // Demo mode keeps everything on this phone: remove it all.
        dir.deleteRecursively()
        dir.mkdirs(); mediaDir.mkdirs()
        state.keys().asSequence().toList().forEach { state.remove(it) }
        cloud.forgetAll()
        save()
        kitchenFlow.value = null
    }

    /** Demo mode has no server for customers to send feedback to. */
    override suspend fun recentFeedback(kitchenId: String, since: Long): List<Pair<Boolean, Boolean>> = emptyList()

    override suspend fun publishKitchen(kitchen: Kitchen, stats: KitchenStats) {
        state.put("publicKitchen", toJson(publicKitchenMap(kitchen, stats)))
        save()
    }

    @Suppress("UNCHECKED_CAST")
    fun publicKitchen(): Map<String, Any?>? = state.optJSONObject("publicKitchen")?.let { fromJson(it) as Map<String, Any?> }

    override suspend fun findOrderCode(platform: String, orderRef: String): String? {
        if (orderRef.isBlank()) return null
        return state.optJSONObject("orderRefs")?.optString(orderRefKey(platform, orderRef))?.ifBlank { null }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun recentOrders(kitchenId: String): List<OrderSummary> {
        val orders = state.optJSONObject("orders") ?: return emptyList()
        return orders.keys().asSequence()
            .map { code -> orderSummaryFromMap(code, fromJson(orders.getJSONObject(code)) as Map<String, Any?>) }
            .sortedByDescending { it.packedAtMillis }
            .take(50).toList()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun getOrder(code: String): Map<String, Any?>? =
        state.optJSONObject("orders")?.optJSONObject(code)?.let { fromJson(it) as Map<String, Any?> }

    companion object {
        fun toJson(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
            is List<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
            else -> v
        }

        fun fromJson(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONObject -> v.keys().asSequence().associateWith { fromJson(v.get(it)) }
            is JSONArray -> (0 until v.length()).map { fromJson(v.get(it)) }
            is Int -> v.toLong()
            else -> v
        }
    }
}
