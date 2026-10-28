package br.com.leitordocs.formats

import java.text.Collator
import java.util.Locale

/** Filtro de uma coluna, como o Autofiltro do Excel: texto contido e/ou lista de valores permitidos. */
data class ColumnFilter(val contains: String = "", val allowed: Set<String>? = null) {
    val active: Boolean get() = contains.isNotBlank() || allowed != null

    fun matches(value: String): Boolean =
        (contains.isBlank() || value.contains(contains.trim(), ignoreCase = true)) &&
            (allowed == null || value in allowed)
}

data class SortSpec(val col: Int, val ascending: Boolean)

/** Filtrar e ordenar só mudam o que aparece na tela; o arquivo não é alterado. */
object SheetFilter {
    private val collator: Collator = Collator.getInstance(Locale("pt", "BR")).apply { strength = Collator.SECONDARY }

    /**
     * Linhas (índices reais) a mostrar. Com [headerRow], a linha 0 fica sempre no topo e fora do filtro.
     * Sem filtro ativo, mostra também as linhas vazias até [totalRows] para permitir digitar nelas.
     */
    fun visibleRows(
        data: SheetData,
        totalRows: Int,
        headerRow: Boolean,
        filters: Map<Int, ColumnFilter>,
        sort: SortSpec?,
    ): List<Int> {
        val first = if (headerRow) 1 else 0
        val active = filters.filterValues { it.active }
        val last = if (active.isEmpty()) totalRows else data.rowCount
        var rows = (first until last).filter { r ->
            active.all { (c, f) -> f.matches(data.cells[r to c].orEmpty()) }
        }
        if (sort != null) {
            val cmp = Comparator<Int> { a, b -> compareValues(data.cells[a to sort.col].orEmpty(), data.cells[b to sort.col].orEmpty()) }
            // Vazias sempre no fim, como no Excel.
            val (empty, filled) = rows.partition { data.cells[it to sort.col].isNullOrEmpty() }
            rows = filled.sortedWith(if (sort.ascending) cmp else cmp.reversed()) + empty
        }
        return if (headerRow && totalRows > 0) listOf(0) + rows else rows
    }

    /** Valores distintos da coluna (para a lista de seleção), em ordem; "" representa células vazias. */
    fun distinctValues(data: SheetData, col: Int, headerRow: Boolean): List<String> {
        val first = if (headerRow) 1 else 0
        return (first until data.rowCount).map { data.cells[it to col].orEmpty() }
            .distinct()
            .sortedWith { a, b -> if (a.isEmpty() != b.isEmpty()) (if (a.isEmpty()) 1 else -1) else compareValues(a, b) }
    }

    /** Números comparados como números (aceita vírgula), depois textos em ordem alfabética pt-BR. */
    fun compareValues(a: String, b: String): Int {
        val na = a.replace(',', '.').toDoubleOrNull()
        val nb = b.replace(',', '.').toDoubleOrNull()
        return when {
            na != null && nb != null -> na.compareTo(nb)
            na != null -> -1
            nb != null -> 1
            else -> collator.compare(a, b)
        }
    }
}
