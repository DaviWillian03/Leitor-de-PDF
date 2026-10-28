package br.com.leitordocs.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.leitordocs.formats.CellPos
import br.com.leitordocs.formats.XlsxDoc

private val CELL_W = 110.dp
private val HEAD_W = 48.dp
private val CELL_H = 40.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XlsxScreen(name: String, doc: XlsxDoc, onClose: () -> Unit) {
    var sheet by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CellPos?>(null) }
    val data = remember(sheet, version) { doc.readSheet(sheet) }

    val rows = (data.rowCount + 10).coerceAtLeast(30).coerceAtMost(5000)
    val cols = (data.colCount + 2).coerceAtLeast(8).coerceAtMost(200)

    val save = rememberSaveAction(MIME_XLSX, onSaved = { dirty = false }) { out ->
        out.write(doc.toBytes())
    }

    DocScaffold(
        title = name,
        dirty = dirty,
        onClose = onClose,
        actions = { TextButton(enabled = dirty, onClick = { save(editedName(name)) }) { Text("Salvar cópia") } },
        bottomBar = {
            if (doc.sheets.size > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                ) {
                    doc.sheets.forEachIndexed { i, s ->
                        FilterChip(
                            selected = i == sheet,
                            onClick = { sheet = i },
                            label = { Text(s.name) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).horizontalScroll(rememberScrollState())) {
            Row {
                Cell("", HEAD_W, header = true)
                for (c in 0 until cols) Cell(XlsxDoc.colName(c), CELL_W, header = true)
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items((0 until rows).toList()) { r ->
                    Row {
                        Cell((r + 1).toString(), HEAD_W, header = true)
                        for (c in 0 until cols) {
                            Cell(data.cells[r to c].orEmpty(), CELL_W, onClick = { editing = r to c })
                        }
                    }
                }
            }
        }
    }

    editing?.let { pos ->
        var draft by remember(pos) { mutableStateOf(data.cells[pos].orEmpty()) }
        val ref = XlsxDoc.colName(pos.second) + (pos.first + 1)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Editar $ref") },
            text = {
                Column {
                    OutlinedTextField(draft, { draft = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (pos in data.formulas) {
                        Text(
                            "Esta célula tem uma fórmula. Ao confirmar, ela será trocada pelo valor digitado.",
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    doc.setCell(sheet, pos.first, pos.second, draft)
                    version++
                    dirty = true
                    editing = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Cell(text: String, width: androidx.compose.ui.unit.Dp, header: Boolean = false, onClick: (() -> Unit)? = null) {
    val bg = if (header) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    var m = Modifier.width(width).height(CELL_H)
        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m.then(Modifier.padding(horizontal = 6.dp)), contentAlignment = if (header) Alignment.Center else Alignment.CenterStart) {
        Text(
            text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (header) FontWeight.Bold else null,
            color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}
