package br.com.leitordocs.formats

import org.w3c.dom.Document
import org.w3c.dom.Element

/** Como as linhas (numeradas a partir de 1) mudam ao inserir ou excluir uma linha. */
sealed class RowShift {
    /** Nova posição da linha [r]; null se ela foi excluída. */
    abstract fun single(r: Int): Int?

    /** Novo intervalo [a]..[b]; null se ele deixou de existir. */
    abstract fun range(a: Int, b: Int): Pair<Int, Int>?

    class Insert(private val at: Int) : RowShift() {
        override fun single(r: Int) = if (r >= at) r + 1 else r
        override fun range(a: Int, b: Int) = (if (a >= at) a + 1 else a) to (if (b >= at) b + 1 else b)
    }

    class Delete(private val at: Int) : RowShift() {
        override fun single(r: Int) = when {
            r == at -> null
            r > at -> r - 1
            else -> r
        }

        override fun range(a: Int, b: Int): Pair<Int, Int>? {
            val na = if (a > at) a - 1 else a
            val nb = if (b >= at) b - 1 else b
            return if (nb < na) null else na to nb
        }
    }
}

/**
 * Desloca linhas de uma aba: células, fórmulas desta aba, células mescladas, filtros, formatação condicional,
 * validação e links. Não atualiza referências vindas de OUTRAS abas nem nomes definidos (a tela avisa).
 */
internal object RowShifter {
    private val REF_TOKEN = Regex("(\\$?)([A-Z]{1,3})(\\$?)(\\d+)(?::(\\$?)([A-Z]{1,3})(\\$?)(\\d+))?")
    private val FORMULA_REF = Regex(
        "(?<![A-Za-z0-9_.!$':])(\\$?[A-Z]{1,3}\\$?)(\\d+)(?::(\\$?[A-Z]{1,3}\\$?)(\\d+))?(?![A-Za-z0-9_(!])",
    )
    private val RANGE_ATTRS = listOf("ref", "sqref")

    fun apply(doc: Document, shift: RowShift) {
        val all = doc.getElementsByTagName("*")
        val elements = (0 until all.length).map { all.item(it) as Element }
        val toRemove = ArrayList<Element>()
        for (e in elements) {
            when (e.localName) {
                "row" -> {
                    val r = e.getAttribute("r").toIntOrNull() ?: continue
                    val nr = shift.single(r) ?: continue
                    e.setAttribute("r", nr.toString())
                    for (c in e.childElements()) {
                        val pos = XlsxDoc.parseRef(c.getAttribute("r")) ?: continue
                        c.setAttribute("r", XlsxDoc.colName(pos.second) + nr)
                    }
                }
                "f" -> {
                    e.textContent = shiftFormula(e.textContent, shift)
                    if (e.hasAttribute("ref")) shiftRefList(e.getAttribute("ref"), shift)?.let { e.setAttribute("ref", it) }
                }
                "selection" -> {
                    e.removeAttribute("activeCell")
                    e.removeAttribute("sqref")
                }
                else -> for (attr in RANGE_ATTRS) {
                    if (!e.hasAttribute(attr)) continue
                    val v = shiftRefList(e.getAttribute(attr), shift)
                    if (v == null) toRemove.add(e) else e.setAttribute(attr, v)
                }
            }
        }
        for (e in toRemove) {
            val parent = e.parentNode as? Element ?: continue
            parent.removeChild(e)
            // Contêineres como <mergeCells count="..."> ficam inválidos se vazios.
            if (parent.childElements().isEmpty() && parent.localName in setOf("mergeCells", "hyperlinks", "dataValidations")) {
                parent.parentNode?.removeChild(parent)
            } else if (parent.hasAttribute("count")) {
                parent.setAttribute("count", parent.childElements().size.toString())
            }
        }
    }

    /** Lista de intervalos separados por espaço (ex.: "A1:B2 D5"). null se todos sumiram. */
    fun shiftRefList(value: String, shift: RowShift): String? {
        val out = value.split(' ').filter { it.isNotEmpty() }.mapNotNull { token ->
            val m = REF_TOKEN.matchEntire(token) ?: return@mapNotNull token // ex.: "A:A"
            val g = m.groupValues
            if (g[8].isEmpty()) {
                shift.single(g[4].toInt())?.let { "${g[1]}${g[2]}${g[3]}$it" }
            } else {
                shift.range(g[4].toInt(), g[8].toInt())?.let { (a, b) ->
                    "${g[1]}${g[2]}${g[3]}$a:${g[5]}${g[6]}${g[7]}$b"
                }
            }
        }
        return if (out.isEmpty()) null else out.joinToString(" ")
    }

    /** Desloca referências da própria aba; ignora textos entre aspas e referências a outras abas. */
    fun shiftFormula(formula: String, shift: RowShift): String =
        formula.split('"').mapIndexed { i, part ->
            if (i % 2 == 1) {
                part
            } else {
                FORMULA_REF.replace(part) { m ->
                    val g = m.groupValues
                    if (g[3].isEmpty()) {
                        shift.single(g[2].toInt())?.let { "${g[1]}$it" } ?: "#REF!"
                    } else {
                        shift.range(g[2].toInt(), g[4].toInt())?.let { (a, b) -> "${g[1]}$a:${g[3]}$b" } ?: "#REF!"
                    }
                }
            }
        }.joinToString("\"")
}
