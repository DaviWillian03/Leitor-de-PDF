package br.com.leitordocs.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.leitordocs.formats.DocxDoc

@Composable
fun DocxScreen(name: String, doc: DocxDoc, onClose: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) } // -1 = novo parágrafo
    val texts = remember(version) { doc.paragraphTexts() }

    val save = rememberSaveAction(MIME_DOCX, onSaved = { dirty = false }) { out ->
        out.write(doc.toBytes())
    }

    DocScaffold(
        title = name,
        dirty = dirty,
        onClose = onClose,
        actions = { TextButton(enabled = dirty, onClick = { save(editedName(name)) }) { Text("Salvar cópia") } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            itemsIndexed(texts) { i, text ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp)
                        .clickable { editing = i }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(text.ifEmpty { " " }, style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                TextButton(onClick = { editing = -1 }, modifier = Modifier.padding(8.dp)) {
                    Text("Adicionar parágrafo no final")
                }
            }
        }
    }

    editing?.let { idx ->
        var draft by remember(idx) { mutableStateOf(if (idx >= 0) texts[idx] else "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (idx >= 0) "Editar parágrafo" else "Novo parágrafo") },
            text = {
                Box {
                    OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (idx >= 0) doc.setParagraph(idx, draft) else doc.addParagraph(draft)
                    version++
                    dirty = true
                    editing = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } },
        )
    }
}
