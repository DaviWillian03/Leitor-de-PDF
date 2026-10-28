package br.com.leitordocs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import br.com.leitordocs.formats.DocKind
import br.com.leitordocs.formats.DocxDoc
import br.com.leitordocs.formats.Sniff
import br.com.leitordocs.formats.XlsxDoc
import br.com.leitordocs.pdf.PdfSource
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface Loaded {
    class Pdf(val name: String, val source: PdfSource) : Loaded
    class Docx(val name: String, val doc: DocxDoc) : Loaded
    class Xlsx(val name: String, val doc: XlsxDoc) : Loaded
    class Failure(val message: String) : Loaded
}

object Loader {
    private const val MAX_BYTES = 100L * 1024 * 1024

    suspend fun load(ctx: Context, uri: Uri): Loaded = withContext(Dispatchers.IO) {
        try {
            val name = displayName(ctx, uri)
            val input = ctx.contentResolver.openInputStream(uri)
                ?: return@withContext Loaded.Failure("Não foi possível abrir o arquivo.")
            val bytes = input.use { readLimited(it) }
            when (Sniff.kindOf(bytes)) {
                DocKind.PDF -> {
                    val file = File(ctx.cacheDir, "aberto.pdf")
                    file.writeBytes(bytes)
                    Loaded.Pdf(name, PdfSource.open(file))
                }
                DocKind.DOCX -> Loaded.Docx(name, DocxDoc.open(bytes))
                DocKind.XLSX -> Loaded.Xlsx(name, XlsxDoc.open(bytes))
                DocKind.LEGACY_OFFICE -> Loaded.Failure(
                    "Este é um arquivo antigo (.doc ou .xls). Peça para a pessoa reenviar como .docx ou .xlsx.",
                )
                DocKind.UNKNOWN -> Loaded.Failure("Formato não suportado. Abro PDF, Word (.docx) e Excel (.xlsx).")
            }
        } catch (e: SecurityException) {
            Loaded.Failure("PDF protegido por senha não é suportado.")
        } catch (e: Exception) {
            Loaded.Failure("Não consegui abrir este arquivo (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            require(total <= MAX_BYTES) { "arquivo maior que 100 MB" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment ?: "arquivo"
    }
}
