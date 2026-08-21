package app.duss.docdsl.openpdf

import app.duss.docdsl.Align
import app.duss.docdsl.ColumnWidth
import app.duss.docdsl.TextStyle
import app.duss.docdsl.document
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke tests: does a document of each shape actually render.
 *
 * They assert on the produced bytes rather than on appearance, because appearance is not something a test can
 * judge — what these catch is the class of failure that matters most here, where openpdf throws during layout
 * (the "Wrong number of columns" family) and takes a whole section of the document with it.
 */
class OpenPdfRendererTest {

    private val renderer = OpenPdfRenderer()

    private fun ByteArray.isPdf(): Boolean = size > 100 && decodeToString(0, 5) == "%PDF-"

    @Test
    fun `renders a document that is only text`() {
        val bytes = renderer.renderToBytes(
            document {
                paragraph("Invoice", bold = true, size = TextStyle.TITLE, align = Align.Center)
                spacer()
                paragraph("Everything below is deliberately plain.")
            }
        )
        assertTrue(bytes.isPdf(), "expected a PDF")
    }

    @Test
    fun `renders a headed table whose numeric columns are content-sized`() {
        val bytes = renderer.renderToBytes(
            document {
                table {
                    column("Item no.", width = ColumnWidth.Auto)
                    column("Description of Goods", width = ColumnWidth.Flexible, align = Align.Start)
                    column("Qty", width = ColumnWidth.Auto)
                    column("Total (EUR)", width = ColumnWidth.Auto, align = Align.End)
                    row {
                        cell("1")
                        cell {
                            text("Wagon tippler", bold = true)
                            text("\nType: Mechanical, with a description long enough to force the flexible column to wrap while the numbers beside it must not.")
                        }
                        cell("12 pieces")
                        cell("1.234.567,89", align = Align.End)
                    }
                }
            }
        )
        assertTrue(bytes.isPdf(), "expected a PDF")
    }

    @Test
    fun `hides a column when no row has anything in it`() {
        val spec = document {
            table {
                column("Item", width = ColumnWidth.Flexible, align = Align.Start)
                column("Qty", width = ColumnWidth.Auto, hideWhenEmpty = true)
                row {
                    cell("Pump")
                    cell(null)
                }
            }
        }
        // The table is declared with two columns and rendered with one; the point is that it renders at all —
        // dropping a column used to mean the titles, the widths and the cells going out of step and openpdf
        // rejecting the whole table.
        assertTrue(renderer.renderToBytes(spec).isPdf(), "expected a PDF")
    }

    @Test
    fun `renders a table nested inside a cell`() {
        val bytes = renderer.renderToBytes(
            document {
                table {
                    column("Item no.", width = ColumnWidth.Auto)
                    column("Description of Goods", width = ColumnWidth.Flexible, align = Align.Start)
                    row {
                        cell("1")
                        cellOf {
                            paragraph("Assembly", bold = true)
                            table {
                                column("#", width = ColumnWidth.Auto)
                                column("Sub-item", width = ColumnWidth.Flexible, align = Align.Start)
                                row { cell("1"); cell("Impeller") }
                                row { cell("2"); cell("Shaft") }
                            }
                        }
                    }
                }
            }
        )
        assertTrue(bytes.isPdf(), "expected a PDF")
    }

    @Test
    fun `renders a footer carrying the page tokens`() {
        val bytes = renderer.renderToBytes(
            document {
                pageFooter {
                    paragraph(align = Align.End) {
                        text("Page ")
                        currentPage()
                        text(" of ")
                        totalPages()
                    }
                }
                // Enough content to run past one page, so the total is genuinely larger than the current.
                repeat(120) { index -> paragraph("Line $index of a document that has to spill over a page.") }
            }
        )
        assertTrue(bytes.isPdf(), "expected a PDF")
    }

    @Test
    fun `renders totals panes bullets and a page break`() {
        val bytes = renderer.renderToBytes(
            document {
                panes(60f, 40f) {
                    pane { paragraph("Notes about the payment.") }
                    pane {
                        totals(align = Align.End) {
                            line("Total Value:", "1.234,56 €")
                            line("Payable:", null) // absent, and must simply not appear
                            line("Balance:", "0,00 €", emphasised = true)
                        }
                    }
                }
                bullets(numbered = true) {
                    item("First requirement")
                    item("Second requirement")
                    sub { item("A nested detail") }
                }
                pageBreak()
                banner("Detailed Item List")
            }
        )
        assertTrue(bytes.isPdf(), "expected a PDF")
    }
}
