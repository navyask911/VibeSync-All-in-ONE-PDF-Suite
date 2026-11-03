package com.vibesync.toolkit.pdf.data.ocr

import android.os.Build
import android.content.ClipData
import android.content.Context
import android.content.Intent
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.util.UriUtils
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates an on-device Word document from OCR text and shares it through FileProvider. */
object OcrDocxExporter {
    const val MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun create(context: Context, baseName: String, ocrText: String): File {
        val output = UriUtils.createExportCacheFile(context, baseName, ".docx")
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                createWithApachePoi(output, ocrText)
            } else {
                // The upstream Android POI sample only supports API 26+; keep this app's API 24–25
                // devices functional with an equivalent minimal WordprocessingML package.
                createCompatibleDocx(output, ocrText)
            }
            if (!output.isFile || output.length() <= 0L) {
                throw IOException("DOCX export did not create a valid Word document.")
            }
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private fun createWithApachePoi(output: File, ocrText: String) {
        XWPFDocument().use { document ->
            val paragraph = document.createParagraph()
            val run = paragraph.createRun()
            run.setText(ocrText)
            FileOutputStream(output).use { stream -> document.write(stream) }
        }
    }

    private fun createCompatibleDocx(output: File, ocrText: String) {
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http:&#47;&#47;schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""".toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http:&#47;&#47;schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http:&#47;&#47;schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http:&#47;&#47;schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t xml:space="preserve">""" +
                    escapeXml(ocrText) +
                    """</w:t></w:r></w:p><w:sectPr/></w:body></w:document>""").toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                }
            )
        }
    }

    fun share(context: Context, docxFile: File) {
        require(docxFile.isFile && docxFile.extension.equals("docx", ignoreCase = true)) {
            "The Word document is missing or invalid."
        }
        val contentUri = UriUtils.getShareableUri(context, docxFile)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, contentUri)
            clipData = ClipData.newUri(context.contentResolver, docxFile.name, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, context.getString(R.string.export_docx)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }
}
