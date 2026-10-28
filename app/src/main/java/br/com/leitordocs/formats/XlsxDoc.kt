package br.com.leitordocs.formats

import org.w3c.dom.Document
import org.w3c.dom.Element

typealias CellPos = Pair<Int, Int> // (linha, coluna), começando em 0

class SheetData(
    val cells: Map<CellPos, String>,
    val formulas: Set<CellPos>,
    val rowCount: Int,
    val colCount: Int,
)

class SheetRef(val name: String, val path: String)

/**
 * Leitura e edição de células de .xlsx. Mostra o valor guardado no arquivo (para fórmulas, o último
 * valor calculado); datas aparecem como número de série. Ao salvar, o Excel recalcula as fórmulas ao
 * abrir. Estilos, gráficos e demais partes do arquivo são preservados.
 */
class XlsxDoc private constructor(private val entries: LinkedHashMap<String, ByteArray>) {
    val sheets: List<SheetRef>
    private val shared: List<String>
    private val parsed = HashMap<String, Document>()
    private val dirty = HashSet<String>()

    init {
        val wb = Xml.parse(entries["xl/workbook.xml"] ?: error("xl/workbook.xml ausente"))
        val targets = HashMap<String, String>()
        entries["xl/_rels/workbook.xml.rels"]?.let { relBytes ->
            val rels = Xml.parse(relBytes).getElementsByTagName("Relationship")
            for (i in 0 until rels.length) {
                val r = rels.item(i) as Element
                val t = r.getAttribute("Target")
                targets[r.getAttribute("Id")] = if (t.startsWith("/")) t.removePrefix("/") else "xl/$t"
            }
        }
        val list = ArrayList<SheetRef>()
        val nodes = wb.getElementsByTagNameNS(NS_MAIN, "sheet")
        for (i in 0 until nodes.length) {
            val s = nodes.item(i) as Element
            val path = targets[s.getAttributeNS(NS_R, "id")] ?: continue
            if (entries.containsKey(path)) list.add(SheetRef(s.getAttribute("name"), path))
        }
        sheets = list
        shared = entries["xl/sharedStrings.xml"]?.let { readShared(Xml.parse(it)) } ?: emptyList()
    }

    private fun readShared(doc: Document): List<String> {
        val result = ArrayList<String>()
        val sis = doc.getElementsByTagNameNS(NS_MAIN, "si")
        for (i in 0 until sis.length) result.add(richText(sis.item(i) as Element))
        return result
    }

    /** Texto de <si>/<is>: junta <t> e <r><t>, ignorando fonética (<rPh>). */
    private fun richText(e: Element): String {
        val sb = StringBuilder()
        for (c in e.childElements()) {
            when (c.localName) {
                "t" -> sb.append(c.textContent)
                "r" -> c.childNamed("t")?.let { sb.append(it.textContent) }
            }
        }
        return sb.toString()
    }

    private fun sheetDoc(path: String): Document =
        parsed.getOrPut(path) { Xml.parse(entries.getValue(path)) }

    fun readSheet(index: Int): SheetData {
        val doc = sheetDoc(sheets[index].path)
        val cells = HashMap<CellPos, String>()
        val formulas = HashSet<CellPos>()
        var maxRow = 0
        var maxCol = 0
        val nodes = doc.getElementsByTagNameNS(NS_MAIN, "c")
        for (i in 0 until nodes.length) {
            val c = nodes.item(i) as Element
            val pos = parseRef(c.getAttribute("r")) ?: continue
            val text = cellText(c)
            if (c.childNamed("f") != null) formulas.add(pos)
            if (text.isEmpty()) continue
            cells[pos] = text
            if (pos.first + 1 > maxRow) maxRow = pos.first + 1
            if (pos.second + 1 > maxCol) maxCol = pos.second + 1
        }
        return SheetData(cells, formulas, maxRow, maxCol)
    }

