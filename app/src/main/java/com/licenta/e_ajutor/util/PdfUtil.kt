package com.licenta.e_ajutor.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import okio.IOException
import java.io.File
import androidx.core.graphics.createBitmap

object PdfUtil {
    @Throws(IOException::class)
    fun pageOneToBitmap(context: Context, pdfBytes: ByteArray): Bitmap {
        // salvează temporar
        val file = File(context.cacheDir, "tmp.pdf").apply {
            writeBytes(pdfBytes)
        }
        ParcelFileDescriptor.open(file,
            ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val page = renderer.openPage(0)
                val bmp = createBitmap(page.width, page.height)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                return bmp
            }
        }
    }
}