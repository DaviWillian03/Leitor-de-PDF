package br.com.leitordocs.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.com.leitordocs.formats.CellPos
import br.com.leitordocs.formats.ColumnFilter
import br.com.leitordocs.formats.SheetData
import br.com.leitordocs.formats.SheetFilter
import br.com.leitordocs.formats.SortSpec
import br.com.leitordocs.formats.XlsxDoc

private val CELL_W = 110.dp
private val HEAD_W = 56.dp
private val CELL_H = 40.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XlsxScreen(name: String, doc: XlsxDoc, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var sheet by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CellPos?>(null) }
    var columnMenu by remember { mutableStateOf<Int?>(null) }
    var rowMenu by remember { mutableStateOf<Int?>(null) }
    var headerRow by remember { mutableStateOf(true) }
    var filters by remember(sheet) { mutableStateOf(mapOf<Int, ColumnFilter>()) }
    var sort by remember(sheet) { mutableStateOf<SortSpec?>(null) }
    val data = remember(sheet, version) { doc.readSheet(sheet) }

    val totalRows = (data.rowCount + 10).coerceAtLeast(30).coerceAtMost(5000)
    val cols = (data.colCount + 2).coerceAtLeast(8).coerceAtMost(200)
    val visible = remember(data, headerRow, filters, sort, totalRows) {
        SheetFilter.visibleRows(data, totalRows, headerRow, filters, sort)
    }
    val filtering = filters.values.any { it.active } || sort != null

    val save = rememberSaveAction(MIME_XLSX, onSaved = { dirty = false }) { out ->
        out.write(doc.toBytes())
    }

    fun rowOp(block: () -> Unit) {
        try {
            block()
            version++
            dirty = true
        } catch (e: UnsupportedOperationException) {
            Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show()
        }
    }

    DocScaffold(
        title = name,
        dirty = dirty,
        onClose = onClose,
        actions = { TextButton(enabled = dirty, onClick = { save(editedName(name)) }) { Text("Salvar cópia") } },
        bottomBar = {
            if (doc.sheets.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = headerRow, onCheckedChange = { headerRow = it })
                Text("1ª linha é cabeçalho", Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodySmall)
                if (filtering) {
                    TextButton(onClick = { filters = emptyMap(); sort = null }) { Text("Limpar filtros") }
                }
            }
            if (filtering) {
                val shownData = visible.count { it < data.rowCount } - if (headerRow) 1 else 0
                Text(
                    "Mostrando $shownData de ${(data.rowCount - if (headerRow) 1 else 0).coerceAtLeast(0)} linhas. " +
                        "Filtro e ordem só mudam a tela, não o arquivo.",
                    Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                Row {
                    Cell("", HEAD_W, header = true)
                    for (c in 0 until cols) {
                        val mark = when {
                            sort?.col == c -> if (sort!!.ascending) " ↑" else " ↓"
                            filters[c]?.active == true -> " ▼"
                            else -> ""
                        }
                        Cell(XlsxDoc.colName(c) + mark, CELL_W, header = true, onClick = { columnMenu = c })
                    }
                }
                @Composable
                fun DataRow(r: Int) {
                    val isHeader = headerRow && r == 0
                    Row(if (isHeader) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier) {
                        Cell((r + 1).toString(), HEAD_W, header = true, onClick = { rowMenu = r })
                        for (c in 0 until cols) {
                            Cell(data.cells[r to c].orEmpty(), CELL_W, bold = isHeader, onClick = { editing = r to c })
                        }
                    }
                }
                // Linha de cabeçalho fica fixa no topo enquanto rola.
                if (headerRow && visible.firstOrNull() == 0) DataRow(0)
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(if (headerRow) visible.drop(1) else visible, key = { it }) { r -> DataRow(r) }
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

    rowMenu?.let { r ->
        AlertDialog(
            onDismissRequest = { rowMenu = null },
            title = { Text("Linha ${r + 1}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { rowOp { doc.insertRow(sheet, r) }; rowMenu = null }, Modifier.fillMaxWidth()) {
                        Text("Inserir linha acima")
                    }
                    OutlinedButton(onClick = { rowOp { doc.insertRow(sheet, r + 1) }; rowMenu = null }, Modifier.fillMaxWidth()) {
                        Text("Inserir linha abaixo")
                    }
                    OutlinedButton(onClick = { rowOp { doc.deleteRow(sheet, r) }; rowMenu = null }, Modifier.fillMaxWidth()) {
                        Text("Excluir esta linha", color = MaterialTheme.colorScheme.error)
                    }
                    if (doc.hasFormulas(sheet)) {
                        Text(
                            "As fórmulas desta aba são ajustadas. Fórmulas de outras abas que apontem para cá podem " +
                                "precisar de conferência no Excel.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { rowMenu = null }) { Text("Fechar") } },
        )
    }

    columnMenu?.let { c ->
        ColumnFilterDialog(
            title = columnTitle(data, c, headerRow),
            values = remember(data, c, headerRow) { SheetFilter.distinctValues(data, c, headerRow) },
            current = filters[c] ?: ColumnFilter(),
            sort = sort?.takeIf { it.col == c },
            onSort = { asc -> sort = asc?.let { SortSpec(c, it) }; columnMenu = null },
            onApply = { f -> filters = filters + (c to f); columnMenu = null },
            onDismiss = { columnMenu = null },
        )
    }
}

private fun columnTitle(data: SheetData, c: Int, headerRow: Boolean): String {
    val head = if (headerRow) data.cells[0 to c].orEmpty() else ""
    return "Coluna ${XlsxDoc.colName(c)}" + if (head.isNotEmpty()) " — $head" else ""
}

@Composable
private fun ColumnFilterDialog(
    title: String,
    values: List<String>,
    current: ColumnFilter,
    sort: SortSpec?,
    onSort: (Boolean?) -> Unit,
    onApply: (ColumnFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var contains by remember { mutableStateOf(current.contains) }
    var allowed by remember { mutableStateOf(current.allowed ?: values.toSet()) }
    var search by remember { mutableStateOf("") }
    val listed = values.filter { search.isBlank() || it.contains(search.trim(), ignoreCase = true) }.take(500)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = sort?.ascending == true, onClick = { onSort(true) }, label = { Text("A→Z / 0→9") })
                    FilterChip(selected = sort?.ascending == false, onClick = { onSort(false) }, label = { Text("Z→A / 9→0") })
                }
                if (sort != null) TextButton(onClick = { onSort(null) }) { Text("Tirar ordenação") }
                OutlinedTextField(
                    contains,
                    { contains = it },
                    label = { Text("Mostrar linhas que contêm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Ou escolha os valores:", style = MaterialTheme.typography.labelMedium)
                if (values.size > 8) {
                    OutlinedTextField(search, { search = it }, label = { Text("Procurar valor") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                Row {
                    TextButton(onClick = { allowed = values.toSet() }) { Text("Todos") }
                    TextButton(onClick = { allowed = emptySet() }) { Text("Nenhum") }
                }
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(listed) { v ->
                        Row(
                            Modifier.fillMaxWidth().clickable { allowed = if (v in allowed) allowed - v else allowed + v },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = v in allowed, onCheckedChange = null)
                            Text(v.ifEmpty { "(vazias)" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val all = allowed.containsAll(values)
                onApply(ColumnFilter(contains.trim(), if (all) null else allowed))
            }) { Text("Aplicar") }
        },
        dismissButton = {
            TextButton(onClick = { onApply(ColumnFilter()) }) { Text("Limpar filtro") }
        },
    )
}

@Composable
private fun Cell(
    text: String,
    width: Dp,
    header: Boolean = false,
    bold: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val bg = if (header) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    var m = Modifier.width(width).height(CELL_H).background(bg)
        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m.then(Modifier.padding(horizontal = 6.dp)), contentAlignment = if (header) Alignment.Center else Alignment.CenterStart) {
        Text(
            text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (header || bold) FontWeight.Bold else null,
            color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}
