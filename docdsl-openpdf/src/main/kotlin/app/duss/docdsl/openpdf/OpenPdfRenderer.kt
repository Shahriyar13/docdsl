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
import app.duss.docdsl.TableLayout
import app.duss.docdsl.TableStyle
import app.duss.docdsl.TextMeasurer
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

    /**
     * Writes just the body of [spec] into a document somebody else opened.
     *
     * The way into an existing codebase. A generator that already owns its page setup, its running header and
     * its footer can describe its *content* with this library and keep everything else exactly as it is —
     * rather than having to port the page furniture on the same day, which is what would make adoption an
     * all-or-nothing rewrite.
     *
     * [DocumentSpec.frame] is deliberately ignored here: the host document already has whatever header and
     * footer it wants, and quietly adding a second set would be worse than not honouring the field. Use
     * [render] when this library is to own the whole page.
     *
     * @param availableWidthPoints the width a full-width table should assume. Defaults to the theme's page
     *   geometry, which is right when the host uses the same paper and margins — pass the host's own figure
     *   when it does not, or tables will be measured against the wrong width.
     */
    public fun renderBody(
        spec: DocumentSpec,
        into: Document,
        availableWidthPoints: Float = theme.page.contentWidthPoints,
    ) {
        spec.body.forEach { block -> into.add(block, availableWidthPoints) }
    }

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
            is Block.Spacer -> add(spacerOf(block.points))
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
        is Block.Spacer -> spacerOf(block.points)
        Block.PageBreak -> Paragraph(Chunk.NEXTPAGE)
    }

    internal fun paragraphOf(block: Block.Paragraph, resolveTokens: TokenResolver?): Paragraph {
        val paragraph = Paragraph()
        paragraph.alignment = block.align.toElementAlignment()
        // Multiplied rather than fixed, so a 14pt title and an 8pt item line each get spacing proportional to
        // themselves. OpenPDF's own default is 1.5, which reads as generously spaced prose in a document that
        // is mostly dense tabular fact — see PdfTheme.lineSpacing.
        paragraph.setLeading(0f, theme.lineSpacing)
        block.runs.forEach { run -> paragraph.add(chunkOf(run, resolveTokens)) }
        return paragraph
    }

    /**
     * Blank vertical space, and exactly as much as was asked for.
     *
     * This used to be a paragraph containing a newline, which costs a whole line of leading *plus* the
     * spacing — so `spacer(10f)` opened a gap of about 25pt. The line is still needed, because OpenPDF drops
     * a paragraph with nothing in it, but its leading is zero, so the gap is the spacing and nothing else.
     */
    private fun spacerOf(points: Float): Paragraph =
        Paragraph(Chunk(" ", theme.fontFor(null))).also {
            it.setLeading(0f, 0f)
            it.spacingAfter = points
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

    /**
     * A list, with room reserved for the widest marker it will actually print.
     *
     * `indentPoints` is the space OpenPDF leaves for the symbol, and a fixed 15pt is enough for "9." and not
     * for "10." — at which point the number runs into the first word and the list reads `10Order
     * Confirmation`. The width is measured from the real count in the font it will be drawn in, so a
     * nine-item list stays tight and a hundred-item one still lines up.
     *
     * Only the items count: a nested list is not numbered by its parent, so it must not widen the gutter for
     * the entries that are.
     */
    private fun bulletsOf(block: Block.Bullets): org.openpdf.text.List {
        val indent = if (!block.numbered) {
            block.indentPoints
        } else {
            val widest = "${block.entries.count { it is ListEntry.Item }}. "
            maxOf(block.indentPoints, theme.fontFor(null).widthOf(widest) + SYMBOL_GAP_POINTS)
        }

        val list = org.openpdf.text.List(block.numbered, indent)
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

    /**
     * How this renderer measures text, for [TableLayout].
     *
     * Real font metrics, in the very font the run will be drawn in — a column measured in one font and drawn
     * in another is how text ends up wrapping when the arithmetic said it fits.
     */
    private val measurer = TextMeasurer { text, style -> theme.fontFor(style).widthOf(text) }

    internal fun tableOf(block: Block.Table, availableWidth: Float): PdfPTable {
        val visible = TableLayout.visibleColumns(block)
        if (visible.isEmpty()) return PdfPTable(1)

        val tableWidth = availableWidth * block.style.widthFraction
        val widths = TableLayout.columnWidths(
            visible = visible,
            rows = block.rows,
            tableWidth = tableWidth,
            measurer = measurer,
            slackPoints = theme.autoColumnSlackPoints,
            minFlexiblePoints = theme.minFlexibleColumnPoints,
            hardMinPoints = theme.hardMinColumnPoints,
            tableHeaderStyle = block.style.headerStyle,
        )

        val table = PdfPTable(visible.size)
        table.widthPercentage = block.style.widthFraction * 100f
        table.horizontalAlignment = block.style.flowAlign.toElementAlignment()
        table.keepTogether = block.style.keepTogether
        table.isSplitLate = false
        // False moves a row too tall for what is left of the page onto the next one whole, instead of
        // breaking it across the boundary. `isSplitLate` above then has nothing to decide: it only chooses
        // *when* to split a row, and this says whether one may be split at all.
        table.isSplitRows = block.style.allowRowSplit
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

    private fun headerCell(column: Column, style: TableStyle): PdfPCell {
        val cell = PdfPCell(Phrase(column.title.orEmpty(), theme.fontFor(style.headerStyleFor(column))))
        cell.horizontalAlignment = column.headerAlignOrDefault.toElementAlignment()
        cell.verticalAlignment = Element.ALIGN_MIDDLE
        style.headerBackground?.let { cell.backgroundColor = it.toAwt() }
        applyBorders(cell, style.cellBorders)
        applyPadding(cell, style.headerPaddingFor(column))
        return cell
    }

    private fun bodyCell(
        cell: Cell,
        column: Column,
        style: TableStyle,
        columnWidth: Float,
    ): PdfPCell {
        // Resolved field by field, which is what [Padding] has always said it does: a cell stating only a
        // top falls back to the table for the other three. `applyPadding` skips nulls, so before this an
        // unstated side silently kept whatever the PdfPCell constructor happened to default to — and the
        // two constructors below do not default to the same thing.
        val padding = cell.padding?.orElse(style.cellPadding) ?: style.cellPadding

        // Padding is space around a cell's *content*. A nested grid is not content — it stands in for column
        // spanning, so its borders are meant to continue the parent's — and padding on the cell draws it
        // inset from the very box it completes. See `Block.Table`'s note on spanning, and `moveOffGrids`.
        //
        // Only when the padding came from the table. An explicit `Cell.padding` is a statement about *this*
        // cell and is honoured as written, which is how a nested table can still be deliberately inset.
        val holdsGrid = cell.padding == null && cell.content.any { it.isGrid() }
        val effective = if (holdsGrid) padding.moveOffGrids(cell.content) else padding

        // The width the content is actually laid out in, which is the column less whatever padding stayed
        // on the cell. It used to be handed the full column width and placed in a narrower box, so an `Auto`
        // column inside a padded cell was measured against a few points it never got.
        val contentWidth = columnWidth - (effective.start ?: 0f) - (effective.end ?: 0f)
        val elements = cell.content.mapIndexed { index, block ->
            elementOf(block, contentWidth).also { element ->
                if (holdsGrid && !block.isGrid()) {
                    element.insetBy(
                        padding = padding,
                        atTop = index == 0,
                        atBottom = index == cell.content.lastIndex,
                    )
                }
            }
        }

        // A cell whose whole content is one grid is built as a **table cell** rather than as a composite of
        // elements, and that is the vertical half of "a nested grid spans its cell".
        //
        // A composite lays the nested table out at its natural height and then positions it, so a one-line
        // grid beside a two-line one is a box floating inside the row with white space above and below,
        // and a grid whose cells are all empty collapses to its padding — 3pt against a neighbour's 12pt.
        // Both were reported as "unsorted borders" on a proforma-invoice header, and both are this one
        // thing. A table cell is stretched to the height the row settles on, so its borders are the row's.
        //
        // **Only when nothing asked for an alignment.** A vertical alignment is what suppresses the
        // stretch: measured on OpenPDF 3.0.0, a one-line grid beside a two-line one fills its 16pt row as a
        // table cell and shrinks back to a floating 8pt box the moment `verticalAlignment` is set to
        // anything but the default. That is coherent rather than a workaround — there is nothing to align
        // something that fills its container — so an explicit `Cell.vAlign` opts out of filling, exactly as
        // an explicit `Cell.padding` opts out of spanning.
        val grid = if (cell.content.singleOrNull()?.isGrid() == true) elements.first() as? PdfPTable else null
        val fillsCell = grid != null && cell.vAlign == null
        val target = if (fillsCell) PdfPCell(grid) else PdfPCell()

        target.horizontalAlignment = (cell.align ?: column.align).toElementAlignment()
        if (!fillsCell) target.verticalAlignment = (cell.vAlign ?: style.cellVAlign).toVerticalAlignment()
        cell.background?.let { target.backgroundColor = it.toAwt() }
        cell.minHeightPoints?.let { target.minimumHeight = it }
        // After the constructor, always: `PdfPCell(PdfPTable)` sets a border and padding of its own, and
        // these are the document's.
        applyBorders(target, cell.borders ?: style.cellBorders)
        applyPadding(target, effective)

        if (!fillsCell) {
            if (elements.isEmpty()) target.addElement(Phrase("")) else elements.forEach(target::addElement)
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

    private companion object {
        /** Clear space between a list's marker and its text, so "10." never touches the first word. */
        const val SYMBOL_GAP_POINTS = 4f
    }
}

/**
 * Whether this block is drawn as a grid whose borders should meet the cell's.
 *
 * A [Block.Group] counts when it holds one, because a group inside a cell becomes a borderless layout table
 * around its children — so the grid is still there, one level down, and still wants the full width.
 */
internal fun Block.isGrid(): Boolean = when (this) {
    is Block.Table -> true
    is Block.Group -> blocks.any { it.isGrid() }
    else -> false
}

/**
 * This padding with the sides a grid touches taken off it.
 *
 * The horizontal padding always goes: a nested grid's left and right borders are the parent cell's, and any
 * side padding is the two grids failing to meet by exactly that many points. The vertical padding goes only
 * at the end the grid actually reaches — so a cell holding a heading above a table keeps the space above the
 * heading and loses the space below the table, which is where the doubled border was.
 *
 * Only called once the caller has established that [content] holds a grid at all; it re-checks which *end*
 * one sits at, which is a different question.
 */
/** This padding with each unstated side taken from [fallback] — what [Padding]'s own doc promises. */
internal fun Padding.orElse(fallback: Padding): Padding = Padding(
    top = top ?: fallback.top,
    bottom = bottom ?: fallback.bottom,
    start = start ?: fallback.start,
    end = end ?: fallback.end,
)

internal fun Padding.moveOffGrids(content: List<Block>): Padding {
    if (content.isEmpty()) return this
    return Padding(
        top = if (content.first().isGrid()) 0f else top,
        bottom = if (content.last().isGrid()) 0f else bottom,
        start = 0f,
        end = 0f,
    )
}

/**
 * Gives a text element the horizontal padding that came off the cell, as indentation.
 *
 * Applied to the paragraphs, lists and images sharing a cell with a grid, so they keep their distance from
 * the border while the grid beside them spans it. [atTop]/[atBottom] carry the vertical padding for the
 * first and last block, which are the only ones it ever applied to.
 *
 * Silent on a [PdfPTable]: that is the grid, and it is the whole point that it is not inset.
 */
internal fun Element.insetBy(padding: Padding, atTop: Boolean, atBottom: Boolean) {
    val start = padding.start ?: 0f
    val end = padding.end ?: 0f
    when (this) {
        is Paragraph -> {
            indentationLeft = start
            indentationRight = end
            if (atTop) spacingBefore = padding.top ?: 0f
            if (atBottom) spacingAfter = padding.bottom ?: 0f
        }
        // Added to whatever the marker gutter already claimed, rather than replacing it — see `bulletsOf`.
        is org.openpdf.text.List -> {
            indentationLeft += start
            indentationRight += end
        }
        is Image -> {
            indentationLeft = start
            indentationRight = end
            if (atTop) spacingBefore = padding.top ?: 0f
            if (atBottom) spacingAfter = padding.bottom ?: 0f
        }
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
