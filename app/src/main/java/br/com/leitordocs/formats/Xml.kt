package br.com.leitordocs.formats

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

internal object Xml {
    fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // Arquivos vêm de terceiros (WhatsApp): sem DOCTYPE/entidades externas.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.isExpandEntityReferences = false }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    fun serialize(doc: Document): ByteArray {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        val out = ByteArrayOutputStream()
        transformer.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }
}

internal fun Element.childElements(): List<Element> {
    val result = ArrayList<Element>()
    var n: Node? = firstChild
    while (n != null) {
        if (n is Element) result.add(n)
        n = n.nextSibling
    }
    return result
}

internal fun Element.childNamed(local: String): Element? =
    childElements().firstOrNull { it.localName == local }

/** Leitura/escrita do contêiner ZIP dos formatos .docx e .xlsx. */
internal object OoxmlZip {
    private const val MAX_TOTAL = 200L * 1024 * 1024

    fun read(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory) continue
                val buf = ByteArrayOutputStream()
                total += copyLimited(zin, buf, MAX_TOTAL - total)
                out[entry.name] = buf.toByteArray()
            }
        }
        return out
    }

    private fun copyLimited(input: InputStream, out: ByteArrayOutputStream, limit: Long): Long {
        val chunk = ByteArray(16 * 1024)
        var copied = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            copied += n
            require(copied <= limit) { "Arquivo descompactado grande demais." }
            out.write(chunk, 0, n)
        }
        return copied
    }

    fun write(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zout ->
            for ((name, data) in entries) {
                zout.putNextEntry(ZipEntry(name))
                zout.write(data)
                zout.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun entryNames(bytes: ByteArray): Set<String> {
        val names = HashSet<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                names.add(e.name)
            }
        }
        return names
    }
}
