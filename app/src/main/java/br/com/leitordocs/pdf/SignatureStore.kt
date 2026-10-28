package br.com.leitordocs.pdf

import android.content.Context

/** Guarda (só neste aparelho, se o usuário pedir) a última assinatura desenhada, em coordenadas do quadro. */
class SignatureStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("assinatura", Context.MODE_PRIVATE)

    fun load(): List<List<NPoint>> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return raw.split('|').filter { it.isNotEmpty() }.map { stroke ->
            stroke.split(';').mapNotNull { p ->
                val xy = p.split(',')
                val x = xy.getOrNull(0)?.toFloatOrNull()
                val y = xy.getOrNull(1)?.toFloatOrNull()
                if (x != null && y != null) NPoint(x, y) else null
            }
        }
    }

    fun save(strokes: List<List<NPoint>>) {
        prefs.edit().putString(KEY, strokes.joinToString("|") { s -> s.joinToString(";") { "${it.x},${it.y}" } }).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "tracos"
    }
}
