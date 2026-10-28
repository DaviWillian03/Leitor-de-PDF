package br.com.leitordocs.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import br.com.leitordocs.pdf.Annot
import br.com.leitordocs.pdf.Ink
import br.com.leitordocs.pdf.InkStroke
import br.com.leitordocs.pdf.NPoint
import br.com.leitordocs.pdf.NRect
import br.com.leitordocs.pdf.PdfExporter
import br.com.leitordocs.pdf.PdfSource
import br.com.leitordocs.pdf.SignatureStore
import br.com.leitordocs.pdf.TextNote
import kotlinx.coroutines.launch

private class Tool(val label: String, val argb: Int, val widthFrac: Float, val forText: Boolean = true)

private val TOOLS = listOf(
    Tool("Preta", 0xFF000000.toInt(), 0.004f),
    Tool("Azul", 0xFF1565C0.toInt(), 0.004f),
    Tool("Vermelha", 0xFFC62828.toInt(), 0.004f),
    Tool("Marca-texto", 0x66FFEB3B, 0.025f, forText = false),
)

private enum class Mode(val label: String, val hint: String) {
    ROLAR("Rolar", ""),
    CANETA("Caneta", "Desenhe com o dedo. Para rolar a página, volte para “Rolar”."),
    TEXTO("Texto", "Toque onde quer escrever."),
    ASSINAR("Assinar", "Toque onde quer colocar a assinatura."),
}

