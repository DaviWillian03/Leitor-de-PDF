package br.com.leitordocs.formats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditingTest {
    private val wns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val sns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val rns = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun zip(vararg e: Pair<String, String>) =
        OoxmlZip.write(LinkedHashMap(e.associate { it.first to it.second.toByteArray() }))

    private fun xlsx(sheet: String) = zip(
        "xl/workbook.xml" to """<workbook xmlns="$sns" xmlns:r="$rns"><sheets><sheet name="A" sheetId="1" r:id="rId1"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="p"><Relationship Id="rId1" Type="t" Target="worksheets/sheet1.xml"/></Relationships>""",
        "xl/worksheets/sheet1.xml" to """<worksheet xmlns="$sns">$sheet</worksheet>""",
    )

    private fun sheetXml(bytes: ByteArray) = String(OoxmlZip.read(bytes).getValue("xl/worksheets/sheet1.xml"))

    private val base = """<dimension ref="A1:B4"/><sheetData>
<row r="1"><c r="A1" t="inlineStr"><is><t>Nome</t></is></c><c r="B1" t="inlineStr"><is><t>Qtd</t></is></c></row>
<row r="2"><c r="A2" t="inlineStr"><is><t>Caneta</t></is></c><c r="B2"><v>3</v></c></row>
<row r="3"><c r="A3" t="inlineStr"><is><t>Lápis</t></is></c><c r="B3"><v>5</v></c></row>
<row r="4"><c r="A4" t="inlineStr"><is><t>Total</t></is></c><c r="B4"><f>SUM(B2:B3)+${'$'}B${'$'}3</f><v>13</v></c></row>
</sheetData><mergeCells count="1"><mergeCell ref="A3:B3"/></mergeCells>"""

    @Test
    fun formulasDeslocam() {
        val ins = RowShift.Insert(3)
        assertEquals("SUM(B2:B4)+\$B\$4", RowShifter.shiftFormula("SUM(B2:B3)+\$B\$3", ins))
        assertEquals("Outra!B3+B4", RowShifter.shiftFormula("Outra!B3+B3", ins))
        assertEquals("\"B3\"&LOG10(B4)", RowShifter.shiftFormula("\"B3\"&LOG10(B3)", ins))
        val del = RowShift.Delete(3)
        assertEquals("SUM(B2:B2)+#REF!", RowShifter.shiftFormula("SUM(B2:B3)+B3", del))
        assertEquals("SUM(B2:B3)", RowShifter.shiftFormula("SUM(B2:B4)", del))
        assertNull(RowShifter.shiftRefList("A3:B3", del))
    }

    @Test
    fun inserirLinha() {
        val d = XlsxDoc.open(xlsx(base))
        d.insertRow(0, 2) // antes de "Lápis"
        val bytes = d.toBytes()
        val s = XlsxDoc.open(bytes).readSheet(0)
        assertEquals("Caneta", s.cells[1 to 0])
        assertNull(s.cells[2 to 0])
        assertEquals("Lápis", s.cells[3 to 0])
        assertEquals("Total", s.cells[4 to 0])
        val xml = sheetXml(bytes)
        assertTrue(xml, xml.contains("SUM(B2:B4)+\$B\$4"))
        assertTrue(xml.contains("<mergeCell ref=\"A4:B4\"/>"))
        assertTrue(xml.contains("<dimension ref=\"A1:B5\"/>"))
    }

    @Test
    fun excluirLinha() {
        val d = XlsxDoc.open(xlsx(base))
        d.deleteRow(0, 2) // "Lápis"
        val bytes = d.toBytes()
        val s = XlsxDoc.open(bytes).readSheet(0)
        assertEquals("Caneta", s.cells[1 to 0])
        assertEquals("Total", s.cells[2 to 0])
        assertEquals(3, s.rowCount)
        val xml = sheetXml(bytes)
        assertTrue(xml, xml.contains("SUM(B2:B2)+#REF!"))
        assertFalse(xml.contains("mergeCell"))
    }

    @Test(expected = UnsupportedOperationException::class)
    fun recusaPlanilhaComTabela() {
        XlsxDoc.open(xlsx(base + """<tableParts count="1"><tablePart xmlns:r="$rns" r:id="rId9"/></tableParts>""")).insertRow(0, 1)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun recusaExcluirFormulaCompartilhadaMestre() {
        val sheet = """<sheetData><row r="1"><c r="A1"><f t="shared" ref="A1:A2" si="0">B1</f></c></row>
<row r="2"><c r="A2"><f t="shared" si="0"/></c></row></sheetData>"""
        XlsxDoc.open(xlsx(sheet)).deleteRow(0, 0)
    }

    @Test
    fun filtroEOrdenacao() {
        val s = XlsxDoc.open(xlsx(base)).readSheet(0)
        val f = mapOf(0 to ColumnFilter(contains = "a"))
        assertEquals(listOf(0, 1, 3), SheetFilter.visibleRows(s, 10, true, f, null)) // Caneta, Total
        val byQtdDesc = SheetFilter.visibleRows(s, 4, true, emptyMap(), SortSpec(1, ascending = false))
        assertEquals(listOf(0, 3, 2, 1), byQtdDesc) // 13, 5, 3
        val only = mapOf(0 to ColumnFilter(allowed = setOf("Lápis")))
        assertEquals(listOf(0, 2), SheetFilter.visibleRows(s, 10, true, only, null))
        assertEquals(listOf("Caneta", "Lápis", "Total"), SheetFilter.distinctValues(s, 0, true))
        assertTrue(SheetFilter.compareValues("2", "10") < 0)
        assertTrue(SheetFilter.compareValues("abacate", "Banana") < 0)
    }

    @Test
    fun docxExcluirEInserirNoMeio() {
        val bytes = zip(
            "word/document.xml" to """<w:document xmlns:w="$wns"><w:body>
<w:p><w:pPr><w:pStyle w:val="Titulo"/></w:pPr><w:r><w:t>Um</w:t></w:r></w:p>
<w:p><w:r><w:t>Dois</w:t></w:r></w:p>
<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Célula</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
<w:sectPr/></w:body></w:document>""",
        )
        val d = DocxDoc.open(bytes)
        d.insertParagraphAfter(0, "Um e meio")
        d.deleteParagraph(2) // "Dois"
        d.deleteParagraph(2) // única da célula: só limpa
        val back = DocxDoc.open(d.toBytes())
        assertEquals(listOf("Um", "Um e meio", ""), back.paragraphTexts())
        assertTrue(String(OoxmlZip.read(d.toBytes()).getValue("word/document.xml")).contains("Titulo"))
    }
}
