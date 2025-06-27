package com.licenta.e_ajutor.util

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.IOException

object PdfTextUtil {
    /**
     * Extrage text din toate paginile unui PDF.
     * @param pdfBytes byte[] conţinând fişierul PDF
     * @return textul extras (gol dacă nu există text)
     */
    @Throws(IOException::class)
    fun extractText(pdfBytes: ByteArray): String {
        ByteArrayInputStream(pdfBytes).use { bais ->
            PDDocument.load(bais).use { doc ->
                return PDFTextStripper().getText(doc)
            }
        }
    }
}