package app.duss.docdsl.poi

import app.duss.docdsl.Block
import app.duss.docdsl.Borders
import app.duss.docdsl.DocColor
import app.duss.docdsl.DocToken
import app.duss.docdsl.DocumentSpec
import app.duss.docdsl.Emphasis
import app.duss.docdsl.ImageSource
import app.duss.docdsl.PageFrame
import app.duss.docdsl.Run
import app.duss.docdsl.TextRun
import app.duss.docdsl.TextStyle
import app.duss.docdsl.TokenRun
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.PrintSetup
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.RegionUtil
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xssf.usermodel.XSSFRichTextString
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.ceil

/**
 * Writes a [DocumentSpec] out as an .xlsx workbook.
 *
 * The sibling of `OpenPdfRenderer`, and shaped like it on purpose — [render], [renderToBytes] and
 * [renderBody] mean the same three things there as here, so a caller that already produces a spec gets the
 * second format by choosing a different renderer and changing nothing else.
 *
 * **What "looks like the PDF" means.** The document is laid out in points, by the same [app.duss.docdsl.TableLayout]
 * the PDF renderer uses, and the sheet's columns are then made fine enough to hold every edge that layout
 * produced (see [SheetGrid]). Proportions, alignment, borders, shading, bold and colour all carry over. Four
 * things cannot:
 *
 *  - **Row heights are estimated, not measured.** Excel will not auto-fit a row containing a merged cell and
 *    almost every row here has one, so how tall a wrapped paragraph ends up is arithmetic rather than fact.
 *  - **Pagination is Excel's.** [Block.PageBreak] becomes a print break, but where the *other* pages fall is
 *    decided by the print setup, not by this library.
 *  - **A cell holding a nested table** is a region of sheet cells rather than one cell, so its own box is
 *    drawn around that region — see [SheetLayout.placeCell].
 *  - **Page tokens** have no meaning in a cell. `&P`/`&N` in the printed header and footer carry them
 *    instead, which is why [DocumentSpec.frame] maps to the sheet's print header and footer in [render].
 */
