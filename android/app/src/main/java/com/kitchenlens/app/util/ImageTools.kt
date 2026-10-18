package com.kitchenlens.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

object ImageTools {

    /**
     * Shrinks a camera photo to at most [maxSide] px, fixes rotation, and writes a JPEG.
     * Returns the compressed file and its fingerprint (for duplicate detection).
     */
    fun compress(input: File, outDir: File, maxSide: Int = 1600): Pair<File, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2

        val decoded = BitmapFactory.decodeFile(
            input.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: error("Could not read the photo")

        val rotation = when (ExifInterface(input.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        var bmp = decoded
        if (rotation != 0f) {
            val m = Matrix().apply { postRotate(rotation) }
            bmp = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        }
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        }

        val out = File(outDir, "photo_${System.currentTimeMillis()}.jpg")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return out to dHash(bmp)
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
