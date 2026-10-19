package com.kitchenlens.app.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Free cloud storage for photos and clips in demo mode, using the kitchen's own
 * Cloudinary account (free plan, no card). A QR code can't hold media, so each
 * file is uploaded once and the QR carries only its short ID.
 *
 * Setup in Cloudinary: Settings > Upload > Upload presets > Add, Signing mode "Unsigned".
 * Then enter the cloud name and preset name in the app (Kitchen details).
 */
class CloudMedia(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_media", Context.MODE_PRIVATE)
    private val cacheDir = context.cacheDir

    val cloudName: String get() = prefs.getString("cloudName", "").orEmpty()
    val preset: String get() = prefs.getString("preset", "").orEmpty()
    val isConfigured: Boolean get() = cloudName.isNotBlank() && preset.isNotBlank()

    /** Forgets the cloud account and every uploaded-file record (account deletion). */
    fun forgetAll() {
        prefs.edit().clear().apply()
    }

    fun saveSettings(cloudName: String, preset: String) {
        prefs.edit().putString("cloudName", cloudName.trim()).putString("preset", preset.trim()).apply()
    }

    /** ID already uploaded for this local file, if any. */
    fun cachedId(localUrl: String): String? = prefs.getString("id:$localUrl", null)

    /**
     * Uploads a local file (path or file:// URL) once and returns its Cloudinary ID.
     * Later calls for the same file return the cached ID without uploading again.
     */
    suspend fun ensureUploaded(localUrl: String, video: Boolean): String {
        cachedId(localUrl)?.let { return it }
        // Already uploading in the background? Wait for that instead of sending it twice.
        pending.remove(localUrl)?.let { started -> runCatching { started.await() }.getOrNull()?.let { return it } }
        return slots.withPermit { uploadAndRemember(localUrl, video) }
    }

    private suspend fun uploadAndRemember(localUrl: String, video: Boolean): String {
        cachedId(localUrl)?.let { return it }
        val path = if (localUrl.startsWith("file:")) Uri.parse(localUrl).path.orEmpty() else localUrl
        val file = File(path)
        require(file.exists()) { "A photo or clip is missing from this phone. Please record it again." }
        val id = upload(file, video)
        prefs.edit().putString("id:$localUrl", id).apply()
        return id
    }

    // ---- Background uploads (start when a photo is taken; up to 3 at a time) ----
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, Deferred<String>>()
    private val slots = Semaphore(3)

    /** Starts uploading in the background; [ensureUploaded] later reuses the result. */
    fun startUpload(localUrl: String, video: Boolean) {
        if (!isConfigured || cachedId(localUrl) != null || pending.containsKey(localUrl)) return
        pending[localUrl] = background.async { slots.withPermit { uploadAndRemember(localUrl, video) } }
    }

    /** The file at [fromUrl] was copied to [toUrl]: both share one upload. */
    fun alias(toUrl: String, fromUrl: String) {
        cachedId(fromUrl)?.let { prefs.edit().putString("id:$toUrl", it).apply(); return }
        val started = pending[fromUrl] ?: return
        pending[toUrl] = background.async { started.await().also { prefs.edit().putString("id:$toUrl", it).apply() } }
    }

    /** Uploads a tiny test image to check the cloud name and preset. */
    suspend fun testConnection(cloudName: String, preset: String): String {
        val f = File(cacheDir, "kl_test.png")
        withContext(Dispatchers.IO) {
            val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(0xFF0F5C4A.toInt())
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return upload(f, video = false, cloud = cloudName.trim(), uploadPreset = preset.trim())
    }

    private suspend fun upload(
        file: File, video: Boolean, cloud: String = cloudName, uploadPreset: String = preset,
    ): String = withContext(Dispatchers.IO) {
        require(cloud.isNotBlank() && uploadPreset.isNotBlank()) { "Cloud storage is not set up" }
        val boundary = "----kitchenlens" + System.currentTimeMillis()
        val endpoint = "https://api.cloudinary.com/v1_1/$cloud/${if (video) "video" else "image"}/upload"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            DataOutputStream(conn.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                }
                field("upload_preset", uploadPreset)
                field("public_id", "kl" + shortId())
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
                out.writeBytes("Content-Type: ${if (video) "video/mp4" else if (file.name.endsWith(".png")) "image/png" else "image/jpeg"}\r\n\r\n")
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (code !in 200..299) {
                val msg = json?.optJSONObject("error")?.optString("message").orEmpty()
                error(
                    when {
                        code == 401 || msg.contains("preset", ignoreCase = true) ->
                            "Cloud storage rejected the upload preset. Check it exists and is set to Unsigned."
                        code == 404 -> "Cloud name not found. Check the cloud name in Kitchen details."
                        else -> "Upload failed ($code) ${msg.take(120)}"
                    }
                )
            }
            json?.optString("public_id")?.takeIf { it.isNotBlank() } ?: error("Upload failed: no file ID returned")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val CHARS = "abcdefghijkmnpqrstuvwxyz23456789"
        private val random = SecureRandom()
        fun shortId(): String = buildString { repeat(8) { append(CHARS[random.nextInt(CHARS.length)]) } }
    }
}
