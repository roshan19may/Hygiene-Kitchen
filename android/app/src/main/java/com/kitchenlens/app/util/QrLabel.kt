package com.kitchenlens.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

object QrLabel {
    // No 0/O, 1/I/L so codes can be read aloud or typed if a sticker is damaged.
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private val random = SecureRandom()

    /** Random, unguessable 8-character order code (~850 billion combinations). */
    fun newCode(): String = buildString { repeat(8) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** Printed above the QR code on every seal sent to the customer. */
    val LABEL_MESSAGE = listOf("We care your Hygiene;", "scan the barcode for the proof.")

    fun qrBitmap(content: String, size: Int): Bitmap {
        val hints = mapOf(
            // M survives small smudges; long demo-mode links use L to keep the code printable
            EncodeHintType.ERROR_CORRECTION to if (content.length > 300) ErrorCorrectionLevel.L else ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /**
     * A printable label sized for common 58 mm Bluetooth thermal printers (384 dots wide).
     */
    fun labelBitmap(url: String, code: String, kitchenName: String, tempText: String): Bitmap {
        val width = 384
        val height = 590
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 20f; textAlign = Paint.Align.CENTER
        }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 34f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        // Customer message above the QR code (shrunk to fit the 58 mm label if needed)
        title.textSize = 26f
        while (LABEL_MESSAGE.any { title.measureText(it) > width - 16 } && title.textSize > 14f) title.textSize -= 1f
        c.drawText(LABEL_MESSAGE[0], width / 2f, 34f, title)
        c.drawText(LABEL_MESSAGE[1], width / 2f, 66f, title)
        c.drawBitmap(qrBitmap(url, 352), 16f, 80f, null)
        c.drawText(code.chunked(4).joinToString(" "), width / 2f, 470f, big)
        c.drawText(kitchenName.take(28), width / 2f, 506f, small)
        c.drawText(tempText, width / 2f, 534f, small)
        c.drawText("Seal must be intact on delivery", width / 2f, 566f, small)
        return bmp
    }

    /**
     * Kitchen hygiene badge for Swiggy / Zomato menu photos, Instagram and Google listings.
     * The QR opens the kitchen's public page with its live 30-day rating.
     */
    fun badgeBitmap(url: String, kitchenName: String, headline: String, detail: String): Bitmap {
        val w = 720; val h = 1000
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(23, 107, 75))
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawRoundRect(30f, 30f, w - 30f, h - 30f, 36f, 36f, card)
        fun text(s: String, y: Float, size: Float, bold: Boolean, color: Int = Color.rgb(23, 43, 36)) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textSize = size; textAlign = Paint.Align.CENTER
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            while (p.measureText(s) > w - 100 && p.textSize > 18f) p.textSize -= 1f
            c.drawText(s, w / 2f, y, p)
        }
        text("KITCHENLENS HYGIENE RATING", 100f, 30f, true, Color.rgb(98, 115, 107))
        text(kitchenName, 170f, 52f, true)
        text(headline, 250f, 56f, true, Color.rgb(16, 84, 58))
        text(detail, 305f, 28f, false, Color.rgb(98, 115, 107))
        c.drawBitmap(qrBitmap(url, 460), (w - 460) / 2f, 340f, null)
        text("Scan to see our kitchen's hygiene proof", 860f, 30f, true)
        text("Rating from recorded hygiene evidence · not an FSSAI rating", 910f, 22f, false, Color.rgb(98, 115, 107))
        return bmp
    }

    /** Saves the label and opens Android's share sheet (choose your printer app). */
    fun share(context: Context, label: Bitmap, code: String, title: String = "Print or share QR seal") {
        val dir = File(context.cacheDir, "labels").apply { mkdirs() }
        val file = File(dir, "seal_$code.png")
        FileOutputStream(file).use { label.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}
