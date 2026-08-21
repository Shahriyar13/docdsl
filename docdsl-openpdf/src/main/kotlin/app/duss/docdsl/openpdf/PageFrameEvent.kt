package app.duss.docdsl.openpdf

import app.duss.docdsl.Block
import app.duss.docdsl.DocToken
import app.duss.docdsl.PageFrame
import app.duss.docdsl.TokenRun
import org.openpdf.text.Chunk
import org.openpdf.text.Document
import org.openpdf.text.Element
import org.openpdf.text.Font
import org.openpdf.text.Image
import org.openpdf.text.pdf.PdfPTable
import org.openpdf.text.pdf.PdfPageEventHelper
import org.openpdf.text.pdf.PdfTemplate
import org.openpdf.text.pdf.PdfWriter

/**
 * Draws the running header and footer, and answers the page tokens.
 *
 * **Why a page count needs machinery at all.** A PDF is written as a stream: while page 1 is being drawn the
 * last page does not exist, so `TotalPages` has no value yet. The way round it is a template — a small canvas
 * placed into every footer. Each page merely *references* the same template, so writing the number into it once,
 * when the document closes, makes it appear on all of them at once. The alternative is rendering the whole
 * document twice and throwing the first pass away.
 *
 * `CurrentPage` needs none of that: the page number is known as the page is drawn.
 */
internal class PageFrameEvent(
    private val frame: PageFrame,
    private val theme: PdfTheme,
    private val renderer: OpenPdfRenderer,
) : PdfPageEventHelper(), TokenResolver {

    private var totalPagesTemplate: PdfTemplate? = null

    /** Set while a footer is being drawn, so the resolver knows which page it is on. */
    private var pageBeingDrawn: Int = 0

    override fun onOpenDocument(writer: PdfWriter, document: Document) {
        if (!frameMentions(DocToken.TotalPages)) return
        // Room for four digits at the theme's size; the number is drawn left-aligned inside it.
        val font = theme.fontFor(null)
        val width = font.widthOf("0000") + 2f
        totalPagesTemplate = writer.directContent.createTemplate(width, font.calculatedSize + 2f)
    }

    override fun onEndPage(writer: PdfWriter, document: Document) {
        pageBeingDrawn = document.pageNumber
        if (frame.header.isNotEmpty()) {
            // Drawn from the top margin upward, so the header sits above the body rather than over it.
            draw(frame.header, writer, atY = document.pageSize.height - theme.page.marginTop + headerHeight())
        }
        if (frame.footer.isNotEmpty()) {
            draw(frame.footer, writer, atY = theme.page.marginBottom)
        }
    }

    override fun onCloseDocument(writer: PdfWriter, document: Document) {
        val template = totalPagesTemplate ?: return
        val font = theme.fontFor(null)
        val base = runCatching { font.getCalculatedBaseFont(false) }.getOrNull() ?: return
        // By the time the document closes the writer has moved past the last page, so the count is one less.
        val total = (writer.pageNumber - 1).coerceAtLeast(1)
        template.beginText()
        template.setFontAndSize(base, font.calculatedSize)
        template.setTextMatrix(0f, 1f)
        template.showText(total.toString())
        template.endText()
        totalPagesTemplate = null
    }

    // -----------------------------------------------------------------------------------------------------

    override fun resolve(run: TokenRun, font: Font): Element = when (run.token) {
        DocToken.CurrentPage -> Chunk(pageBeingDrawn.toString(), font)
        DocToken.TotalPages -> totalPagesTemplate
            ?.let { Chunk(Image.getInstance(it), 0f, -1f) }
            ?: Chunk("", font)
    }

    /**
     * Frame blocks are written at an absolute position rather than added to the document.
     *
     * They have to be: `document.add` during a page event would append to the page being finished and recurse.
     * Everything is wrapped in one full-width table so a single `writeSelectedRows` places the lot.
     */
    private fun draw(blocks: List<Block>, writer: PdfWriter, atY: Float) {
        val holder = PdfPTable(1)
        holder.totalWidth = theme.page.contentWidthPoints
        holder.isLockedWidth = true
        holder.defaultCell.border = 0
        holder.defaultCell.setPadding(0f)
        blocks.forEach { block ->
            holder.addCell(
                org.openpdf.text.pdf.PdfPCell().also { cell ->
                    cell.border = 0
                    cell.setPadding(0f)
                    cell.addElement(elementWithTokens(block))
                }
            )
        }
        holder.writeSelectedRows(0, -1, theme.page.marginStart, atY, writer.directContent)
    }

    /**
     * A frame block, with its tokens resolved.
     *
     * Only paragraphs are given the resolver: a page number inside a nested table in a footer is not something
     * these documents do, and threading a resolver through the whole renderer to allow it would complicate
     * every signature for a case that does not exist. A token elsewhere renders as nothing rather than wrongly.
     */
    private fun elementWithTokens(block: Block): Element = when (block) {
        is Block.Paragraph -> renderer.paragraphOf(block, resolveTokens = this)
        else -> renderer.elementOf(block, theme.page.contentWidthPoints)
    }

    private fun frameMentions(token: DocToken): Boolean =
        (frame.header + frame.footer).any { block -> mentions(block, token) }

    private fun mentions(block: Block, token: DocToken): Boolean = when (block) {
        is Block.Paragraph -> block.runs.any { it is TokenRun && it.token == token }
        is Block.Group -> block.blocks.any { mentions(it, token) }
        is Block.Table -> block.rows.any { row ->
            row.cells.any { cell -> cell.content.any { mentions(it, token) } }
        }
        else -> false
    }

    /**
     * A rough height for the header block, since `writeSelectedRows` positions by the TOP edge and the caller
     * knows only where the content should start.
     *
     * Approximate on purpose: an exact figure would mean laying the table out twice. One line per block at the
     * theme's size is close enough for a two-or-three-line header, and a taller one should set its own margin.
     */
    private fun headerHeight(): Float = frame.header.size * (theme.defaultSizePoints + 4f)
}

