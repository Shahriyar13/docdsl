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
        )

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
