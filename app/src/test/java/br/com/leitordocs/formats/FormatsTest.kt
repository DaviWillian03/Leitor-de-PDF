package br.com.leitordocs.formats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {
    private val wns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val sns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val rns = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val pkgRel = "http://schemas.openxmlformats.org/package/2006/relationships"

    private fun zip(vararg e: Pair<String, String>) =
        OoxmlZip.write(LinkedHashMap(e.associate { it.first to it.second.toByteArray() }))

    private fun docx() = zip(
        "[Content_Types].xml" to "<Types/>",
        "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="$wns"><w:body>
<w:p><w:r><w:t>Olá </w:t></w:r><w:r><w:t>mundo</w:t></w:r></w:p>
<w:p/>
<w:sectPr/></w:body></w:document>""",
    )

    private fun xlsx() = zip(
        "[Content_Types].xml" to """<Types><Override PartName="/xl/calcChain.xml" ContentType="x"/></Types>""",
        "xl/workbook.xml" to """<workbook xmlns="$sns" xmlns:r="$rns"><sheets><sheet name="Vendas" sheetId="1" r:id="rId1"/></sheets><calcPr calcId="1"/></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="$pkgRel"><Relationship Id="rId1" Type="t" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="t/calcChain" Target="calcChain.xml"/></Relationships>""",
        "xl/calcChain.xml" to "<calcChain/>",
        "xl/sharedStrings.xml" to """<sst xmlns="$sns"><si><t>Produto</t></si></sst>""",
        "xl/worksheets/sheet1.xml" to """<worksheet xmlns="$sns"><sheetData>
<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><v>10</v></c></row>
<row r="3"><c r="A3"><f>B1*2</f><v>20</v></c></row></sheetData></worksheet>""",
    )

    @Test
    fun sniffIdentificaTipos() {
        assertEquals(DocKind.DOCX, Sniff.kindOf(docx()))
        assertEquals(DocKind.XLSX, Sniff.kindOf(xlsx()))
        assertEquals(DocKind.PDF, Sniff.kindOf("%PDF-1.7 ...".toByteArray()))
        assertEquals(DocKind.UNKNOWN, Sniff.kindOf("texto qualquer".toByteArray()))
    }

    @Test
    fun docxLeEEditaParagrafos() {
        val d = DocxDoc.open(docx())
        assertEquals(listOf("Olá mundo", ""), d.paragraphTexts())
        d.setParagraph(0, "Texto novo")
        d.setParagraph(1, "Segundo")
        d.addParagraph("Último")
        val back = DocxDoc.open(d.toBytes())
        assertEquals(listOf("Texto novo", "Segundo", "Último"), back.paragraphTexts())
    }

    @Test
    fun xlsxLeCelulasEFormulas() {
        val d = XlsxDoc.open(xlsx())
        assertEquals("Vendas", d.sheets[0].name)
        val s = d.readSheet(0)
        assertEquals("Produto", s.cells[0 to 0])
        assertEquals("10", s.cells[0 to 1])
        assertEquals("20", s.cells[2 to 0])
        assertTrue((2 to 0) in s.formulas)
        assertEquals(3, s.rowCount)
        assertEquals(2, s.colCount)
    }

    @Test
    fun xlsxEditaMantendoOrdemELimpandoCalcChain() {
        val d = XlsxDoc.open(xlsx())
        d.setCell(0, 0, 1, "12,5")      // número com vírgula
        d.setCell(0, 1, 0, "Caneta")    // linha nova entre as existentes
        d.setCell(0, 2, 0, "00123")     // código: continua texto
        val bytes = d.toBytes()
        val s = XlsxDoc.open(bytes).readSheet(0)
        assertEquals("12.5", s.cells[0 to 1])
        assertEquals("Caneta", s.cells[1 to 0])
        assertEquals("00123", s.cells[2 to 0])
        assertFalse((2 to 0) in s.formulas)

        val parts = OoxmlZip.read(bytes)
        assertFalse(parts.containsKey("xl/calcChain.xml"))
        assertFalse(String(parts.getValue("[Content_Types].xml")).contains("calcChain"))
        assertFalse(String(parts.getValue("xl/_rels/workbook.xml.rels")).contains("calcChain"))
        assertTrue(String(parts.getValue("xl/workbook.xml")).contains("fullCalcOnLoad"))

        val rows = Regex("<row r=\"(\\d+)\"").findAll(String(parts.getValue("xl/worksheets/sheet1.xml")))
            .map { it.groupValues[1].toInt() }.toList()
        assertEquals(listOf(1, 2, 3), rows)
    }

    @Test
    fun refs() {
        assertEquals(0 to 0, XlsxDoc.parseRef("A1"))
        assertEquals(11 to 27, XlsxDoc.parseRef("AB12"))
        assertEquals("AB", XlsxDoc.colName(27))
        assertEquals("Z", XlsxDoc.colName(25))
    }
}
