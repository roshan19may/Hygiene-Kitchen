package com.kitchenlens.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * An order that is being packed but has no QR seal yet. Saved on the phone so
 * staff can stop half-way (or the app can close) and continue later.
 */
data class OrderDraft(
    val platform: String = "",
    val orderRef: String = "",
    val packer: String = "",
    val foodType: String = "Hot",
    val tempText: String = "",
    val clipPath: String? = null,
    val clipSeconds: Int = 0,
    /** When the packing clip started recording (shown as its timestamp). */
    val clipRecordedAt: Long = 0L,
    val savedAt: Long = 0L,
) {
    val isEmpty: Boolean
        get() = platform.isBlank() && orderRef.isBlank() && tempText.isBlank() && clipPath == null
}

/** Stores at most one draft per phone, in the app's private storage. */
class DraftStore(context: Context) {
    private val dir = File(context.filesDir, "draft").apply { mkdirs() }
    private val file = File(dir, "order.json")

    fun load(): OrderDraft? = runCatching {
        if (!file.exists()) return null
        val j = JSONObject(file.readText())
        OrderDraft(
            platform = j.optString("platform"),
            orderRef = j.optString("orderRef"),
            packer = j.optString("packer"),
            foodType = j.optString("foodType", "Hot"),
            tempText = j.optString("tempText"),
            clipPath = j.optString("clipPath").ifBlank { null }?.takeIf { File(it).exists() },
            clipSeconds = j.optInt("clipSeconds"),
            clipRecordedAt = j.optLong("clipRecordedAt"),
            savedAt = j.optLong("savedAt"),
        )
    }.getOrNull()

    /** Saves the draft; a newly recorded clip is moved out of the temporary cache. */
    fun save(draft: OrderDraft): OrderDraft {
        var clipPath = draft.clipPath
        if (clipPath != null && !clipPath.startsWith(dir.absolutePath)) {
            val src = File(clipPath)
            if (src.exists()) {
                val dest = File(dir, "clip_${System.currentTimeMillis()}.mp4")
                // Move (instant) rather than copy a multi-MB clip; copy only if moving fails
                if (!src.renameTo(dest)) src.copyTo(dest, overwrite = true)
                clipPath = dest.absolutePath
            }
        }
        // Remove clips that no longer belong to the draft
        dir.listFiles { f -> f.name.endsWith(".mp4") && f.absolutePath != clipPath }?.forEach { it.delete() }
        val saved = draft.copy(clipPath = clipPath, savedAt = System.currentTimeMillis())
        file.writeText(
            JSONObject()
                .put("platform", saved.platform)
                .put("orderRef", saved.orderRef)
                .put("packer", saved.packer)
                .put("foodType", saved.foodType)
                .put("tempText", saved.tempText)
                .put("clipPath", saved.clipPath ?: "")
                .put("clipSeconds", saved.clipSeconds)
                .put("clipRecordedAt", saved.clipRecordedAt)
                .put("savedAt", saved.savedAt)
                .toString()
        )
        return saved
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Forgets the draft but keeps its clip on disk, so "Undo" can bring it back. */
    fun clearKeepingClip() {
        file.delete()
    }
}