public class ExcelRenderer(
    private val theme: ExcelTheme = ExcelTheme(),
) {

    /** How this renderer measures text, for [app.duss.docdsl.TableLayout]. */
    private val measurer = HelveticaMeasurer(theme.defaultSizePoints)

    /**
     * Renders [spec] into [out] as a whole workbook. The stream is flushed but not closed.
     *
     * @param sheetName what the tab is called. Excel forbids `:\/?*[]` and more than 31 characters, so the
     *   name given is trimmed to fit rather than allowed to throw at write time.
     */
    public fun render(spec: DocumentSpec, out: OutputStream, sheetName: String = "Document") {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet(safeSheetName(sheetName))
            renderBody(spec, sheet)
            applyPageSetup(sheet, spec.frame)
            workbook.write(out)
        }
        out.flush()
    }

    /** Renders [spec] and hands back the bytes, for callers that are not writing to a file. */
    public fun renderToBytes(spec: DocumentSpec, sheetName: String = "Document"): ByteArray =
        ByteArrayOutputStream().also { render(spec, it, sheetName) }.toByteArray()

    /**
     * Writes just the body of [spec] into a sheet somebody else created.
     *
     * The way into an existing codebase, and the counterpart of `OpenPdfRenderer.renderBody`. A caller that
     * already owns its workbook — several documents as several tabs, a summary sheet in front of them — keeps
     * all of that and describes only the content.
     *
     * [DocumentSpec.frame] is deliberately ignored here, exactly as it is for a PDF written into someone
     * else's document: the host owns the print setup, and quietly overwriting its header would be worse than
     * not honouring the field.
     *
     * @param startRow the first row to write on, so a caller can put something above the document.
     * @param availableWidthPoints the width a full-width table should assume. Defaults to the theme's sheet
     *   geometry, which is right when the workbook is this document and nothing else.
     * @return the row after the last one written.
     */
    public fun renderBody(
        spec: DocumentSpec,
        into: XSSFSheet,
        startRow: Int = 0,
        availableWidthPoints: Float = theme.sheet.contentWidthPoints,
    ): Int {
        val grid = SheetLayout(theme, measurer).layout(spec.body, availableWidthPoints)
        return Emitter(into, grid, startRow).emit()
    }

    // -----------------------------------------------------------------------------------------------------
    //  Emission
    // -----------------------------------------------------------------------------------------------------

    /**
     * Writes a laid-out [SheetGrid] into a sheet.
     *
     * Its own class because it carries three pieces of state that would otherwise be arguments to everything:
     * where the document starts on the sheet, how an x position maps to a column index, and the style cache.
     */
    private inner class Emitter(
        private val sheet: XSSFSheet,
        private val grid: SheetGrid,
        private val startRow: Int,
    ) {
        private val workbook: XSSFWorkbook = sheet.workbook

        /** Every x an edge landed on, ascending. The physical columns are the gaps between consecutive pairs. */
        private val edges: List<Int> = grid.boundaries.sorted()

        /** Built once per distinct look. POI caps a workbook at 64k styles and creating one per cell is slow. */
        private val styles: MutableMap<StyleKey, XSSFCellStyle> = mutableMapOf()
        private val fonts: MutableMap<FontKey, XSSFFont> = mutableMapOf()

        /** Tallest thing to have landed on each row, so a height can be set once the whole grid is placed. */
        private val rowHeights: MutableMap<Int, Float> = mutableMapOf()

        fun emit(): Int {
            applyColumnWidths()
            grid.cells.forEach { write(it) }
            grid.frames.forEach { frame(it) }
            grid.pictures.forEach { picture(it) }
            applyRowHeights()
            grid.pageBreakRows.forEach { sheet.setRowBreak(startRow + it) }
            return startRow + grid.rowCount
        }

        // -- geometry -------------------------------------------------------------------------------------

        /** The rightmost physical column. There is one fewer column than there are edges. */
        private val lastColumn: Int = (edges.size - 2).coerceAtLeast(0)

        /** Where an x position sits among the edges, whether or not it is one of them. */
        private fun edgeIndexOf(x: Int): Int {
            val index = edges.binarySearch(x)
            return if (index >= 0) index else (-index - 1)
        }

        /** The first physical column a span covers. */
        private fun firstColumnOf(x: Int): Int = edgeIndexOf(x).coerceIn(0, lastColumn)

        /**
         * The last physical column a span covers.
         *
         * The right edge is *exclusive* — a cell running to edge `i` fills the column before it — which is
         * why this is not [firstColumnOf] with a different argument. Getting that wrong costs a span its last
         * column, and does it silently: the merge is one narrower than it should be and only shows as a
         * hairline of unfilled sheet down the right of every full-width block.
         */
        private fun lastColumnOf(x: Int, from: Int): Int = (edgeIndexOf(x) - 1).coerceIn(from, lastColumn)

        private fun applyColumnWidths() {
            for (index in 0 until edges.size - 1) {
                val points = unscaleX(edges[index + 1] - edges[index])
                sheet.setColumnWidth(index, points.toColumnWidthUnits())
            }
        }

        // -- cells ----------------------------------------------------------------------------------------

        private fun write(placed: PlacedCell) {
            val first = firstColumnOf(placed.left)
            val last = lastColumnOf(placed.right, first)
            val top = startRow + placed.row
            val bottom = startRow + placed.row + placed.rowSpan - 1

            val text = placed.runs.textOf()
            val style = styleFor(placed, wrap = text.contains(' ') || text.contains('\n'))

            // Every cell of a merged region needs the style, not just its top-left: Excel draws each cell's
            // own borders and fill, and styling only the anchor leaves a region with one side of a box.
            for (rowIndex in top..bottom) {
                val row = sheet.getRow(rowIndex) ?: sheet.createRow(rowIndex)
                for (columnIndex in first..last) {
                    val cell = row.getCell(columnIndex) ?: row.createCell(columnIndex)
                    cell.cellStyle = style
                }
            }

            if (text.isNotEmpty()) {
                sheet.getRow(top).getCell(first).setCellValue(richTextOf(placed.runs))
            }
            if (last > first || bottom > top) {
                sheet.addMergedRegion(CellRangeAddress(top, bottom, first, last))
            }

            recordHeight(placed, text, first, last, top, bottom)
        }

        /**
         * The box a cell draws around content that had to be laid out as several cells.
         *
         * `RegionUtil` puts a border on the region's perimeter rather than on every cell in it, which is
         * exactly the difference between a box around a nested table and a grid through it.
         */
        private fun frame(placed: PlacedFrame) {
            val first = firstColumnOf(placed.left)
            val last = lastColumnOf(placed.right, first)
            val top = startRow + placed.row
            val bottom = startRow + placed.row + placed.rowSpan - 1
            for (rowIndex in top..bottom) {
                val row = sheet.getRow(rowIndex) ?: sheet.createRow(rowIndex)
                for (columnIndex in first..last) row.getCell(columnIndex) ?: row.createCell(columnIndex)
            }

            val region = CellRangeAddress(top, bottom, first, last)
            val colour = placed.borders.color.toXssf()
            if (placed.borders.top > 0f) {
                RegionUtil.setBorderTop(placed.borders.top.toBorderStyle(), region, sheet)
                RegionUtil.setTopBorderColor(colour.index.toInt(), region, sheet)
            }
            if (placed.borders.bottom > 0f) {
                RegionUtil.setBorderBottom(placed.borders.bottom.toBorderStyle(), region, sheet)
                RegionUtil.setBottomBorderColor(colour.index.toInt(), region, sheet)
            }
            if (placed.borders.start > 0f) {
                RegionUtil.setBorderLeft(placed.borders.start.toBorderStyle(), region, sheet)
                RegionUtil.setLeftBorderColor(colour.index.toInt(), region, sheet)
            }
            if (placed.borders.end > 0f) {
                RegionUtil.setBorderRight(placed.borders.end.toBorderStyle(), region, sheet)
                RegionUtil.setRightBorderColor(colour.index.toInt(), region, sheet)
            }
        }

        private fun picture(placed: PlacedPicture) {
            val bytes = when (val source = placed.source) {
                is ImageSource.Bytes -> source.value
                is ImageSource.Path -> runCatching { File(source.value).readBytes() }.getOrNull() ?: return
            }
            val index = workbook.addPicture(bytes, pictureTypeOf(bytes))
            val helper = workbook.creationHelper
            val anchor = helper.createClientAnchor()
            anchor.setCol1(firstColumnOf(placed.left))
            anchor.row1 = startRow + placed.row
            anchor.setCol2(firstColumnOf(placed.left) + 1)
            anchor.row2 = startRow + placed.row + placed.rowSpan
            val drawing = sheet.createDrawingPatriarch()
            // resize() honours the image's own proportions; the anchor decides where it starts.
            drawing.createPicture(anchor, index).resize()
        }

        /**
         * Which image format the bytes are, read from the file's own signature.
         *
         * POI has to be told, and a caller handing over an [ImageSource] never said. Guessing from the bytes
         * is more reliable than guessing from a file extension, and PNG and JPEG cover everything these
         * documents carry.
         */
        private fun pictureTypeOf(bytes: ByteArray): Int = when {
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> Workbook.PICTURE_TYPE_JPEG
            else -> Workbook.PICTURE_TYPE_PNG
        }

        // -- heights --------------------------------------------------------------------------------------

        /**
         * How tall this cell needs its rows to be.
         *
         * Estimated, because Excel refuses to auto-fit a row holding a merged cell and nearly every row here
         * holds one. Each explicit line is divided by the width it has to fit into, and the total is shared
         * evenly over the rows the cell spans — a cell spanning three rows only has to make each of them a
         * third as tall.
         */
        private fun recordHeight(
            placed: PlacedCell,
            text: String,
            first: Int,
            last: Int,
            top: Int,
            bottom: Int,
        ) {
            val widthPoints = unscaleX(placed.right - placed.left).coerceAtLeast(1f)
            val style = placed.runs.firstOrNull()?.style
            val lines = if (text.isEmpty()) {
                1
            } else {
                text.split('\n').sumOf { line ->
                    val measured = measurer.widthOf(line, style)
                    ceil(measured / widthPoints).toInt().coerceAtLeast(1)
                }
            }
            val sizePoints = style?.sizePoints ?: theme.defaultSizePoints
            val needed = lines * sizePoints * theme.lineHeightFactor + theme.rowPaddingPoints
            val minimum = placed.minHeightPoints ?: 0f
            val perRow = maxOf(needed, minimum) / (bottom - top + 1)
            for (rowIndex in top..bottom) {
                rowHeights[rowIndex] = maxOf(rowHeights[rowIndex] ?: 0f, perRow)
            }
        }

        private fun applyRowHeights() {
            val default = theme.defaultSizePoints * theme.lineHeightFactor + theme.rowPaddingPoints
            for (offset in 0 until grid.rowCount) {
                val rowIndex = startRow + offset
                val row = sheet.getRow(rowIndex) ?: sheet.createRow(rowIndex)
                val spacer = grid.spacerHeights[offset]
                row.heightInPoints = spacer ?: rowHeights[rowIndex] ?: default
            }
        }

        // -- styling --------------------------------------------------------------------------------------

        private fun styleFor(placed: PlacedCell, wrap: Boolean): XSSFCellStyle {
            val style = placed.runs.firstOrNull()?.style ?: placed.defaultStyle
            val key = StyleKey(
                font = fontKeyOf(style),
                align = placed.align,
                vAlign = placed.vAlign,
                borders = placed.borders,
                background = placed.background,
                wrap = wrap,
            )
            return styles.getOrPut(key) { buildStyle(key) }
        }

        private fun buildStyle(key: StyleKey): XSSFCellStyle {
            val style = workbook.createCellStyle()
            style.setFont(fonts.getOrPut(key.font) { buildFont(key.font) })
            style.alignment = key.align.toHorizontal()
            style.verticalAlignment = key.vAlign.toVertical()
            style.wrapText = key.wrap

            val borders = key.borders
            val colour = borders.color.toXssf()
            if (borders.top > 0f) {
                style.borderTop = borders.top.toBorderStyle()
                style.setTopBorderColor(colour)
            }
            if (borders.bottom > 0f) {
                style.borderBottom = borders.bottom.toBorderStyle()
                style.setBottomBorderColor(colour)
            }
            if (borders.start > 0f) {
                style.borderLeft = borders.start.toBorderStyle()
                style.setLeftBorderColor(colour)
            }
            if (borders.end > 0f) {
                style.borderRight = borders.end.toBorderStyle()
                style.setRightBorderColor(colour)
            }

            key.background?.let {
                style.setFillForegroundColor(it.toXssf())
                style.fillPattern = FillPatternType.SOLID_FOREGROUND
            }
            return style
        }

        private fun buildFont(key: FontKey): XSSFFont {
            val font = workbook.createFont()
            font.fontName = theme.fontName
            font.fontHeightInPoints = key.sizePoints.toInt().toShort()
            font.bold = key.bold
            font.italic = key.italic
            font.setColor(key.color.toXssf())
            return font
        }

        private fun fontKeyOf(style: TextStyle?): FontKey {
            val emphasis = style?.emphasis ?: Emphasis.Normal
            return FontKey(
                sizePoints = style?.sizePoints ?: theme.defaultSizePoints,
                bold = emphasis == Emphasis.Bold || emphasis == Emphasis.BoldItalic,
                italic = emphasis == Emphasis.Italic || emphasis == Emphasis.BoldItalic,
                color = style?.color ?: theme.defaultColor,
            )
        }

        /**
         * The runs as one value, keeping each run's own look.
         *
         * A cell here is regularly a bold item name followed by plain detail lines, and a spreadsheet can hold
         * that: rich text applies a font over a character range. Losing it would flatten every item table into
         * one weight, which is most of what makes those tables readable.
         */
        private fun richTextOf(runs: List<Run>): XSSFRichTextString {
            val text = runs.textOf()
            val rich = XSSFRichTextString(text)
            var cursor = 0
            runs.forEach { run ->
                val piece = run.textOf()
                if (piece.isEmpty()) return@forEach
                rich.applyFont(cursor, cursor + piece.length, fonts.getOrPut(fontKeyOf(run.style)) {
                    buildFont(fontKeyOf(run.style))
                })
                cursor += piece.length
            }
            return rich
        }
    }

    // -----------------------------------------------------------------------------------------------------
    //  Page setup
    // -----------------------------------------------------------------------------------------------------

    /**
     * The printed page, and the running header and footer.
     *
     * A [PageFrame] is blocks, which a sheet header cannot hold — Excel's is three plain strings and a handful
     * of `&`-codes. Flattening it to text is the honest reading: the frame says "this text repeats on every
     * page", and that is precisely what a print header is. [DocToken] becomes `&P` and `&N`, which is the one
     * place a spreadsheet knows its own page count.
     */
    private fun applyPageSetup(sheet: XSSFSheet, frame: PageFrame) {
        sheet.isDisplayGridlines = theme.showGridlines
        sheet.printSetup.landscape = theme.sheet.landscape
        sheet.printSetup.paperSize = PrintSetup.A4_PAPERSIZE
        sheet.setMargin(Sheet.LeftMargin, theme.sheet.marginStart.pointsToInches())
        sheet.setMargin(Sheet.RightMargin, theme.sheet.marginEnd.pointsToInches())
        sheet.setMargin(Sheet.TopMargin, theme.sheet.marginTop.pointsToInches())
        sheet.setMargin(Sheet.BottomMargin, theme.sheet.marginBottom.pointsToInches())
        if (theme.fitToPageWidth) {
            sheet.fitToPage = true
            sheet.printSetup.fitWidth = 1
            // Zero means "as many pages tall as it takes", which is what a long item list wants.
            sheet.printSetup.fitHeight = 0
        }
        frame.header.flattenToPrintText()?.let { sheet.header.center = it }
        frame.footer.flattenToPrintText()?.let { sheet.footer.center = it }
    }

    private fun List<Block>.flattenToPrintText(): String? {
        val text = mapNotNull { it.printText() }.filter { it.isNotBlank() }.joinToString(" ")
        return text.ifBlank { null }
    }

    private fun Block.printText(): String? = when (this) {
        is Block.Paragraph -> runs.joinToString("") { it.printText() }
        is Block.Group -> blocks.mapNotNull { it.printText() }.joinToString(" ")
        is Block.Table -> rows.joinToString(" ") { row ->
            row.cells.joinToString(" ") { cell -> cell.content.mapNotNull { it.printText() }.joinToString(" ") }
        }
        is Block.Bullets -> entries.joinToString(" ") { entry ->
            when (entry) {
                is app.duss.docdsl.ListEntry.Item -> entry.runs.joinToString("") { it.printText() }
                is app.duss.docdsl.ListEntry.Sub -> entry.list.printText().orEmpty()
            }
        }
        is Block.Picture -> null
        is Block.Spacer -> null
        Block.PageBreak -> null
    }

    private fun Run.printText(): String = when (this) {
        is TextRun -> text
        is TokenRun -> when (token) {
            DocToken.CurrentPage -> "&P"
            DocToken.TotalPages -> "&N"
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
//  Small translations
// ---------------------------------------------------------------------------------------------------------

/** A run's own text. A page token has none in a cell — see [ExcelRenderer]. */
private fun Run.textOf(): String = when (this) {
    is TextRun -> text
    is TokenRun -> ""
}

private fun List<Run>.textOf(): String = joinToString("") { it.textOf() }

/**
 * A border width as one of Excel's fixed weights.
 *
 * Excel has no arbitrary border width — it has a list of named ones. The two weights these documents use, a
 * 0.5 hairline grid and a 1.0 band, land on THIN and MEDIUM, which is the same visual difference.
 */
private fun Float.toBorderStyle(): BorderStyle = when {
    this <= 0f -> BorderStyle.NONE
    this < 1f -> BorderStyle.THIN
    this < 2f -> BorderStyle.MEDIUM
    else -> BorderStyle.THICK
}

private fun DocColor.toXssf(): XSSFColor =
    XSSFColor(byteArrayOf(red.toByte(), green.toByte(), blue.toByte()), null)

/** Excel forbids `:\/?*[]` in a tab name and caps it at 31 characters; both silently, at write time. */
private fun safeSheetName(name: String): String {
    val cleaned = name.filterNot { it in ":\\/?*[]" }.trim().ifBlank { "Document" }
    return cleaned.take(31)
}

private data class FontKey(
    val sizePoints: Float,
    val bold: Boolean,
    val italic: Boolean,
    val color: DocColor,
)

private data class StyleKey(
    val font: FontKey,
    val align: app.duss.docdsl.Align,
    val vAlign: app.duss.docdsl.VAlign,
    val borders: Borders,
    val background: DocColor?,
    val wrap: Boolean,
)
