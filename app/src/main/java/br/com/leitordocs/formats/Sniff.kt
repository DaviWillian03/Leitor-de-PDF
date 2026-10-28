package br.com.leitordocs.formats

enum class DocKind { PDF, DOCX, XLSX, LEGACY_OFFICE, UNKNOWN }

/** Identifica o tipo pelo conteúdo — nomes e MIME vindos do WhatsApp nem sempre são confiáveis. */
object Sniff {
    fun kindOf(bytes: ByteArray): DocKind {
        if (bytes.size < 4) return DocKind.UNKNOWN
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        if (head.contains("%PDF")) return DocKind.PDF
        if (bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            val names = runCatching { OoxmlZip.entryNames(bytes) }.getOrDefault(emptySet())
            return when {
                "word/document.xml" in names -> DocKind.DOCX
                "xl/workbook.xml" in names -> DocKind.XLSX
                else -> DocKind.UNKNOWN
            }
        }
        val ole = intArrayOf(0xD0, 0xCF, 0x11, 0xE0)
        if (ole.indices.all { (bytes[it].toInt() and 0xFF) == ole[it] }) return DocKind.LEGACY_OFFICE
        return DocKind.UNKNOWN
    }
}
