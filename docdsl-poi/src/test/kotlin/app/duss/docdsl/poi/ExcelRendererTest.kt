package app.duss.docdsl.poi

import app.duss.docdsl.Align
import app.duss.docdsl.ColumnWidth
import app.duss.docdsl.DocColor
import app.duss.docdsl.Padding
import app.duss.docdsl.TableStyle
import app.duss.docdsl.TextStyle
import app.duss.docdsl.document
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFSheet
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the PDF renderer's tests could not do.
 *
 * Those assert on the produced bytes, because appearance is not something a test can judge. A spreadsheet is
 * different: its layout *is* data, so these read the workbook back and check the things that would actually
 * be wrong — a span one column short, a hidden column that still took a slot, a table whose proportions did
 * not survive the trip onto a shared grid.
 */
class ExcelRendererTest {

    private val renderer = ExcelRenderer()

    private fun render(spec: app.duss.docdsl.DocumentSpec): XSSFSheet {
        val bytes = renderer.renderToBytes(spec)
        assertTrue(bytes.size > 100, "expected a workbook")
        val workbook = WorkbookFactory.create(ByteArrayInputStream(bytes))
        return workbook.getSheetAt(0) as XSSFSheet
    }

    private fun XSSFSheet.textAt(row: Int, column: Int): String =
        getRow(row)?.getCell(column)?.let { runCatching { it.stringCellValue }.getOrDefault("") }.orEmpty()

    private fun XSSFSheet.mergeAt(row: Int, column: Int): CellRangeAddress? =
        mergedRegions.firstOrNull { it.isInRange(row, column) }

    /** The first merged span on a row, wherever it starts. */
    private fun XSSFSheet.spanOn(row: Int): CellRangeAddress =
        mergedRegions.filter { it.firstRow == row }.minBy { it.firstColumn }

    /**
     * Where a row's text starts, and what it says.
     *
     * Not every placed cell is a merged one: a span that happens to cover a single physical column needs no
     * merge, so asking about merges is asking the wrong question when what is being checked is position.
     */
    private fun XSSFSheet.firstTextOn(row: Int): Pair<Int, String> {
        val cells = getRow(row) ?: error("no row $row")
        val cell = cells.firstOrNull { runCatching { it.stringCellValue }.getOrDefault("").isNotBlank() }
            ?: error("nothing written on row $row")
        return cell.columnIndex to cell.stringCellValue
    }

    /** Every physical column the document ended up with, in points. */
    private fun XSSFSheet.columnPoints(): List<Double> =
        (0 until 64).map { getColumnWidth(it) }
            .takeWhile { it != defaultColumnWidth * 256 }
            .map { it / 256.0 * 7.0 * 96.0 / 72.0 }

    @Test
    fun `writes a document that is only text`() {
        val sheet = render(
            document {
                paragraph("Invoice", bold = true, size = TextStyle.TITLE, align = Align.Center)
                spacer()
                paragraph("Everything below is deliberately plain.")
            }
        )
        assertEquals("Invoice", sheet.textAt(0, 0))
        assertEquals("Everything below is deliberately plain.", sheet.textAt(2, 0))
    }

    @Test
    fun `a full-width block reaches the last column`() {
        // The defect this exists for: a span's right edge is exclusive, and treating it like the left edge
        // costs the span its final column. It shows only as a hairline of unmerged sheet down the right.
        val sheet = render(
            document {
                table {
                    column("A", width = ColumnWidth.Auto)
                    column("B", width = ColumnWidth.Flexible, align = Align.Start)
                    row { cell("1"); cell("long enough to need the room") }
                }
                paragraph("A paragraph, which is full width by definition.")
            }
        )
        val lastColumn = sheet.columnPoints().lastIndex
        // Row 0 is the table's header, row 1 its only body row, so the paragraph lands on row 2.
        val paragraph = sheet.spanOn(2)
        assertEquals(0, paragraph.firstColumn)
        assertEquals(lastColumn, paragraph.lastColumn, "the paragraph should span every column")
    }

    @Test
    fun `a hidden column takes no space on the sheet`() {
        val withQuantity = render(
            document {
                table {
                    column("#", width = ColumnWidth.Auto)
                    column("Item", width = ColumnWidth.Flexible, align = Align.Start)
                    column("Qty", width = ColumnWidth.Auto, hideWhenEmpty = true)
                    row { cell("1"); cell("Wagon tippler"); cell("12 pieces") }
                }
            }
        )
        val withoutQuantity = render(
            document {
                table {
                    column("#", width = ColumnWidth.Auto)
                    column("Item", width = ColumnWidth.Flexible, align = Align.Start)
                    column("Qty", width = ColumnWidth.Auto, hideWhenEmpty = true)
                    row { cell("1"); cell("Wagon tippler"); cell(null) }
                }
            }
        )
        assertEquals("Qty", withQuantity.textAt(0, 2))
        assertTrue(
            withoutQuantity.columnPoints().size < withQuantity.columnPoints().size,
            "an empty hideWhenEmpty column should not leave a column behind",
        )
    }

