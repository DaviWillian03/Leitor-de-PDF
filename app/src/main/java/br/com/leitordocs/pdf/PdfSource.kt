package br.com.leitordocs.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    companion object {
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

    suspend fun export(source: PdfSource, strokes: Map<Int, List<InkStroke>>, out: OutputStream) =
        withContext(Dispatchers.IO) {
            val pdf = PdfDocument()
            try {
                for (i in 0 until source.pageCount) {
                    val (w, h) = source.sizes[i]
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                    val bmp = source.render(i, RENDER_WIDTH)
                    page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                    bmp.recycle()
                    for (s in strokes[i].orEmpty()) drawStroke(page.canvas, s, w.toFloat(), h.toFloat())
                    pdf.finishPage(page)
                }
                pdf.writeTo(out)
            } finally {
                pdf.close()
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
