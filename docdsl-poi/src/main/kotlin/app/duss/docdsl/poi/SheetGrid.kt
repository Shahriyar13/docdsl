package app.duss.docdsl.poi

import app.duss.docdsl.Align
import app.duss.docdsl.Block
import app.duss.docdsl.Borders
import app.duss.docdsl.Cell
import app.duss.docdsl.Column
import app.duss.docdsl.DocColor
import app.duss.docdsl.ImageSource
import app.duss.docdsl.ListEntry
import app.duss.docdsl.Run
import app.duss.docdsl.TableLayout
import app.duss.docdsl.TableStyle
import app.duss.docdsl.TextMeasurer
import app.duss.docdsl.TextRun
import app.duss.docdsl.TextStyle
import app.duss.docdsl.VAlign
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A laid-out document, in the only shape a spreadsheet can hold one.
 *
 * The whole difficulty of rendering a document to a sheet is here. A page is free-form — every table on it
 * chooses its own column widths, and a cell can contain another table — while a sheet has exactly **one**
 * column grid that everything on it must share.
 *
 * The reconciliation is to keep laying the document out in points, exactly as the PDF renderer does, and then
 * to make the sheet's physical columns the **union of every x position any table's edge lands on**. A logical
 * cell then occupies a merged range of those physical columns. A 45/55 information grid and a five-column
 * item table below it therefore keep their real proportions and still share one grid, which is what makes the
 * workbook read as the document rather than as a spreadsheet of it.
 *
 * Positions are held in quarter-points as `Int` rather than in `Float` points, because two edges that should
 * be the same edge have to compare equal — and two floats arrived at by different routes very often do not.
 */
internal class SheetGrid {
    val cells: MutableList<PlacedCell> = mutableListOf()

    /**
     * Borders belonging to a cell whose content had to be laid out as several sheet cells.
     *
     * A cell holding a nested table cannot be one sheet cell, so its own box is drawn afterwards around the
     * region its content occupies. See [SheetLayout.placeCell].
     */
    val frames: MutableList<PlacedFrame> = mutableListOf()
    val pictures: MutableList<PlacedPicture> = mutableListOf()

    /** Every x position any table edge lands on. The sheet's physical columns are the gaps between them. */
    val boundaries: MutableSet<Int> = sortedSetOf()

    /** Rows a spacer asked for a specific height, keyed by row index. */
    val spacerHeights: MutableMap<Int, Float> = mutableMapOf()

    /** Rows after which the printed sheet should break, mirroring [Block.PageBreak]. */
    val pageBreakRows: MutableSet<Int> = mutableSetOf()

    var rowCount: Int = 0
}

/** One piece of content, placed on the grid. */
internal data class PlacedCell(
    val row: Int,
    val rowSpan: Int,
    val left: Int,
    val right: Int,
    val runs: List<Run>,
    val align: Align,
    val vAlign: VAlign,
    val borders: Borders,
    val background: DocColor?,
    val minHeightPoints: Float?,
    val defaultStyle: TextStyle?,
)

/** A box drawn around a region rather than around a single cell. */
internal data class PlacedFrame(
    val row: Int,
    val rowSpan: Int,
    val left: Int,
    val right: Int,
    val borders: Borders,
)

internal data class PlacedPicture(
    val row: Int,
    val rowSpan: Int,
    val left: Int,
    val source: ImageSource,
    val maxWidthPoints: Float?,
    val maxHeightPoints: Float?,
)

/** Quarter-point resolution: fine enough that no two real edges collide, coarse enough that they coalesce. */
private const val SCALE = 4f

internal fun scaleX(points: Float): Int = (points * SCALE).roundToInt()

internal fun unscaleX(units: Int): Float = units / SCALE

/**
 * Walks a document's blocks and decides where everything sits.
 *
 * Pure arithmetic — it knows nothing about POI, which is what makes it testable and what keeps the emission
 * step in [ExcelRenderer] down to "write what this says".
 */