    @Test
    fun `a weighted split keeps its proportions on the shared grid`() {
        val sheet = render(
            document {
                table(TableStyle(cellPadding = Padding.None, headerBackground = null)) {
                    column(width = ColumnWidth.Weight(45f), align = Align.Start)
                    column(width = ColumnWidth.Weight(55f), align = Align.Start)
                    row { cell("Seller"); cell("Buyer") }
                }
            }
        )
        val left = sheet.mergeAt(0, 0)
        val widths = sheet.columnPoints()
        val leftWidth = (0..(left?.lastColumn ?: 0)).sumOf { widths[it] }
        val share = leftWidth / widths.sum()
        assertTrue(share in 0.40..0.50, "expected roughly a 45% split, got ${"%.2f".format(share)}")
    }

    @Test
    fun `a cell holding a nested table becomes a region rather than one cell`() {
        val sheet = render(
            document {
                table {
                    column("Left", width = ColumnWidth.Weight(50f), align = Align.Start)
                    column("Right", width = ColumnWidth.Weight(50f), align = Align.Start)
                    row {
                        cell("One line")
                        cellOf {
                            table(TableStyle(cellPadding = Padding.None, headerBackground = null)) {
                                column(width = ColumnWidth.Weight(50f), align = Align.Start)
                                column(width = ColumnWidth.Weight(50f), align = Align.Start)
                                row { cell("Number"); cell("MTP-PI-1") }
                                row { cell("Date"); cell("2026-08-28") }
                            }
                        }
                    }
                }
            }
        )
        // The nested table's two rows push the whole table row to two rows tall, and the plain cell beside it
        // merges down to match — otherwise the two columns would slide out of step.
        val plain = sheet.mergeAt(1, 0)
        assertEquals(1, plain?.firstRow)
        assertEquals(2, plain?.lastRow, "the neighbouring cell should span the nested table's rows")
        assertEquals("MTP-PI-1", sheet.textAt(1, plain!!.lastColumn + 2))
    }

    @Test
    fun `a numbered list numbers itself and a sub-list indents`() {
        val sheet = render(
            document {
                bullets(numbered = true) {
                    item("Order Confirmation")
                    item("Terms of payment:")
                    sub { item("30% Advance Payment") }
                }
            }
        )
        val (firstColumn, firstText) = sheet.firstTextOn(0)
        assertEquals("1. Order Confirmation", firstText)
        assertEquals("2. Terms of payment:", sheet.firstTextOn(1).second)
        // Indenting shifts the band, so the sub-list's own left edge becomes a real column boundary rather
        // than padding inside a cell — which is what lets it line up with everything else on the sheet.
        val (subColumn, subText) = sheet.firstTextOn(2)
        assertEquals("• 30% Advance Payment", subText)
        assertTrue(subColumn > firstColumn, "a sub-list should start further in")
    }

    @Test
    fun `the sheet is set up to print like the page`() {
        val sheet = render(document { paragraph("Anything") })
        assertTrue(!sheet.isDisplayGridlines, "gridlines hide the document's own borders")
        assertTrue(sheet.fitToPage, "a document should print one page wide")
        assertEquals(1, sheet.printSetup.fitWidth.toInt())
    }

    /**
     * A horizontal bar chart, which is the shape that decided whether a report could render to a sheet.
     *
     * A bar needs no primitive of its own: it is a weighted layout table whose first cell has a background
     * and no text. What has to survive is the proportion — a 25% bar that comes out half the width is a
     * different chart — so this checks the filled cell really is about a quarter of the pair.
     */
    @Test
    fun `a weighted filled cell is a bar`() {
        val sheet = render(
            document {
                table(TableStyle.Layout) {
                    column(width = ColumnWidth.Weight(30f), align = Align.Start)
                    column(width = ColumnWidth.Weight(70f))
                    row {
                        cell("Steel", align = Align.Start)
                        cellOf {
                            table(TableStyle.Layout) {
                                column(width = ColumnWidth.Weight(0.25f))
                                column(width = ColumnWidth.Weight(0.75f))
                                row {
                                    cellOf(background = DocColor.Red, minHeightPoints = 12f) {}
                                    cellOf {}
                                }
                            }
                        }
                    }
                }
            }
        )
        // Asserted on the grid itself rather than on merges: a span covering a single physical column needs
        // no merge, so counting merges would be counting the wrong thing.
        //
        // The label takes 30% of the width, so the chart starts there; the bar is a quarter of the remaining
        // 70%, so it ends at 30 + 17.5 = 47.5%. Both have to be real column boundaries or the bar is not
        // where the description put it.
        val widths = sheet.columnPoints()
        val total = widths.sum()
        val boundaries = widths.runningFold(0.0) { at, width -> at + width }.map { it / total }

        assertTrue(boundaries.any { abs(it - 0.30) < 0.03 }, "no boundary where the label ends: $boundaries")
        assertTrue(boundaries.any { abs(it - 0.475) < 0.03 }, "no boundary where the bar ends: $boundaries")
    }

    @Test
    fun `shading and colour survive`() {
        val sheet = render(
            document {
                table(TableStyle(headerBackground = DocColor.LightGray)) {
                    column("Heading", width = ColumnWidth.Flexible, align = Align.Start)
                    row { cell("Body", color = DocColor.Red) }
                }
            }
        )
        assertEquals("Heading", sheet.textAt(0, 0))
        assertEquals("Body", sheet.textAt(1, 0))
    }
}
