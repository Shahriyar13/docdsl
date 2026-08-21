package app.duss.docdsl.openpdf

import app.duss.docdsl.Align
import app.duss.docdsl.Block
import app.duss.docdsl.Borders
import app.duss.docdsl.Cell
import app.duss.docdsl.Column
import app.duss.docdsl.ColumnWidth
import app.duss.docdsl.DocumentSpec
import app.duss.docdsl.ImageSource
import app.duss.docdsl.ListEntry
import app.duss.docdsl.Padding
import app.duss.docdsl.Run
import app.duss.docdsl.TableStyle
import app.duss.docdsl.TextRun
import app.duss.docdsl.TokenRun
import app.duss.docdsl.VAlign
import org.openpdf.text.Chunk
import org.openpdf.text.Document
import org.openpdf.text.Element
import org.openpdf.text.Image
import org.openpdf.text.ListItem
import org.openpdf.text.Paragraph
import org.openpdf.text.Phrase
import org.openpdf.text.Rectangle
import org.openpdf.text.pdf.PdfPCell
import org.openpdf.text.pdf.PdfPTable
import org.openpdf.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Writes a [DocumentSpec] out as a PDF.
 *
 * The only class in this library that knows a PDF library exists. Everything it consumes is plain data, so a
 * second renderer — HTML, a spreadsheet — is a sibling of this file rather than a reason to describe every
 * document again.
 */
