package com.kitchenlens.app.util

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.Calendar

/**
 * On-device AI (Google ML Kit, works offline): reads the text on a supplier bill
 * and picks out the most likely purchase date. Staff can still correct it.
 */
object BillReader {
    private val datePattern = Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2,4})\b""")

    suspend fun readDate(context: Context, photo: File): String? {
        val image = InputImage.fromFilePath(context, Uri.fromFile(photo))
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val text = recognizer.process(image).await().text
            pickDate(text, System.currentTimeMillis())
        } finally {
            recognizer.close()
        }
    }

    /** Returns "dd-MM-yyyy" for the first date in the last 60 days (Indian day-first format). */
    fun pickDate(text: String, now: Long): String? {
        val sixtyDays = 60L * 24 * 3_600_000
        for (m in datePattern.findAll(text)) {
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            var y = m.groupValues[3].toInt()
            if (y < 100) y += 2000
            if (d !in 1..31 || mo !in 1..12) continue
            val cal = Calendar.getInstance().apply {
                clear(); set(y, mo - 1, d, 12, 0)
            }
            val t = cal.timeInMillis
            if (t <= now + 24 * 3_600_000 && now - t <= sixtyDays) {
                return "%02d-%02d-%04d".format(d, mo, y)
            }
        }
        return null
    }
}
