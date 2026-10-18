package com.kitchenlens.app.data

import android.content.Context
import android.net.Uri
import com.kitchenlens.app.util.HygieneScore
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

    override suspend fun createKitchen(name: String, locality: String, city: String, fssai: String, tier: String): String {
        val id = "demo-" + UUID.randomUUID().toString().take(8)
        state.put("kitchen", toJson(mapOf(
            "id" to id, "name" to name.trim(), "locality" to locality.trim(), "city" to city,
            "fssai" to fssai.trim(), "tier" to tier, "latest" to emptyMap<String, Any>(),
            "recentHashes" to emptyList<String>(),
        )))
        save()
        return id
    }

    @Suppress("UNCHECKED_CAST")
    private fun kitchenMap(): MutableMap<String, Any?> =
        (fromJson(state.getJSONObject("kitchen")) as Map<String, Any?>).toMutableMap()

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
    override suspend fun saveLog(
        kitchen: Kitchen, section: Section, key: String, staffName: String, media: File,
        mediaType: MediaType, billPhoto: File?, details: Map<String, String>, label: String,
        photoHash: String?, onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val mediaUrl = copyIn(media, if (mediaType == MediaType.VIDEO) "mp4" else "jpg")
        onProgress(1f)
        val billUrl = billPhoto?.let { copyIn(it, "jpg") }
        val allDetails = if (billUrl != null) details + ("billUrl" to billUrl) else details
        val now = System.currentTimeMillis()

        val k = kitchenMap()
        val latest = ((k["latest"] as? Map<String, Any?>) ?: emptyMap()).toMutableMap()
        latest[key] = publicSummary(now, mediaUrl, mediaType, label, allDetails)
        k["latest"] = latest
        if (photoHash != null) k["recentHashes"] = (listOf(photoHash) + kitchen.recentHashes).take(60)
        state.put("kitchen", toJson(k))

        val logs = state.optJSONArray("logs") ?: JSONArray()
        logs.put(toJson(mapOf("section" to section.name, "key" to key, "staffName" to staffName,
            "mediaUrl" to mediaUrl, "details" to allDetails, "label" to label, "capturedAtMillis" to now)))
        state.put("logs", logs)
        save()
        kitchenFlow.value = kitchenFromMap(kitchen.id, k)
    }

    override suspend fun createOrder(
        kitchen: Kitchen, platform: String, orderRef: String, packerName: String,
        foodType: String, tempC: Double, clip: File, onProgress: (Float) -> Unit,
    ): OrderSummary = withContext(Dispatchers.IO) {
        val code = QrLabel.newCode()
        val clipUrl = copyIn(clip, "mp4")
        onProgress(1f)
        val fresh = getKitchen(kitchen.id)
        val now = System.currentTimeMillis()
        val tempOk = HygieneScore.temperatureOk(foodType, tempC)
        val score = HygieneScore.compute(fresh.latest, tempOk, now)
        val record = buildOrderRecord(fresh, platform, orderRef, packerName, foodType, tempC, tempOk, clipUrl, now, score)
        val orders = state.optJSONObject("orders") ?: JSONObject()
        orders.put(code, toJson(record))
        state.put("orders", orders)
        save()
        orderSummaryFromMap(code, record)
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
