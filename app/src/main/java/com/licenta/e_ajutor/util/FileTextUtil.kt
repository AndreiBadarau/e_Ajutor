package com.licenta.e_ajutor.util

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.IOException
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object FileTextUtil {
    private const val TMP_PDF = "tmp.pdf"

    /**
     * Extrage text dintr-un fișier indiferent de format:
     * - PDF cu text intern → PDFBox
     * - PDF scanat / imagine → OCR după preprocesare
     * - alt format → text UTF-8
     */
    suspend fun extractText(
        context: Context,
        fileBytes: ByteArray,
        contentType: String?,
        fileName: String
    ): String {
        val ct = contentType?.lowercase()
            ?: fileName.substringAfterLast('.', "").lowercase()
        return try {
            when {
                fileName.endsWith(".pdf", true) -> {
                    // 1) încerc extragere text cu PDFBox
                    val rawText = extractPdfText(fileBytes)
                    if (rawText.isNotBlank()) rawText
                    else {
                        // 2) fallback: PDF scanat → OCR
                        val bmp = renderPdfPageOne(context, fileBytes)
                        ocrText(context, preprocessForOcr(bmp))
                    }
                }
                ct.startsWith("image/") -> {
                    // imagine simplă → OCR
                    val bmp = BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size)
                        ?: return ""
                    ocrText(context, preprocessForOcr(bmp))
                }
                else -> {
                    // fallback text simplu
                    String(fileBytes)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FileTextUtil", "extractText failed for $fileName: ${e.message}", e)
            ""
        }
    }

    @Throws(IOException::class)
    private fun extractPdfText(pdfBytes: ByteArray): String {
        // Creează InputStream simplu
        val bais = ByteArrayInputStream(pdfBytes)
        // Încarcă documentul PDF
        val doc = PDDocument.load(bais)
        // Extrage text și închide documentul
        val text = PDFTextStripper().getText(doc)
        doc.close()
        return text
    }

    @Throws(IOException::class)
    private fun renderPdfPageOne(context: Context, pdfBytes: ByteArray): Bitmap {
        // salvează temporar PDF în cache
        val file = java.io.File(context.cacheDir, TMP_PDF).apply { writeBytes(pdfBytes) }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                val page = renderer.openPage(0)
                val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                return bmp
            }
        }
    }

    private suspend fun ocrText(context: Context, bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    private fun preprocessForOcr(src: Bitmap): Bitmap {
        // scalare + color transform pentru contrast
        val scaled = Bitmap.createScaledBitmap(src, src.width * 2, src.height * 2, true)
        val gray = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gray)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        val thresh = 128
        for (x in 0 until gray.width) {
            for (y in 0 until gray.height) {
                val c = gray.getPixel(x, y)
                val v = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
                gray.setPixel(x, y, if (v < thresh) Color.BLACK else Color.WHITE)
            }
        }
        return gray
    }
}