    private fun cellText(c: Element): String {
        val v = c.childNamed("v")?.textContent.orEmpty()
        return when (c.getAttribute("t")) {
            "s" -> shared.getOrNull(v.toIntOrNull() ?: -1).orEmpty()
            "inlineStr" -> c.childNamed("is")?.let { richText(it) }.orEmpty()
            "b" -> if (v == "1") "VERDADEIRO" else "FALSO"
            else -> v
        }
    }

    fun setCell(sheet: Int, row: Int, col: Int, text: String) {
        val path = sheets[sheet].path
        val doc = sheetDoc(path)
        val sheetData = doc.getElementsByTagNameNS(NS_MAIN, "sheetData").item(0) as? Element
            ?: error("Planilha sem dados")
        val rowEl = findOrCreateRow(doc, sheetData, row)
        val cell = findOrCreateCell(doc, rowEl, row, col)

        while (cell.firstChild != null) cell.removeChild(cell.firstChild)
        cell.removeAttribute("t")
        val value = text.trim()
        when {
            value.isEmpty() -> Unit
            isNumber(value) -> {
                val v = doc.createElementNS(NS_MAIN, "v")
                v.textContent = value.replace(',', '.')
                cell.appendChild(v)
            }
            else -> {
                cell.setAttribute("t", "inlineStr")
                val isEl = doc.createElementNS(NS_MAIN, "is")
                val t = doc.createElementNS(NS_MAIN, "t")
                t.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
                t.textContent = text
                isEl.appendChild(t)
                cell.appendChild(isEl)
            }
        }
        dirty.add(path)
    }

    /** Insere uma linha vazia na posição [at] (0 = primeira); as linhas de baixo descem. */
    fun insertRow(sheet: Int, at: Int) {
        val path = sheets[sheet].path
        val doc = sheetDoc(path)
        checkRowOpsAllowed(doc, deleting = null)
        RowShifter.apply(doc, RowShift.Insert(at + 1))
        dirty.add(path)
    }

    /** Exclui a linha [at] (0 = primeira); as linhas de baixo sobem. */
    fun deleteRow(sheet: Int, at: Int) {
        val path = sheets[sheet].path
        val doc = sheetDoc(path)
        checkRowOpsAllowed(doc, deleting = at + 1)
        val sheetData = doc.getElementsByTagNameNS(NS_MAIN, "sheetData").item(0) as Element
        sheetData.childElements()
            .firstOrNull { it.localName == "row" && it.getAttribute("r").toIntOrNull() == at + 1 }
            ?.let { sheetData.removeChild(it) }
        RowShifter.apply(doc, RowShift.Delete(at + 1))
        dirty.add(path)
    }

    /** Recusa casos em que deslocar linhas corromperia o arquivo (melhor avisar que estragar). */
    private fun checkRowOpsAllowed(doc: Document, deleting: Int?) {
        val tables = doc.getElementsByTagNameNS(NS_MAIN, "tablePart")
        if (tables.length > 0) {
            throw UnsupportedOperationException(
                "Esta planilha tem uma Tabela formatada do Excel. Inserir ou excluir linhas nela não é suportado aqui.",
            )
        }
        if (deleting == null) return
        val fs = doc.getElementsByTagNameNS(NS_MAIN, "f")
        for (i in 0 until fs.length) {
            val f = fs.item(i) as Element
            if (f.getAttribute("t") != "shared" || !f.hasAttribute("ref")) continue
            val cell = f.parentNode as? Element ?: continue
            if (parseRef(cell.getAttribute("r"))?.first == deleting - 1) {
                throw UnsupportedOperationException(
                    "Esta linha tem uma fórmula usada por outras células. Para não estragar a planilha, exclua-a no Excel.",
                )
            }
        }
    }

    /** true se a aba tem fórmulas (o usuário deve conferir depois de inserir/excluir linhas). */
    fun hasFormulas(sheet: Int): Boolean =
        sheetDoc(sheets[sheet].path).getElementsByTagNameNS(NS_MAIN, "f").length > 0

