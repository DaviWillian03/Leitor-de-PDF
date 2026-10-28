package br.com.leitordocs

import android.content.Context
import br.com.leitordocs.formats.DocKind
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class RecentFile(val id: String, val name: String, val kind: DocKind, val openedAt: Long, val size: Long)

/**
 * Lista dos últimos arquivos abertos. Guarda uma cópia de cada um no armazenamento interno do app (só neste
 * aparelho), porque a permissão que o WhatsApp dá para ler o arquivo acaba quando o app fecha.
 */
class RecentStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "recentes").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("recentes", Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<RecentFile> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentFile(o.getString("id"), o.getString("name"), DocKind.valueOf(o.getString("kind")), o.getLong("at"), o.getLong("size"))
            }
        }.getOrDefault(emptyList()).filter { file(it).exists() }
    }

    @Synchronized
    fun add(name: String, kind: DocKind, bytes: ByteArray): RecentFile {
        val current = list().toMutableList()
        // Mesmo arquivo reenviado: substitui a entrada antiga.
        current.filter { it.name == name && it.size == bytes.size.toLong() }.forEach {
            file(it).delete()
            current.remove(it)
        }
        val entry = RecentFile(UUID.randomUUID().toString(), name, kind, System.currentTimeMillis(), bytes.size.toLong())
        file(entry).writeBytes(bytes)
        current.add(0, entry)
        while (current.size > MAX) file(current.removeAt(current.lastIndex)).delete()
        save(current)
        return entry
    }

    @Synchronized
    fun touch(entry: RecentFile) {
        val current = list().toMutableList()
        current.removeAll { it.id == entry.id }
        current.add(0, RecentFile(entry.id, entry.name, entry.kind, System.currentTimeMillis(), entry.size))
        save(current)
    }

    @Synchronized
    fun remove(id: String) {
        val current = list().toMutableList()
        current.filter { it.id == id }.forEach { file(it).delete() }
        current.removeAll { it.id == id }
        save(current)
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        prefs.edit().remove(KEY).apply()
    }

    fun file(entry: RecentFile) = File(dir, entry.id)

    private fun save(list: List<RecentFile>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind.name)
                    .put("at", it.openedAt).put("size", it.size),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "lista"
        const val MAX = 30
    }
}
