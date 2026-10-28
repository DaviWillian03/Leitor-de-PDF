package br.com.leitordocs.pdf

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Ponto normalizado (0..1) dentro da página, para o desenho valer em qualquer resolução. */
data class NPoint(val x: Float, val y: Float)

/** Traço de caneta/marca-texto; [widthFrac] é a espessura relativa à largura da página. */
data class InkStroke(val argb: Int, val widthFrac: Float, val points: List<NPoint>)

/** Retângulo normalizado (0..1) na página. */
data class NRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** O que o usuário acrescenta a uma página; cada item é desfeito de uma vez. */
sealed interface Annot

/** Um traço de caneta, ou vários (uma assinatura). */
data class Ink(val strokes: List<InkStroke>) : Annot

/** Texto digitado; ([x], [y]) é o canto superior esquerdo e [sizeFrac] o tamanho relativo à largura da página. */
data class TextNote(val text: String, val x: Float, val y: Float, val sizeFrac: Float, val argb: Int) : Annot

class PdfSource private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Tamanho de cada página em pontos (1/72"). */
    val sizes: List<Pair<Int, Int>>,
) : AutoCloseable {
    private val lock = Mutex()
    val pageCount: Int get() = sizes.size

    suspend fun render(index: Int, widthPx: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            val page = renderer.openPage(index)
            try {
                val h = (widthPx.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                page.close()
            }
        }
    }

    /**
     * Busca de texto. Depende do leitor de PDF do sistema (Android 15, ou atualização do sistema equivalente);
     * em aparelhos sem suporte, [searchSupported] é false.
     */
    suspend fun search(query: String): List<Pair<Int, NRect>> = lock.withLock {
        withContext(Dispatchers.IO) {
            val hits = ArrayList<Pair<Int, NRect>>()
            if (!searchSupported || query.isBlank()) return@withContext hits
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                try {
                    val (w, h) = sizes[i]
                    for (m in searchPage(page, query)) {
                        hits.add(i to NRect(m.left / w, m.top / h, m.right / w, m.bottom / h))
                    }
                } finally {
                    page.close()
                }
            }
            hits
        }
    }

    @SuppressLint("NewApi")
    private fun searchPage(page: PdfRenderer.Page, query: String): List<RectF> {
        // Cada ocorrência pode ter vários retângulos (quando quebra linha); usamos o primeiro.
        return page.searchText(query).mapNotNull { it.bounds.firstOrNull() }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    companion object {
        val searchSupported: Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13

        /** Lança [SecurityException] para PDF protegido por senha. */
        fun open(file: File): PdfSource {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(pfd)
                val sizes = (0 until renderer.pageCount).map { i ->
                    val p = renderer.openPage(i)
                    try {
                        p.width to p.height
                    } finally {
                        p.close()
                    }
                }
                return PdfSource(pfd, renderer, sizes)
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
        }
    }
}

/**
 * Gera um PDF novo com as páginas originais e os traços por cima.
 * Atenção: as páginas viram imagem (o texto deixa de ser selecionável).
 */
object PdfExporter {
    private const val RENDER_WIDTH = 1600

    suspend fun export(source: PdfSource, annots: Map<Int, List<Annot>>, out: OutputStream) =
        withContext(Dispatchers.IO) {
            val pdf = PdfDocument()
            try {
                for (i in 0 until source.pageCount) {
                    val (w, h) = source.sizes[i]
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                    val bmp = source.render(i, RENDER_WIDTH)
                    page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                    bmp.recycle()
                    for (a in annots[i].orEmpty()) {
                        when (a) {
                            is Ink -> a.strokes.forEach { drawStroke(page.canvas, it, w.toFloat(), h.toFloat()) }
                            is TextNote -> drawText(page.canvas, a, w.toFloat(), h.toFloat())
                        }
                    }
                    pdf.finishPage(page)
                }
                pdf.writeTo(out)
            } finally {
                pdf.close()
            }
        }

    private fun drawText(canvas: Canvas, n: TextNote, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = n.argb
            textSize = n.sizeFrac * w
        }
        var baseline = n.y * h - paint.ascent()
        for (line in n.text.split('\n')) {
            canvas.drawText(line, n.x * w, baseline, paint)
            baseline += paint.fontSpacing
        }
    }

    private fun drawStroke(canvas: Canvas, s: InkStroke, w: Float, h: Float) {
        if (s.points.size < 2) return
        val path = Path()
        s.points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = s.widthFrac * w
            color = s.argb
        }
        canvas.drawPath(path, paint)
    }
}