    private fun isNumber(s: String): Boolean {
        val n = s.replace(',', '.')
        if (!NUMBER.matches(n)) return false
        if (n.length > 15) return false // evita perder dígitos (ex.: códigos longos)
        if (LEADING_ZERO.matches(n)) return false // "00123" é código, não número
        return true
    }

    private fun findOrCreateRow(doc: Document, sheetData: Element, row: Int): Element {
        var before: Element? = null
        for (r in sheetData.childElements()) {
            if (r.localName != "row") continue
            val n = r.getAttribute("r").toIntOrNull() ?: continue
            if (n == row + 1) {
                r.removeAttribute("spans")
                return r
            }
            if (n > row + 1) {
                before = r
                break
            }
        }
        val el = doc.createElementNS(NS_MAIN, "row")
        el.setAttribute("r", (row + 1).toString())
        sheetData.insertBefore(el, before)
        return el
    }

    private fun findOrCreateCell(doc: Document, rowEl: Element, row: Int, col: Int): Element {
        var before: Element? = null
        for (c in rowEl.childElements()) {
            if (c.localName != "c") continue
            val pos = parseRef(c.getAttribute("r")) ?: continue
            if (pos.second == col) return c
            if (pos.second > col) {
                before = c
                break
            }
        }
        val el = doc.createElementNS(NS_MAIN, "c")
        el.setAttribute("r", colName(col) + (row + 1))
        rowEl.insertBefore(el, before)
        return el
    }

    fun toBytes(): ByteArray {
        val out = LinkedHashMap(entries)
        if (dirty.isNotEmpty()) {
            for (path in dirty) out[path] = Xml.serialize(parsed.getValue(path))
            dropCalcChainAndForceRecalc(out)
        }
        return OoxmlZip.write(out)
    }

    /** A cadeia de cálculo fica velha após editar; removê-la é seguro e o Excel a reconstrói. */
    private fun dropCalcChainAndForceRecalc(out: LinkedHashMap<String, ByteArray>) {
        out.remove("xl/calcChain.xml")
        fun rewrite(name: String, transform: (String) -> String) {
            val bytes = out[name] ?: return
            out[name] = transform(String(bytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        }
        rewrite("[Content_Types].xml") { it.replace(Regex("<Override\\b[^>]*calcChain[^>]*/>"), "") }
        rewrite("xl/_rels/workbook.xml.rels") {
            it.replace(Regex("<Relationship\\b[^>]*calcChain[^>]*/>"), "")
        }
        rewrite("xl/workbook.xml") { s ->
            when {
                s.contains("fullCalcOnLoad") -> s
                s.contains("<calcPr") -> s.replaceFirst("<calcPr", "<calcPr fullCalcOnLoad=\"1\"")
                s.contains("</workbook>") -> s.replaceFirst("</workbook>", "<calcPr fullCalcOnLoad=\"1\"/></workbook>")
                else -> s
            }
        }
    }

    companion object {
        private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private val NUMBER = Regex("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")
        private val LEADING_ZERO = Regex("0\\d.*")

        fun open(bytes: ByteArray) = XlsxDoc(OoxmlZip.read(bytes))

        fun parseRef(ref: String): CellPos? {
            var i = 0
            var col = 0
            while (i < ref.length && ref[i].isLetter()) {
                col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1)
                i++
            }
            val row = ref.substring(i).toIntOrNull() ?: return null
            if (i == 0 || row < 1) return null
            return Pair(row - 1, col - 1)
        }

        fun colName(col: Int): String {
            var n = col + 1
            val sb = StringBuilder()
            while (n > 0) {
                val r = (n - 1) % 26
                sb.insert(0, 'A' + r)
                n = (n - 1) / 26
            }
            return sb.toString()
        }
    }
}
