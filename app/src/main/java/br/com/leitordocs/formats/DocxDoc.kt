package br.com.leitordocs.formats

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Edição de texto de .docx por parágrafo. Ao editar um parágrafo, o texto novo vai para o primeiro
 * trecho de texto dele (formatação desse trecho é mantida) e os demais trechos ficam vazios.
 * Imagens, tabelas, cabeçalhos e demais partes do arquivo são preservados como estavam.
 */
class DocxDoc private constructor(private val entries: LinkedHashMap<String, ByteArray>) {
    private val doc: Document = Xml.parse(
        entries["word/document.xml"] ?: error("word/document.xml ausente"),
    )

    private fun paragraphs(): List<Element> {
        val nodes = doc.getElementsByTagNameNS(W, "p")
        val result = ArrayList<Element>()
        for (i in 0 until nodes.length) {
            val p = nodes.item(i) as Element
            if (!insideFallback(p)) result.add(p)
        }
        return result
    }

    private fun insideFallback(e: Element): Boolean {
        var n: Node? = e.parentNode
        while (n != null) {
            if (n is Element && n.localName == "Fallback") return true
            n = n.parentNode
        }
        return false
    }

    fun paragraphTexts(): List<String> = paragraphs().map { textOf(it) }

    private fun textOf(p: Element): String {
        val sb = StringBuilder()
        fun walk(e: Element) {
            for (c in e.childElements()) {
                when (c.localName) {
                    "t" -> sb.append(c.textContent)
                    "tab" -> sb.append('\t')
                    "br", "cr" -> sb.append(' ')
                    else -> walk(c)
                }
            }
        }
        walk(p)
        return sb.toString()
    }

    fun setParagraph(index: Int, text: String) {
        val p = paragraphs()[index]
        val clean = text.replace('\n', ' ')
        val ts = ArrayList<Element>()
        val nodes = p.getElementsByTagNameNS(W, "t")
        for (i in 0 until nodes.length) ts.add(nodes.item(i) as Element)
        if (ts.isEmpty()) {
            p.appendChild(newRun(clean))
            return
        }
        ts[0].textContent = clean
        ts[0].setAttributeNS(XML_NS, "xml:space", "preserve")
        for (i in 1 until ts.size) ts[i].textContent = ""
    }

    fun addParagraph(text: String) {
        val body = doc.getElementsByTagNameNS(W, "body").item(0) as Element
        val p = doc.createElementNS(W, "w:p")
        p.appendChild(newRun(text.replace('\n', ' ')))
        val sectPr = body.childElements().lastOrNull { it.localName == "sectPr" }
        body.insertBefore(p, sectPr)
    }

    private fun newRun(text: String): Element {
        val r = doc.createElementNS(W, "w:r")
        val t = doc.createElementNS(W, "w:t")
        t.setAttributeNS(XML_NS, "xml:space", "preserve")
        t.textContent = text
        r.appendChild(t)
        return r
    }

    fun toBytes(): ByteArray {
        val out = LinkedHashMap(entries)
        out["word/document.xml"] = Xml.serialize(doc)
        return OoxmlZip.write(out)
    }

    companion object {
        private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        private const val XML_NS = "http://www.w3.org/XML/1998/namespace"

        fun open(bytes: ByteArray) = DocxDoc(OoxmlZip.read(bytes))
    }
}
