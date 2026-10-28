package br.com.leitordocs.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var loading by remember { mutableStateOf(false) }
    var openRequest by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(incoming) { if (incoming != null) openRequest = incoming }
    LaunchedEffect(openRequest) {
        val uri = openRequest ?: return@LaunchedEffect
        loading = true
        val previous = loaded
        loaded = Loader.load(ctx, uri)
        if (previous is Loaded.Pdf) previous.source.close()
        loading = false
        openRequest = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openRequest = uri
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
            )
        }
    }
}

@Composable
private fun HomeScreen(error: String?, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        Arrangement.Center,
        Alignment.CenterHorizontally,
    ) {
        Text("Leitor de Documentos", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Abra PDF, Word (.docx) e Excel (.xlsx). Para abrir direto do WhatsApp, toque no arquivo " +
                "e escolha este app em “Abrir com”.",
            Modifier.padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (error != null) {
            Text(error, Modifier.padding(bottom = 16.dp), color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = onPick) { Text("Abrir arquivo") }
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