public class OpenPdfRenderer(
    private val theme: PdfTheme = PdfTheme(),
) {

    /** Renders [spec] into [out]. The stream is flushed but not closed. */
    public fun render(spec: DocumentSpec, out: OutputStream) {
        val geometry = theme.page
        val document = Document(
            geometry.rectangle(),
            geometry.marginStart,
            geometry.marginEnd,
            geometry.marginTop,
            geometry.marginBottom,
        )
        val writer = PdfWriter.getInstance(document, out)

        // Set before opening: the event has to see the first page too.
        val frame = PageFrameEvent(spec.frame, theme, this)
        writer.pageEvent = frame

        document.open()
        spec.body.forEach { block -> document.add(block, geometry.contentWidthPoints) }
        // Closing fires onCloseDocument, which is where the page total is finally known and written.
        document.close()
        out.flush()
    }

    /** Renders [spec] and hands back the bytes, for callers that are not writing to a file. */
    public fun renderToBytes(spec: DocumentSpec): ByteArray =
        ByteArrayOutputStream().also { render(spec, it) }.toByteArray()

    // -----------------------------------------------------------------------------------------------------
    //  Blocks
    // -----------------------------------------------------------------------------------------------------

    private fun Document.add(block: Block, availableWidth: Float) {
        when (block) {
            is Block.Paragraph -> add(paragraphOf(block, resolveTokens = null))
            is Block.Table -> add(tableOf(block, availableWidth))
            is Block.Bullets -> add(bulletsOf(block))
            is Block.Picture -> add(pictureOf(block))
            is Block.Group -> {
                // keepTogether needs a container to apply to, so a group that asks for it becomes a single
                // borderless one-cell table holding its children.
                if (block.keepTogether) {
                    add(
                        tableOf(
                            Block.Table(
                                columns = listOf(Column(width = ColumnWidth.Flexible, align = Align.Start)),
                                rows = listOf(app.duss.docdsl.Row(listOf(Cell(content = block.blocks)))),
                                style = TableStyle.Layout.copy(keepTogether = true),
                            ),
                            availableWidth,
                        )
                    )
                } else {
                    block.blocks.forEach { add(it, availableWidth) }
                }
            }
            is Block.Spacer -> add(Paragraph(Chunk("\n")).also { it.spacingAfter = block.points })
            Block.PageBreak -> newPage()
        }
    }

    /** The element form of a block, for the places that need one rather than adding straight to a document. */
    internal fun elementOf(block: Block, availableWidth: Float): Element = when (block) {
        is Block.Paragraph -> paragraphOf(block, resolveTokens = null)
        is Block.Table -> tableOf(block, availableWidth)
        is Block.Bullets -> bulletsOf(block)
        is Block.Picture -> pictureOf(block)
        is Block.Group -> {
            // Inside a cell there is nowhere to "add several things", so a group becomes a nested layout table.
            tableOf(
                Block.Table(
                    columns = listOf(Column(width = ColumnWidth.Flexible, align = Align.Start)),
                    rows = block.blocks.map { app.duss.docdsl.Row(listOf(Cell(content = listOf(it)))) },
                    style = TableStyle.Layout.copy(keepTogether = block.keepTogether),
                ),
                availableWidth,
            )
        }
        is Block.Spacer -> Paragraph(Chunk("\n")).also { it.spacingAfter = block.points }
        Block.PageBreak -> Paragraph(Chunk.NEXTPAGE)
    }

    internal fun paragraphOf(block: Block.Paragraph, resolveTokens: TokenResolver?): Paragraph {
        val paragraph = Paragraph()
        paragraph.alignment = block.align.toElementAlignment()
        block.runs.forEach { run -> paragraph.add(chunkOf(run, resolveTokens)) }
        return paragraph
    }

    /**
     * One run as a chunk.
     *
     * A [TokenRun] is where the page numbering happens. `CurrentPage` is known as the page is written;
     * `TotalPages` is not — it becomes a reserved template the resolver fills in once the document closes.
     */
    private fun chunkOf(run: Run, resolveTokens: TokenResolver?): Element {
        val font = theme.fontFor(run.style)
        return when (run) {
            is TextRun -> Chunk(run.text, font)
            is TokenRun -> resolveTokens?.resolve(run, font) ?: Chunk("", font)
        }
    }

    private fun bulletsOf(block: Block.Bullets): org.openpdf.text.List {
        val list = org.openpdf.text.List(block.numbered, block.indentPoints)
        block.entries.forEach { entry ->
            when (entry) {
                is ListEntry.Item -> {
                    val phrase = Phrase()
                    entry.runs.forEach { run -> phrase.add(chunkOf(run, resolveTokens = null)) }
                    list.add(ListItem(phrase))
                }
                is ListEntry.Sub -> list.add(bulletsOf(entry.list))
            }
        }
        return list
    }

    private fun pictureOf(block: Block.Picture): Image {
        val image = when (val source = block.source) {
            is ImageSource.Path -> Image.getInstance(source.value)
            is ImageSource.Bytes -> Image.getInstance(source.value)
        }
        if (block.maxWidthPoints != null || block.maxHeightPoints != null) {
            image.scaleToFit(
                block.maxWidthPoints ?: theme.page.contentWidthPoints,
                block.maxHeightPoints ?: theme.page.heightPoints,
            )
        }
        image.alignment = block.align.toElementAlignment()
        return image
    }

    // -----------------------------------------------------------------------------------------------------
    //  Tables
    // -----------------------------------------------------------------------------------------------------

    internal fun tableOf(block: Block.Table, availableWidth: Float): PdfPTable {
        val visible = visibleColumns(block)
        if (visible.isEmpty()) return PdfPTable(1)

        val tableWidth = availableWidth * block.style.widthFraction
        val widths = columnWidths(visible, block.rows, tableWidth)

        val table = PdfPTable(visible.size)
        table.widthPercentage = block.style.widthFraction * 100f
        table.horizontalAlignment = block.style.flowAlign.toElementAlignment()
        table.keepTogether = block.style.keepTogether
        table.isSplitLate = false
        table.setWidths(widths)

        val hasHeader = visible.any { it.column.title != null }
        if (hasHeader) {
            visible.forEach { (column, _) ->
                table.addCell(headerCell(column, block.style))
            }
            if (block.style.repeatHeader) table.headerRows = 1
        }

        block.rows.forEach { row ->
            visible.forEachIndexed { position, indexed ->
                val cell = row.cells.getOrNull(indexed.index) ?: Cell()
                // Each cell is told the width its column ended up with, so a nested table inside it can size
                // its own columns against something real instead of guessing at the page.
                table.addCell(bodyCell(cell, indexed.column, block.style, widthPointsOf(widths, position, tableWidth)))
            }
        }
        return table
    }

    private fun widthPointsOf(widths: FloatArray, position: Int, tableWidth: Float): Float {
        val total = widths.sum()
        if (total <= 0f) return tableWidth
        return tableWidth * (widths[position] / total)
    }

    private data class IndexedColumn(val column: Column, val index: Int)


    /**
     * The columns that survive [Column.hideWhenEmpty].
     *
     * Dropping a column means dropping its title, its width and its cell in every row together. Doing that
     * here, once, is the point: done by hand at the call site it is three parallel lists that have to be kept
     * in step, and they are exactly what drifts apart.
     */
    private fun visibleColumns(block: Block.Table): List<IndexedColumn> =
        block.columns.mapIndexedNotNull { index, column ->
            if (!column.hideWhenEmpty) return@mapIndexedNotNull IndexedColumn(column, index)
            val anyContent = block.rows.any { row ->
                row.cells.getOrNull(index)?.content?.isNotEmpty() == true
            }
            if (anyContent) IndexedColumn(column, index) else null
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
     */
    private fun columnWidths(
        visible: List<IndexedColumn>,
        rows: List<app.duss.docdsl.Row>,
        tableWidth: Float,
    ): FloatArray {
        val widths = FloatArray(visible.size)

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
            val header = indexed.column.title?.let { theme.fontFor(null).widthOf(it) } ?: 0f
            val content = rows.maxOfOrNull { row ->
                naturalWidth(row.cells.getOrNull(indexed.index))
            } ?: 0f
            widths[position] = maxOf(header, content) + theme.autoColumnSlackPoints
            remaining -= widths[position]
        }

        val flexible = visible.indices.filter { visible[it].column.width == ColumnWidth.Flexible }
        if (flexible.isNotEmpty()) {
            val each = (remaining / flexible.size).coerceAtLeast(theme.minFlexibleColumnPoints)
            flexible.forEach { widths[it] = each }
        } else if (remaining < 0f) {
            // Nothing flexible to absorb the overflow, so shrink everything proportionally instead of letting
            // openpdf silently overrun the margin.
            val total = widths.sum()
            if (total > 0f) {
                val scale = tableWidth / total
                widths.indices.forEach { widths[it] = widths[it] * scale }
            }
        }

        // A zero anywhere would make setWidths throw, and an all-zero table would divide by zero downstream.
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
    private fun naturalWidth(cell: Cell?): Float {
        if (cell == null) return 0f
        return cell.content.maxOfOrNull { block -> naturalWidth(block) } ?: 0f
    }

    private fun naturalWidth(block: Block): Float = when (block) {
        is Block.Paragraph -> block.runs.sumOf { run ->
            when (run) {
                is TextRun -> theme.fontFor(run.style).widthOf(run.text).toDouble()
                // A token stands in for a page number: two digits' worth is plenty and never wraps.
                is TokenRun -> theme.fontFor(run.style).widthOf("00").toDouble()
            }
        }.toFloat()
        is Block.Group -> block.blocks.maxOfOrNull { naturalWidth(it) } ?: 0f
        is Block.Bullets -> 0f
        is Block.Picture -> block.maxWidthPoints ?: 0f
        is Block.Spacer -> 0f
        Block.PageBreak -> 0f
        is Block.Table -> 0f
    }

    private fun headerCell(column: Column, style: TableStyle): PdfPCell {
        val cell = PdfPCell(Phrase(column.title.orEmpty(), theme.fontFor(null)))
        cell.horizontalAlignment = column.align.toElementAlignment()
        cell.verticalAlignment = Element.ALIGN_MIDDLE
        style.headerBackground?.let { cell.backgroundColor = it.toAwt() }
        applyBorders(cell, style.cellBorders)
        applyPadding(cell, style.cellPadding)
        return cell
    }

    private fun bodyCell(
        cell: Cell,
        column: Column,
        style: TableStyle,
        columnWidth: Float,
    ): PdfPCell {
        val target = PdfPCell()
        target.horizontalAlignment = (cell.align ?: column.align).toElementAlignment()
        target.verticalAlignment = (cell.vAlign ?: style.cellVAlign).toVerticalAlignment()
        cell.background?.let { target.backgroundColor = it.toAwt() }
        cell.minHeightPoints?.let { target.minimumHeight = it }
        applyBorders(target, cell.borders ?: style.cellBorders)
        applyPadding(target, cell.padding ?: style.cellPadding)

        if (cell.content.isEmpty()) {
            target.addElement(Phrase(""))
        } else {
            cell.content.forEach { block -> target.addElement(elementOf(block, columnWidth)) }
        }
        return target
    }

    private fun applyBorders(cell: PdfPCell, borders: Borders) {
        var mask = 0
        if (borders.top > 0f) mask = mask or Rectangle.TOP
        if (borders.bottom > 0f) mask = mask or Rectangle.BOTTOM
        if (borders.start > 0f) mask = mask or Rectangle.LEFT
        if (borders.end > 0f) mask = mask or Rectangle.RIGHT
        cell.border = mask
        if (mask == 0) return
        cell.borderColor = borders.color.toAwt()
        if (borders.top > 0f) cell.borderWidthTop = borders.top
        if (borders.bottom > 0f) cell.borderWidthBottom = borders.bottom
        if (borders.start > 0f) cell.borderWidthLeft = borders.start
        if (borders.end > 0f) cell.borderWidthRight = borders.end
    }

    private fun applyPadding(cell: PdfPCell, padding: Padding) {
        padding.top?.let { cell.paddingTop = it }
        padding.bottom?.let { cell.paddingBottom = it }
        padding.start?.let { cell.paddingLeft = it }
        padding.end?.let { cell.paddingRight = it }
    }
}

internal fun VAlign.toVerticalAlignment(): Int = when (this) {
    VAlign.Top -> Element.ALIGN_TOP
    VAlign.Middle -> Element.ALIGN_MIDDLE
    VAlign.Bottom -> Element.ALIGN_BOTTOM
}

/** Turns a [TokenRun] into something drawable. Implemented by the page-frame event, which knows the page. */
internal interface TokenResolver {
    fun resolve(run: TokenRun, font: org.openpdf.text.Font): Element
}
