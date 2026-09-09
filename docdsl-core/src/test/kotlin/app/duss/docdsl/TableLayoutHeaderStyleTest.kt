package app.duss.docdsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A heading has to be **measured** in the style it will be **drawn** in.
 *
 * The rule these guard is the one that makes styled headings safe: a column sized against the default size
 * and then drawn a size larger wraps inside a column that the arithmetic said fitted, which is the failure
 * mode the library already carries a warning about for cell text.
 */
class TableLayoutHeaderStyleTest {

    /** Width proportional to size, so a heading two points larger measures two points wider per character. */
    private val measurer = TextMeasurer { text, style ->
        text.length * (style?.sizePoints ?: 10f)
    }

    private fun widthsFor(
        column: Column,
        tableHeaderStyle: TextStyle? = null,
        tableWidth: Float = 1_000f,
    ): FloatArray = TableLayout.columnWidths(
        visible = listOf(VisibleColumn(column, 0), VisibleColumn(Column(width = ColumnWidth.Flexible), 1)),
        rows = emptyList(),
        tableWidth = tableWidth,
        measurer = measurer,
        slackPoints = 0f,
        minFlexiblePoints = 0f,
        tableHeaderStyle = tableHeaderStyle,
    )

    @Test
    fun `an auto column is measured at the default size when no header style is given`() {
        val widths = widthsFor(Column(title = "Qty", width = ColumnWidth.Auto))

        // 3 characters at the measurer's 10pt default.
        assertEquals(30f, widths[0])
    }

    @Test
    fun `a table header style widens the column it is measured for`() {
        val plain = widthsFor(Column(title = "Qty", width = ColumnWidth.Auto))
        val larger = widthsFor(
            Column(title = "Qty", width = ColumnWidth.Auto),
            tableHeaderStyle = TextStyle(sizePoints = TextStyle.LARGE),
        )

        assertEquals(3 * TextStyle.LARGE, larger[0])
        assertTrue(larger[0] > plain[0], "a larger heading has to be given more width, not drawn into less")
    }

    @Test
    fun `a column's own header style wins over the table's`() {
        val widths = widthsFor(
            Column(
                title = "Qty",
                width = ColumnWidth.Auto,
                headerStyle = TextStyle(sizePoints = TextStyle.SMALL),
            ),
            tableHeaderStyle = TextStyle(sizePoints = TextStyle.TITLE),
        )

        assertEquals(3 * TextStyle.SMALL, widths[0])
    }

    @Test
    fun `header style resolution prefers the column, then the table, then nothing`() {
        val columnStyle = TextStyle(sizePoints = TextStyle.SMALL)
        val tableStyle = TextStyle(sizePoints = TextStyle.LARGE)
        val table = TableStyle(headerStyle = tableStyle)

        assertSame(columnStyle, table.headerStyleFor(Column(headerStyle = columnStyle)))
        assertSame(tableStyle, table.headerStyleFor(Column()))
        assertEquals(null, TableStyle().headerStyleFor(Column()))
    }

    @Test
    fun `header padding falls back to cell padding, which is what it used to be fixed at`() {
        val cell = Padding(top = 2f, bottom = 2f, start = 2f, end = 2f)
        val header = Padding(top = 6f, bottom = 6f, start = 4f, end = 4f)
        val column = Padding(top = 9f, bottom = 9f, start = 9f, end = 9f)

        assertEquals(cell, TableStyle(cellPadding = cell).headerPaddingFor(Column()))
        assertEquals(header, TableStyle(cellPadding = cell, headerPadding = header).headerPaddingFor(Column()))
        assertEquals(
            column,
            TableStyle(cellPadding = cell, headerPadding = header)
                .headerPaddingFor(Column(headerPadding = column)),
        )
    }

    @Test
    fun `rows may be split across pages unless a table says otherwise`() {
        assertEquals(true, TableStyle().allowRowSplit, "the default has to stay what every document already does")
        assertEquals(false, TableStyle(allowRowSplit = false).allowRowSplit)
    }
}
