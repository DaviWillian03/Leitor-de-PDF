package br.com.leitordocs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import br.com.leitordocs.formats.DocxDoc

private sealed interface DocxEdit {
    data class Edit(val index: Int) : DocxEdit
    data class InsertAfter(val index: Int) : DocxEdit
    data object Append : DocxEdit
}

@Composable
fun DocxScreen(name: String, doc: DocxDoc, onClose: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DocxEdit?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var current by remember { mutableIntStateOf(0) }
    val texts = remember(version) { doc.paragraphTexts() }
    val matches = remember(texts, query) {
        if (query.isBlank()) emptyList() else texts.indices.filter { texts[it].contains(query.trim(), ignoreCase = true) }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(matches, current) {
        matches.getOrNull(current)?.let { listState.animateScrollToItem(it) }
    }

    val save = rememberSaveAction(MIME_DOCX, onSaved = { dirty = false }) { out ->
        out.write(doc.toBytes())
    }

    fun changed() {
        version++
        dirty = true
        editing = null
    }

    DocScaffold(
        title = name,
        dirty = dirty,
        onClose = onClose,
        actions = {
            TextButton(onClick = { searching = !searching; if (!searching) query = "" }) { Text("Buscar") }
            TextButton(enabled = dirty, onClick = { save(editedName(name)) }) { Text("Salvar cópia") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searching) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        query,
                        { query = it; current = 0 },
                        label = { Text("Buscar no texto") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (matches.isEmpty()) "0" else "${current + 1}/${matches.size}",
                        Modifier.padding(horizontal = 8.dp),
                    )
                    TextButton(enabled = matches.isNotEmpty(), onClick = {
                        current = (current - 1 + matches.size) % matches.size
                    }) { Text("↑") }
                    TextButton(enabled = matches.isNotEmpty(), onClick = {
                        current = (current + 1) % matches.size
                    }) { Text("↓") }
                }
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(texts) { i, text ->
                    val focused = matches.getOrNull(current) == i
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 40.dp)
                            .then(if (focused) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                            .clickable { editing = DocxEdit.Edit(i) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(highlight(text.ifEmpty { " " }, query), style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item {
                    TextButton(onClick = { editing = DocxEdit.Append }, modifier = Modifier.padding(8.dp)) {
                        Text("Adicionar parágrafo no final")
                    }
                }
            }
        }
    }

    editing?.let { mode ->
        var draft by remember(mode) { mutableStateOf((mode as? DocxEdit.Edit)?.let { texts[it.index] } ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = {
                Text(
                    when (mode) {
                        is DocxEdit.Edit -> "Editar parágrafo"
                        is DocxEdit.InsertAfter -> "Novo parágrafo abaixo"
                        DocxEdit.Append -> "Novo parágrafo no final"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth())
                    if (mode is DocxEdit.Edit) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editing = DocxEdit.InsertAfter(mode.index) }) { Text("Inserir abaixo") }
                            OutlinedButton(onClick = { doc.deleteParagraph(mode.index); changed() }) {
                                Text("Excluir", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (mode) {
                        is DocxEdit.Edit -> doc.setParagraph(mode.index, draft)
                        is DocxEdit.InsertAfter -> doc.insertParagraphAfter(mode.index, draft)
                        DocxEdit.Append -> doc.addParagraph(draft)
                    }
                    changed()
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } },
        )
    }
}

private fun highlight(text: String, query: String): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val hit = text.indexOf(q, i, ignoreCase = true)
            if (hit < 0) {
                append(text.substring(i))
                break
            }
            append(text.substring(i, hit))
            withStyle(SpanStyle(background = Color(0x99FFEB3B))) { append(text.substring(hit, hit + q.length)) }
            i = hit + q.length
        }
    }
}
