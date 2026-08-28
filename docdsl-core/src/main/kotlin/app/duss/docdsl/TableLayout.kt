package app.duss.docdsl

/**
 * How wide a piece of text is, in points, in whatever face the renderer will draw it with.
 *
 * The one thing table layout cannot work out on its own. A PDF renderer answers this from real font metrics;
 * a spreadsheet renderer answers it approximately, because a spreadsheet's own column widths are approximate
 * anyway. Either way the arithmetic on top of it is the same, which is the point of putting it behind an
 * interface rather than letting each renderer grow its own.
 */
public fun interface TextMeasurer {
    public fun widthOf(text: String, style: TextStyle?): Float
}

/** A column that survived hiding, and where it sits in the table's original column list. */
public data class VisibleColumn(public val column: Column, public val index: Int)

/**
 * Turning a [Block.Table] into a set of column widths.
 *
 * Extracted from the PDF renderer when the spreadsheet renderer arrived, because a document that renders to
 * two media has to place its columns the same way in both — otherwise the same 45/55 information grid is a
 * 45/55 split on one and something else on the other, and "the spreadsheet looks like the PDF" stops being
 * true the moment either copy of the arithmetic is touched.
 *
 * It is deliberately pure: no fonts, no page, no library. Everything it needs about the medium arrives as
 * arguments.
 */
public object TableLayout {

    /**
     * The columns that survive [Column.hideWhenEmpty].
     *
     * Dropping a column means dropping its title, its width and its cell in every row together. Doing that
     * here, once, is the point: done by hand at the call site it is three parallel lists that have to be kept
     * in step, and they are exactly what drifts apart.
     */
    public fun visibleColumns(table: Block.Table): List<VisibleColumn> =
        table.columns.mapIndexedNotNull { index, column ->
            if (!column.hideWhenEmpty) return@mapIndexedNotNull VisibleColumn(column, index)
            val anyContent = table.rows.any { row -> row.cells.getOrNull(index)?.content?.isNotEmpty() == true }
            if (anyContent) VisibleColumn(column, index) else null
        }

    /**
     * How wide each column should be, in points.
     *
     * The heart of the width fix. [ColumnWidth.Auto] columns are measured against their real content in the
     * font they will be drawn in and get exactly that much, so a price or a quantity cannot wrap.
     * [ColumnWidth.Flexible] columns divide whatever is left, because prose is the thing that should wrap. A
     * [ColumnWidth.Weight] column takes its stated share first, for layouts that are about geometry rather
     * than content.
     *
     * When the fixed demands exceed the table — very long codes, a narrow table — flexible columns fall to
     * their floor and the auto columns are scaled down together rather than one of them being starved.
     *
     * @param slackPoints extra width allowed on top of measured text, so a column sized to its glyphs does
     *   not wrap on the last character.
     * @param minFlexiblePoints a flexible column never shrinks below this.
     */
    public fun columnWidths(
        visible: List<VisibleColumn>,
        rows: List<Row>,
        tableWidth: Float,
        measurer: TextMeasurer,
        slackPoints: Float,
        minFlexiblePoints: Float,
    ): FloatArray {
        val widths = FloatArray(visible.size)
        if (visible.isEmpty()) return widths

        // Weighted columns are a stated proportion of the whole table, so they come out first.
        val weightTotal = visible.sumOf { (column, _) ->
            ((column.width as? ColumnWidth.Weight)?.value ?: 0f).toDouble()
        }.toFloat()
        var remaining = tableWidth
        visible.forEachIndexed { position, (column, _) ->
            val weight = (column.width as? ColumnWidth.Weight)?.value ?: return@forEachIndexed
            widths[position] = if (weightTotal > 0f) tableWidth * (weight / weightTotal) else 0f
            remaining -= widths[position]
        }

        // Auto columns take what they measure, plus a little slack so the glyphs are not flush to the border.
        visible.forEachIndexed { position, indexed ->
            if (indexed.column.width != ColumnWidth.Auto) return@forEachIndexed
            val header = indexed.column.title?.let { measurer.widthOf(it, null) } ?: 0f
            val content = rows.maxOfOrNull { row ->
                naturalWidth(row.cells.getOrNull(indexed.index), measurer)
            } ?: 0f
            widths[position] = maxOf(header, content) + slackPoints
            remaining -= widths[position]
        }

        val flexible = visible.indices.filter { visible[it].column.width == ColumnWidth.Flexible }
        if (flexible.isNotEmpty()) {
            val each = (remaining / flexible.size).coerceAtLeast(minFlexiblePoints)
            flexible.forEach { widths[it] = each }
        } else if (remaining < 0f) {
            // Nothing flexible to absorb the overflow, so shrink everything proportionally instead of letting
            // the renderer silently overrun the margin.
            val total = widths.sum()
            if (total > 0f) {
                val scale = tableWidth / total
                widths.indices.forEach { widths[it] = widths[it] * scale }
            }
        }

        // A zero anywhere would make openpdf's setWidths throw, and an all-zero table would divide by zero
        // downstream in either renderer.
        if (widths.all { it <= 0f }) return FloatArray(visible.size) { tableWidth / visible.size }
        widths.indices.forEach { if (widths[it] <= 0f) widths[it] = 1f }
        return widths
    }

    /**
     * What a cell's text needs, in points, or zero when it holds something that has no width of its own.
     *
     * A nested table returns zero deliberately: its own width is decided by the column it lands in, so letting
     * it bid would starve the columns that do have a measurable demand. A column holding nested tables should
     * be [ColumnWidth.Flexible].
     */
    public fun naturalWidth(cell: Cell?, measurer: TextMeasurer): Float {
        if (cell == null) return 0f
        return cell.content.maxOfOrNull { block -> naturalWidth(block, measurer) } ?: 0f
    }

    public fun naturalWidth(block: Block, measurer: TextMeasurer): Float = when (block) {
        is Block.Paragraph -> block.runs.sumOf { run ->
            when (run) {
                is TextRun -> measurer.widthOf(run.text, run.style).toDouble()
                // A token stands in for a page number: two digits' worth is plenty and never wraps.
                is TokenRun -> measurer.widthOf("00", run.style).toDouble()
            }
        }.toFloat()
        is Block.Group -> block.blocks.maxOfOrNull { naturalWidth(it, measurer) } ?: 0f
        is Block.Bullets -> 0f
        is Block.Picture -> block.maxWidthPoints ?: 0f
        is Block.Spacer -> 0f
        Block.PageBreak -> 0f
        is Block.Table -> 0f
    }
}