private val TEXT_SIZES = listOf("Pequeno" to 0.022f, "Médio" to 0.032f, "Grande" to 0.048f)
private const val SIGNATURE_WIDTH = 0.35f // fração da largura da página
private const val PAD_ASPECT = 3f // largura/altura do quadro de assinatura

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(name: String, source: PdfSource, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(Mode.ROLAR) }
    var tool by remember { mutableStateOf(TOOLS[0]) }
    val annots = remember { mutableStateMapOf<Int, List<Annot>>() }
    val history = remember { mutableStateListOf<Int>() } // página de cada item, para desfazer
    var dirty by remember { mutableStateOf(false) }
    var textAt by remember { mutableStateOf<Pair<Int, NPoint>?>(null) }
    var signAt by remember { mutableStateOf<Pair<Int, NPoint>?>(null) }

    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Pair<Int, NRect>>>(emptyList()) }
    var currentHit by remember { mutableIntStateOf(0) }
    var searchedOnce by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(hits, currentHit) {
        hits.getOrNull(currentHit)?.let { listState.animateScrollToItem(it.first) }
    }

    fun add(page: Int, a: Annot) {
        annots[page] = annots[page].orEmpty() + a
        history.add(page)
        dirty = true
    }

    val save = rememberSaveAction(MIME_PDF, onSaved = { dirty = false }) { out ->
        PdfExporter.export(source, annots.toMap(), out)
    }

    DocScaffold(
        title = name,
        dirty = dirty,
        onClose = onClose,
        actions = {
            TextButton(onClick = { searching = !searching }) { Text("Buscar") }
            TextButton(enabled = dirty, onClick = { save(editedName(name)) }) { Text("Salvar cópia") }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Mode.entries.forEach { m ->
                            FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                        }
                        TextButton(
                            enabled = history.isNotEmpty(),
                            onClick = {
                                val page = history.removeAt(history.lastIndex)
                                annots[page] = annots[page].orEmpty().dropLast(1)
                                dirty = history.isNotEmpty()
                            },
                        ) { Text("Desfazer") }
                    }
                    if (mode == Mode.CANETA || mode == Mode.TEXTO) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TOOLS.filter { mode == Mode.CANETA || it.forText }.forEach { t ->
                                FilterChip(selected = tool === t, onClick = { tool = t }, label = { Text(t.label) })
                            }
                        }
                    }
                    if (mode.hint.isNotEmpty()) Text(mode.hint, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searching) {
                SearchBar(
                    query = query,
                    onQuery = { query = it },
                    status = when {
                        !PdfSource.searchSupported -> "Busca em PDF precisa do Android 15 (ou atualização do sistema)."
                        hits.isNotEmpty() -> "${currentHit + 1}/${hits.size}"
                        searchedOnce -> "Nada encontrado"
                        else -> ""
                    },
                    enabled = PdfSource.searchSupported,
                    onSearch = {
                        scope.launch {
                            hits = source.search(query)
                            currentHit = 0
                            searchedOnce = true
                        }
                    },
                    onPrev = { if (hits.isNotEmpty()) currentHit = (currentHit - 1 + hits.size) % hits.size },
                    onNext = { if (hits.isNotEmpty()) currentHit = (currentHit + 1) % hits.size },
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(source.sizes) { index, (w, h) ->
                    PdfPage(
                        source = source,
                        index = index,
                        aspect = w.toFloat() / h,
                        annots = annots[index].orEmpty(),
                        highlights = hits.filter { it.first == index }.map { it.second },
                        focused = hits.getOrNull(currentHit)?.takeIf { it.first == index }?.second,
                        mode = mode,
                        tool = tool,
                        onInk = { add(index, Ink(listOf(it))) },
                        onTap = { p ->
                            when (mode) {
                                Mode.TEXTO -> textAt = index to p
                                Mode.ASSINAR -> signAt = index to p
                                else -> Unit
                            }
                        },
                    )
                }
            }
        }
    }

    textAt?.let { (page, p) ->
        TextNoteDialog(
            onDismiss = { textAt = null },
            onConfirm = { text, size ->
                if (text.isNotBlank()) add(page, TextNote(text, p.x, p.y, size, tool.takeIf { it.forText }?.argb ?: TOOLS[0].argb))
                textAt = null
            },
        )
    }

    signAt?.let { (page, p) ->
        val (w, h) = source.sizes[page]
        SignatureDialog(
            store = remember { SignatureStore(ctx) },
            onDismiss = { signAt = null },
            onConfirm = { padStrokes ->
                // Converte do quadro (0..1) para a página, centralizado no toque.
                val sw = SIGNATURE_WIDTH
                val sh = sw / PAD_ASPECT * (w.toFloat() / h)
                val left = (p.x - sw / 2).coerceIn(0f, 1f - sw)
                val top = (p.y - sh / 2).coerceIn(0f, (1f - sh).coerceAtLeast(0f))
                val strokes = padStrokes.map { s ->
                    InkStroke(0xFF0D2A6B.toInt(), 0.003f, s.map { NPoint(left + it.x * sw, top + it.y * sh) })
                }
                add(page, Ink(strokes))
                signAt = null
            },
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    status: String,
    enabled: Boolean,
    onSearch: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, onQuery, label = { Text("Buscar no PDF") }, singleLine = true,
                enabled = enabled, modifier = Modifier.weight(1f))
            TextButton(enabled = enabled && query.isNotBlank(), onClick = onSearch) { Text("Ir") }
            TextButton(enabled = enabled, onClick = onPrev) { Text("↑") }
            TextButton(enabled = enabled, onClick = onNext) { Text("↓") }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TextNoteDialog(onDismiss: () -> Unit, onConfirm: (String, Float) -> Unit) {
    var text by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(TEXT_SIZES[1].second) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escrever no PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TEXT_SIZES.forEach { (label, v) ->
                        FilterChip(selected = size == v, onClick = { size = v }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text, size) }) { Text("Colocar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun SignatureDialog(store: SignatureStore, onDismiss: () -> Unit, onConfirm: (List<List<NPoint>>) -> Unit) {
    val saved = remember { store.load() }
    val strokes = remember { mutableStateListOf<List<NPoint>>().apply { addAll(saved) } }
    var current by remember { mutableStateOf<List<NPoint>>(emptyList()) }
    var keep by remember { mutableStateOf(saved.isNotEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assinatura") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Assine com o dedo dentro do quadro.", style = MaterialTheme.typography.bodySmall)
                Canvas(
                    Modifier.fillMaxWidth().aspectRatio(PAD_ASPECT).background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { o -> current = listOf(NPoint(o.x / size.width, o.y / size.height)) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val p = change.position
                                    current = current + NPoint(
                                        (p.x / size.width).coerceIn(0f, 1f),
                                        (p.y / size.height).coerceIn(0f, 1f),
                                    )
                                },
                                onDragEnd = {
                                    if (current.size > 1) strokes.add(current)
                                    current = emptyList()
                                },
                                onDragCancel = { current = emptyList() },
                            )
                        },
                ) {
                    (strokes + listOf(current)).forEach { s ->
                        drawInk(InkStroke(0xFF0D2A6B.toInt(), 0.008f, s))
                    }
                }
                Row(
                    Modifier.clickable { keep = !keep },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = keep, onCheckedChange = null)
                    Text("Guardar neste aparelho para a próxima vez", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { strokes.clear() }) { Text("Limpar quadro") }
            }
        },
        confirmButton = {
            TextButton(enabled = strokes.isNotEmpty(), onClick = {
                if (keep) store.save(strokes.toList()) else store.clear()
                onConfirm(strokes.toList())
            }) { Text("Usar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun PdfPage(
    source: PdfSource,
    index: Int,
    aspect: Float,
    annots: List<Annot>,
    highlights: List<NRect>,
    focused: NRect?,
    mode: Mode,
    tool: Tool,
    onInk: (InkStroke) -> Unit,
    onTap: (NPoint) -> Unit,
) {
    val bitmap by produceState<Bitmap?>(null, index) { value = source.render(index, 1080) }
    var current by remember { mutableStateOf<List<NPoint>>(emptyList()) }
    val measurer = rememberTextMeasurer()

    Box(Modifier.fillMaxWidth().aspectRatio(aspect).background(Color.White)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Página ${index + 1}", modifier = Modifier.fillMaxSize()) }
        Canvas(
            Modifier.fillMaxSize().pointerInput(mode, tool) {
                when (mode) {
                    Mode.CANETA -> detectDragGestures(
                        onDragStart = { o -> current = listOf(NPoint(o.x / size.width, o.y / size.height)) },
                        onDrag = { change, _ ->
                            change.consume()
                            val p = change.position
                            current = current + NPoint(p.x / size.width, p.y / size.height)
                        },
                        onDragEnd = {
                            if (current.size > 1) onInk(InkStroke(tool.argb, tool.widthFrac, current))
                            current = emptyList()
                        },
                        onDragCancel = { current = emptyList() },
                    )
                    Mode.TEXTO, Mode.ASSINAR -> detectTapGestures { o ->
                        onTap(NPoint(o.x / size.width, o.y / size.height))
                    }
                    Mode.ROLAR -> Unit
                }
            },
        ) {
            highlights.forEach { r ->
                drawRect(
                    if (r == focused) Color(0x88FF9800) else Color(0x55FFEB3B),
                    topLeft = Offset(r.left * size.width, r.top * size.height),
                    size = Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height),
                )
            }
            annots.forEach { a ->
                when (a) {
                    is Ink -> a.strokes.forEach { drawInk(it) }
                    is TextNote -> drawNote(measurer, a)
                }
            }
            if (current.isNotEmpty()) drawInk(InkStroke(tool.argb, tool.widthFrac, current))
        }
    }
}

private fun DrawScope.drawNote(measurer: TextMeasurer, n: TextNote) {
    drawText(
        textMeasurer = measurer,
        text = n.text,
        topLeft = Offset(n.x * size.width, n.y * size.height),
        style = TextStyle(color = Color(n.argb), fontSize = (n.sizeFrac * size.width).toSp()),
        softWrap = false, // igual ao PDF salvo, que não quebra linha sozinho
    )
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
