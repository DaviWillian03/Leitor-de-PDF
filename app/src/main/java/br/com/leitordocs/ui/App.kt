package br.com.leitordocs.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.style.TextAlign
import br.com.leitordocs.RecentFile
import br.com.leitordocs.RecentStore
import br.com.leitordocs.formats.DocKind
import java.text.DateFormat
import java.util.Date
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.leitordocs.Loaded
import br.com.leitordocs.Loader
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MIME_PDF = "application/pdf"
const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

@Composable
fun App(incoming: Uri?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun show(next: Loaded) {
        val previous = loaded
        loaded = next
        if (previous is Loaded.Pdf) previous.source.close()
    }

    fun openWith(block: suspend () -> Loaded) {
        scope.launch {
            loading = true
            show(block())
            loading = false
        }
    }

    LaunchedEffect(incoming) { if (incoming != null) openWith { Loader.load(ctx, incoming) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openWith { Loader.load(ctx, uri) }
    }

    fun close() {
        (loaded as? Loaded.Pdf)?.source?.close()
        loaded = null
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val current = loaded
        when {
            loading -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Abrindo…", Modifier.padding(top = 16.dp))
            }
            current is Loaded.Pdf -> PdfScreen(current.name, current.source, ::close)
            current is Loaded.Docx -> DocxScreen(current.name, current.doc, ::close)
            current is Loaded.Xlsx -> XlsxScreen(current.name, current.doc, ::close)
            else -> HomeScreen(
                error = (current as? Loaded.Failure)?.message,
                onPick = { picker.launch(arrayOf(MIME_PDF, MIME_DOCX, MIME_XLSX)) },
                onOpenRecent = { entry -> openWith { Loader.loadRecent(ctx, entry) } },
            )
        }
    }
}

private enum class KindFilter(val label: String, val kind: DocKind?) {
    TODOS("Todos", null), PDF("PDF", DocKind.PDF), WORD("Word", DocKind.DOCX), EXCEL("Excel", DocKind.XLSX)
}

private fun kindLabel(kind: DocKind) = when (kind) {
    DocKind.PDF -> "PDF"
    DocKind.DOCX -> "Word"
    DocKind.XLSX -> "Excel"
    else -> "?"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(error: String?, onPick: () -> Unit, onOpenRecent: (RecentFile) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { RecentStore(ctx) }
    var version by remember { mutableIntStateOf(0) }
    val recents = remember(version) { store.list() }
    var kindFilter by remember { mutableStateOf(KindFilter.TODOS) }
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = recents.filter { r ->
        (kindFilter.kind == null || r.kind == kindFilter.kind) &&
            (query.isBlank() || r.name.contains(query.trim(), ignoreCase = true))
    }
    val dateFmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Leitor de Documentos") }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(onClick = onPick, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Abrir arquivo") }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(
                    "Abra PDF, Word (.docx) e Excel (.xlsx). No WhatsApp, toque no arquivo e escolha este app " +
                        "em “Abrir com”.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (error != null) {
                    Text(error, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
                }
            }
            if (recents.isNotEmpty()) {
                item {
                    Text("Recentes", Modifier.padding(top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KindFilter.entries.forEach { f ->
                            FilterChip(selected = kindFilter == f, onClick = { kindFilter = f }, label = { Text(f.label) })
                        }
                    }
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text("Buscar pelo nome") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    if (shown.isEmpty()) Text("Nenhum arquivo com esse filtro.", style = MaterialTheme.typography.bodySmall)
                }
                items(shown, key = { it.id }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenRecent(r) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.width(56.dp),
                        ) {
                            Text(kindLabel(r.kind), Modifier.padding(vertical = 6.dp), textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(dateFmt.format(Date(r.openedAt)), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { store.remove(r.id); version++ }) { Text("Remover") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item {
                    Text(
                        "As cópias dos arquivos recentes ficam só neste aparelho (máx. 30).",
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { confirmClear = true }) { Text("Limpar lista") }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Limpar recentes?") },
            text = { Text("As cópias guardadas no app serão apagadas. Os arquivos originais no WhatsApp não mudam.") },
            confirmButton = { TextButton(onClick = { store.clear(); version++; confirmClear = false }) { Text("Limpar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocScaffold(
    title: String,
    dirty: Boolean,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val requestClose = { if (dirty) confirm = true else onClose() }
    BackHandler(onBack = requestClose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = requestClose) { Text("Fechar") } },
                actions = actions,
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Descartar alterações?") },
            text = { Text("Há alterações que ainda não foram salvas neste arquivo.") },
            confirmButton = { TextButton(onClick = { confirm = false; onClose() }) { Text("Descartar") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Continuar editando") } },
        )
    }
}

/** Escreve no destino escolhido pelo usuário e avisa o resultado. Devolve true se salvou. */
@Composable
fun rememberSaveAction(
    mime: String,
    onSaved: () -> Unit,
    write: suspend (OutputStream) -> Unit,
): (String) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: return@withContext false
                    out.use { write(it) }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(ctx, if (ok) "Arquivo salvo." else "Não foi possível salvar.", Toast.LENGTH_LONG).show()
            if (ok) onSaved()
        }
    }
    return { suggested -> launcher.launch(suggested) }
}

fun editedName(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) + "-editado" + name.substring(dot) else "$name-editado"
}