internal class SheetLayout(
    private val theme: ExcelTheme,
    private val measurer: TextMeasurer,
) {

    private val grid = SheetGrid()

    fun layout(blocks: List<Block>, widthPoints: Float): SheetGrid {
        val right = scaleX(widthPoints)
        grid.boundaries += 0
        grid.boundaries += right

        var row = 0
        blocks.forEach { row = place(it, left = 0, right = right, row = row) }
        grid.rowCount = row
        return grid
    }

    // -----------------------------------------------------------------------------------------------------
    //  Blocks
    // -----------------------------------------------------------------------------------------------------

    /** Places one block in the horizontal band [left]..[right], starting at [row]. Returns the next free row. */
    private fun place(block: Block, left: Int, right: Int, row: Int): Int = when (block) {
        is Block.Paragraph -> {
            grid.cells += PlacedCell(
                row = row,
                rowSpan = 1,
                left = left,
                right = right,
                runs = block.runs,
                align = block.align,
                vAlign = VAlign.Top,
                borders = Borders.None,
                background = null,
                minHeightPoints = null,
                defaultStyle = null,
            )
            row + 1
        }

        is Block.Spacer -> {
            grid.spacerHeights[row] = block.points
            row + 1
        }

        // POI breaks *after* the row it is given, so a break before the first row has nowhere to go — which is
        // right: a document that opens with a page break does not want a blank sheet in front of it.
        Block.PageBreak -> {
            if (row > 0) grid.pageBreakRows += row - 1
            row
        }

        is Block.Group -> block.blocks.fold(row) { current, child -> place(child, left, right, current) }

        is Block.Bullets -> placeBullets(block, left, right, row)

        is Block.Picture -> {
            val rows = pictureRows(block)
            grid.pictures += PlacedPicture(row, rows, left, block.source, block.maxWidthPoints, block.maxHeightPoints)
            row + rows
        }

        is Block.Table -> placeTable(block, left, right, row)
    }

    /**
     * A list, one entry per row, indented by shifting its band rather than by padding the text.
     *
     * Shifting adds an x boundary, so the indent is a real column edge that lines up with everything else on
     * the sheet — the same treatment a table's columns get, for the same reason.
     */
    private fun placeBullets(block: Block.Bullets, left: Int, right: Int, row: Int): Int {
        val indented = left + scaleX(block.indentPoints)
        grid.boundaries += indented

        var current = row
        block.entries.forEachIndexed { index, entry ->
            when (entry) {
                is ListEntry.Item -> {
                    val marker = if (block.numbered) "${index + 1}. " else "• "
                    grid.cells += PlacedCell(
                        row = current,
                        rowSpan = 1,
                        left = indented,
                        right = right,
                        runs = listOf(TextRun(marker, entry.runs.firstOrNull()?.style)) + entry.runs,
                        align = Align.Start,
                        vAlign = VAlign.Top,
                        borders = Borders.None,
                        background = null,
                        minHeightPoints = null,
                        defaultStyle = null,
                    )
                    current++
                }
                is ListEntry.Sub -> current = placeBullets(entry.list, indented, right, current)
            }
        }
        return current
    }

    // -----------------------------------------------------------------------------------------------------
    //  Tables
    // -----------------------------------------------------------------------------------------------------

    private fun placeTable(block: Block.Table, left: Int, right: Int, row: Int): Int {
        val visible = TableLayout.visibleColumns(block)
        if (visible.isEmpty()) return row

        val bandPoints = unscaleX(right - left)
        val tableWidthPoints = bandPoints * block.style.widthFraction
        val widths = TableLayout.columnWidths(
            visible = visible,
            rows = block.rows,
            tableWidth = tableWidthPoints,
            measurer = measurer,
            slackPoints = theme.autoColumnSlackPoints,
            minFlexiblePoints = theme.minFlexibleColumnPoints,
        )

        // Normalised to the table's width, exactly as openpdf's `setWidths` treats the same array: the numbers
        // are a proportion, not an absolute demand, and a table narrower than its columns want must still fit
        // inside the band it was given.
        val demanded = widths.sum()
        val factor = if (demanded > 0f) tableWidthPoints / demanded else 1f

        val tableWidth = scaleX(tableWidthPoints)
        val tableLeft = when (block.style.flowAlign) {
            Align.End -> right - tableWidth
            Align.Center -> left + (right - left - tableWidth) / 2
            else -> left
        }

        val edges = IntArray(visible.size + 1)
        edges[0] = tableLeft
        var accumulated = 0f
        widths.forEachIndexed { index, width ->
            accumulated += width * factor
            edges[index + 1] = tableLeft + scaleX(accumulated)
        }
        edges.forEach { grid.boundaries += it }

        var current = row

        // The header row exists only when some column named itself — the same rule that turns the same Table
        // type into a banner, a totals block or a layout pane.
        if (visible.any { it.column.title != null }) {
            visible.forEachIndexed { position, indexed ->
                grid.cells += PlacedCell(
                    row = current,
                    rowSpan = 1,
                    left = edges[position],
                    right = edges[position + 1],
                    runs = listOf(TextRun(indexed.column.title.orEmpty())),
                    align = indexed.column.headerAlignOrDefault,
                    vAlign = VAlign.Middle,
                    borders = block.style.cellBorders,
                    background = block.style.headerBackground,
                    minHeightPoints = null,
                    defaultStyle = null,
                )
            }
            current++
        }

        block.rows.forEach { tableRow ->
            // Every cell in a row occupies the same rows, so the row is as tall as its most demanding cell and
            // the others merge vertically to match. Without that the columns would slide out of step the
            // moment one cell held a nested table.
            val span = visible.maxOf { rowsNeeded(tableRow.cells.getOrNull(it.index)) }.coerceAtLeast(1)
            visible.forEachIndexed { position, indexed ->
                placeCell(
                    cell = tableRow.cells.getOrNull(indexed.index) ?: Cell(),
                    column = indexed.column,
                    style = block.style,
                    left = edges[position],
                    right = edges[position + 1],
                    row = current,
                    span = span,
                )
            }
            current += span
        }
        return current
    }

    /**
     * One table cell.
     *
     * A cell holding nothing or a single paragraph — which is nearly all of them — becomes one sheet cell,
     * merged across its columns and down its rows.
     *
     * Anything richer has to be laid out as several sheet cells, and then its own box is drawn around the
     * region afterwards as a [PlacedFrame]. That is the one place a spreadsheet cannot do what a page does:
     * the outer border and the nested content's borders are two rectangles in the PDF and one region here, so
     * where they do not coincide the workbook shows the outer one and the inner grid rather than both.
     */
    private fun placeCell(
        cell: Cell,
        column: Column,
        style: TableStyle,
        left: Int,
        right: Int,
        row: Int,
        span: Int,
    ) {
        val borders = cell.borders ?: style.cellBorders
        val single = cell.content.singleOrNull()

        if (cell.content.isEmpty() || single is Block.Paragraph) {
            grid.cells += PlacedCell(
                row = row,
                rowSpan = span,
                left = left,
                right = right,
                runs = (single as? Block.Paragraph)?.runs.orEmpty(),
                // The cell's own alignment wins, then its column's. A paragraph inside a table cell does not
                // get to disagree with the column it is in — the same precedence the PDF renderer applies.
                align = cell.align ?: column.align,
                vAlign = cell.vAlign ?: style.cellVAlign,
                borders = borders,
                background = cell.background,
                minHeightPoints = cell.minHeightPoints,
                defaultStyle = null,
            )
            return
        }

        var current = row
        cell.content.forEach { current = place(it, left, right, current) }
        if (borders != Borders.None) {
            grid.frames += PlacedFrame(row, span, left, right, borders)
        }
    }

    // -----------------------------------------------------------------------------------------------------
    //  How many rows something needs
    // -----------------------------------------------------------------------------------------------------

    private fun rowsNeeded(cell: Cell?): Int {
        if (cell == null || cell.content.isEmpty()) return 1
        return cell.content.sumOf { rowsNeeded(it) }.coerceAtLeast(1)
    }

    private fun rowsNeeded(block: Block): Int = when (block) {
        is Block.Paragraph -> 1
        is Block.Spacer -> 1
        Block.PageBreak -> 0
        is Block.Group -> block.blocks.sumOf { rowsNeeded(it) }.coerceAtLeast(1)
        is Block.Picture -> pictureRows(block)
        is Block.Bullets -> block.entries.sumOf { entry ->
            when (entry) {
                is ListEntry.Item -> 1
                is ListEntry.Sub -> rowsNeeded(entry.list)
            }
        }.coerceAtLeast(1)
        is Block.Table -> {
            val visible = TableLayout.visibleColumns(block)
            val header = if (visible.any { it.column.title != null }) 1 else 0
            header + block.rows.sumOf { tableRow ->
                visible.maxOfOrNull { rowsNeeded(tableRow.cells.getOrNull(it.index)) }?.coerceAtLeast(1) ?: 1
            }
        }
    }

    /** A picture is given as many rows as its height needs, so the rows below it are not written over. */
    private fun pictureRows(block: Block.Picture): Int {
        val height = block.maxHeightPoints ?: return 1
        val lineHeight = theme.defaultSizePoints * theme.lineHeightFactor
        return ceil(height / lineHeight).toInt().coerceAtLeast(1)
    }
}
