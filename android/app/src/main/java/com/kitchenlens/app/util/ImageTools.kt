package com.kitchenlens.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ImageTools {

    /**
     * Shrinks a camera photo to at most [maxSide] px, fixes rotation, and writes a JPEG.
     * Returns the compressed file and its fingerprint (for duplicate detection).
     * 1280 px at quality 78 is about 150–250 KB: sharp on any phone screen and quick to upload on 4G.
     */
    fun compress(input: File, outDir: File, maxSide: Int = MAX_SIDE, takenAt: Long = System.currentTimeMillis()): Pair<File, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2

        val decoded = BitmapFactory.decodeFile(
            input.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true }
        ) ?: error("Could not read the photo")

        val rotation = when (ExifInterface(input.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        var bmp = decoded
        // Scale and rotate in one step (one bitmap copy instead of two)
        val scale = (maxSide.toFloat() / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
        if (rotation != 0f || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            bmp = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (bmp !== decoded) decoded.recycle()
        }

        val hash = dHash(bmp) // fingerprint the scene before the timestamp is added
        val stamped = stamp(bmp, takenAt)
        val out = File(outDir, "photo_${System.currentTimeMillis()}.jpg")
        FileOutputStream(out).use { stamped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (stamped !== bmp) bmp.recycle()
        stamped.recycle()
        input.delete() // the raw camera file is no longer needed
        return out to hash
    }

    /**
     * Small preview copy (about 20 KB) uploaded next to each photo, so reports and the app
     * show thumbnails instantly instead of downloading full photos.
     */
    fun thumbnail(photo: File, outDir: File, maxSide: Int = THUMB_SIDE): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val src = BitmapFactory.decodeFile(photo.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Could not read the photo")
        val s = (maxSide.toFloat() / maxOf(src.width, src.height)).coerceAtMost(1f)
        val small = if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true) else src
        val out = File(outDir, photo.nameWithoutExtension + "_t.jpg")
        FileOutputStream(out).use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        if (small !== src) src.recycle()
        small.recycle()
        return out
    }

    const val MAX_SIDE = 1280
    const val JPEG_QUALITY = 78
    const val THUMB_SIDE = 360

    /** e.g. "18 Oct 2026, 07:42:10 PM IST" in the phone's time zone. */
    fun stampText(at: Long): String =
        SimpleDateFormat("dd MMM yyyy, hh:mm:ss a z", Locale.ENGLISH).format(Date(at))

    /**
     * Burns the date and time into the bottom of the photo, so the time it was
     * taken is visible wherever the photo is shown (app, customer page, downloads).
     */
    fun stamp(src: Bitmap, at: Long): Bitmap {
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val text = "KitchenLens · " + stampText(at)
        val size = (bmp.width * 0.034f).coerceIn(14f, 64f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = size; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val pad = size * 0.5f
        val barH = size + pad * 2
        val bar = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        c.drawRect(0f, bmp.height - barH, bmp.width.toFloat(), bmp.height.toFloat(), bar)
        c.drawText(text, pad, bmp.height - pad - paint.descent(), paint)
        return bmp
    }

    /**
     * 64-bit "difference hash". Two photos of the same scene give hashes that
     * differ in only a few bits, so re-uploading an old photo is caught.
     */
    fun dHash(src: Bitmap): String {
        val small = Bitmap.createScaledBitmap(src, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = gray(small.getPixel(x, y))
                val right = gray(small.getPixel(x + 1, y))
                if (left > right) hash = hash or (1L shl bit)
                bit++
            }
        }
        return java.lang.Long.toHexString(hash)
    }

    private fun gray(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** True when [hash] is almost identical to one of the kitchen's recent photos. */
    fun isDuplicate(hash: String, recent: List<String>, maxBits: Int = 5): Boolean {
        val h = java.lang.Long.parseUnsignedLong(hash, 16)
        return recent.any { r ->
            runCatching { java.lang.Long.bitCount(h xor java.lang.Long.parseUnsignedLong(r, 16)) <= maxBits }
                .getOrDefault(false)
        }
    }
}
