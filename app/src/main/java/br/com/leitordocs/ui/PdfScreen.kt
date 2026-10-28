package br.com.leitordocs.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import br.com.leitordocs.pdf.InkStroke
import br.com.leitordocs.pdf.NPoint
import br.com.leitordocs.pdf.PdfExporter
import br.com.leitordocs.pdf.PdfSource

private class Tool(val label: String, val argb: Int, val widthFrac: Float)

private val TOOLS = listOf(
    Tool("Caneta preta", 0xFF000000.toInt(), 0.004f),
    Tool("Caneta azul", 0xFF1565C0.toInt(), 0.004f),
    Tool("Caneta vermelha", 0xFFC62828.toInt(), 0.004f),
    Tool("Marca-texto", 0x66FFEB3B, 0.025f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(name: String, source: PdfSource, onClose: () -> Unit) {
    var drawing by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(TOOLS[0]) }
    val strokes = remember { mutableStateMapOf<Int, List<InkStroke>>() }
    val history = remember { mutableStateListOf<Int>() } // página de cada traço, para desfazer
    var dirty by remember { mutableStateOf(false) }

    val save = rememberSaveAction(MIME_PDF, onSaved = { dirty = false }) { out ->
        PdfExporter.export(source, strokes.toMap(), out)
    }

    DocScaffold(
        title = name,
        dirty = dirty,
        onClose = onClose,
        actions = {
            TextButton(
                enabled = dirty,
                onClick = { save(editedName(name)) },
            ) { Text("Salvar cópia") }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        FilterChip(
                            selected = drawing,
                            onClick = { drawing = !drawing },
                            label = { Text(if (drawing) "Desenhando" else "Desenhar") },
                        )
                        TextButton(
                            enabled = history.isNotEmpty(),
                            onClick = {
                                val page = history.removeAt(history.lastIndex)
                                strokes[page] = strokes[page].orEmpty().dropLast(1)
                                dirty = history.isNotEmpty()
                            },
                        ) { Text("Desfazer") }
                    }
                    if (drawing) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TOOLS.forEach { t ->
                                FilterChip(
                                    selected = tool === t,
                                    onClick = { tool = t },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                        Text(
                            "Desligue “Desenhando” para rolar a página.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(source.sizes) { index, (w, h) ->
                PdfPage(
                    source = source,
                    index = index,
                    aspect = w.toFloat() / h,
                    strokes = strokes[index].orEmpty(),
                    drawing = drawing,
                    tool = tool,
                    onStroke = {
                        strokes[index] = strokes[index].orEmpty() + it
                        history.add(index)
                        dirty = true
                    },
                )
            }
        }
    }
}

@Composable
private fun PdfPage(
    source: PdfSource,
    index: Int,
    aspect: Float,
    strokes: List<InkStroke>,
    drawing: Boolean,
    tool: Tool,
    onStroke: (InkStroke) -> Unit,
) {
    val bitmap by produceState<Bitmap?>(null, index) { value = source.render(index, 1080) }
    var current by remember { mutableStateOf<List<NPoint>>(emptyList()) }

    Box(Modifier.fillMaxWidth().aspectRatio(aspect).background(Color.White)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Página ${index + 1}", modifier = Modifier.fillMaxSize()) }
        Canvas(
            Modifier.fillMaxSize().pointerInput(drawing, tool) {
                if (!drawing) return@pointerInput
                detectDragGestures(
                    onDragStart = { o -> current = listOf(NPoint(o.x / size.width, o.y / size.height)) },
                    onDrag = { change, _ ->
                        change.consume()
                        val p = change.position
                        current = current + NPoint(p.x / size.width, p.y / size.height)
                    },
                    onDragEnd = {
                        if (current.size > 1) onStroke(InkStroke(tool.argb, tool.widthFrac, current))
                        current = emptyList()
                    },
                    onDragCancel = { current = emptyList() },
                )
            },
        ) {
            strokes.forEach { drawInk(it) }
            if (current.isNotEmpty()) drawInk(InkStroke(tool.argb, tool.widthFrac, current))
        }
    }
}

private fun DrawScope.drawInk(s: InkStroke) {
    if (s.points.size < 2) return
    val path = Path()
    s.points.forEachIndexed { i, p ->
        val x = p.x * size.width
        val y = p.y * size.height
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path,
        Color(s.argb),
        style = Stroke(width = s.widthFrac * size.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
