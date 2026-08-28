package app.duss.docdsl.poi

import app.duss.docdsl.Align
import app.duss.docdsl.DocColor
import app.duss.docdsl.Emphasis
import app.duss.docdsl.TextMeasurer
import app.duss.docdsl.TextStyle
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import app.duss.docdsl.VAlign
import kotlin.math.roundToInt

/**
 * Everything a document leaves unsaid, for the spreadsheet renderer.
 *
 * The sibling of `PdfTheme`, and deliberately shaped like it: a
 * [app.duss.docdsl.DocumentSpec] says "this run is bold", never "this run is 8pt Helvetica bold", and the gap
 * between the two lives in one place so a house style is a value you pass rather than a hundred literals.
 *
 * The defaults are chosen so a workbook looks like the PDF of the same document rather than like a
 * spreadsheet: the same typeface, the same 8pt body, gridlines off so the document's own borders are the only
 * grid, and a page setup that prints A4 at the same width.
 */
public data class ExcelTheme(
    /**
     * The typeface. Helvetica is what the PDFs use; Excel has no Helvetica of its own and substitutes Arial,
     * whose metrics are the same, so the two render at the same width.
     */
    public val fontName: String = "Helvetica",
    /** Used by any run that does not name a size. Most of these documents live at 8pt. */
    public val defaultSizePoints: Float = TextStyle.SMALL,
    public val defaultColor: DocColor = DocColor.Black,
    public val sheet: SheetGeometry = SheetGeometry(),
    /** Extra width allowed on top of measured text, so a column sized to its glyphs does not wrap. */
    public val autoColumnSlackPoints: Float = 6f,
    /** A [app.duss.docdsl.ColumnWidth.Flexible] column never shrinks below this. */
    public val minFlexibleColumnPoints: Float = 90f,
    /**
     * How tall one line of text is, as a multiple of its point size.
     *
     * Excel will not auto-fit a row that contains a merged cell, and almost every row here does, so row
     * heights are estimated rather than measured. 1.35 leaves a little air without opening the document out.
     */
    public val lineHeightFactor: Float = 1.35f,
    /** Added to every estimated row height, standing in for the cell padding a PDF draws. */
    public val rowPaddingPoints: Float = 3f,
    /**
     * Off by default, and the single biggest reason a workbook reads as a document.
     *
     * With gridlines on, every cell of the sheet is boxed and the table borders the document actually asked
     * for disappear into them.
     */
    public val showGridlines: Boolean = false,
    /** Scale the print-out to one page wide, so a printed sheet matches the PDF's line breaks. */
    public val fitToPageWidth: Boolean = true,
)

/**
 * The width a document has to work with, in points, and the paper it prints on.
 *
 * The counterpart of `PageGeometry`, and stated in the same units for the same reason: a `Weight(45)` column
 * has to come out 45% of the same width in both media, or the information grid at the top of a proforma
 * invoice is a different shape in the workbook than on the page.
 */
public data class SheetGeometry(
    /** A4 portrait less 40pt margins on each side — the same content width the PDFs are laid out against. */
    public val contentWidthPoints: Float = 515f,
    public val landscape: Boolean = false,
    /**
     * Print margins, in points, so a printed sheet starts where the PDF's text starts.
     *
     * Excel's own defaults are 0.7in at the sides and 0.75in top and bottom, which is half an inch wider than
     * these documents use — enough that a table sized to the page here would not fit there.
     */
    public val marginStart: Float = 40f,
    public val marginEnd: Float = 40f,
    public val marginTop: Float = 40f,
    public val marginBottom: Float = 60f,
)

/** Excel states margins in inches. */
internal fun Float.pointsToInches(): Double = this / 72.0

// ---------------------------------------------------------------------------------------------------------
//  Translation into POI's vocabulary
// ---------------------------------------------------------------------------------------------------------

internal fun Align.toHorizontal(): HorizontalAlignment = when (this) {
    Align.Start -> HorizontalAlignment.LEFT
    Align.Center -> HorizontalAlignment.CENTER
    Align.End -> HorizontalAlignment.RIGHT
    Align.Justify -> HorizontalAlignment.JUSTIFY
}

internal fun VAlign.toVertical(): VerticalAlignment = when (this) {
    VAlign.Top -> VerticalAlignment.TOP
    VAlign.Middle -> VerticalAlignment.CENTER
    VAlign.Bottom -> VerticalAlignment.BOTTOM
}

/**
 * A width in points as Excel's own column unit: 1/256 of the width of a digit in the default font.
 *
 * Excel measures columns in characters, not points, and the conversion is its own: a column of *n* characters
 * is `n * 7 + 5` pixels wide at 96 dpi. Inverting that is what lets a column the document specified in points
 * arrive at approximately the width the PDF gave it. "Approximately" is the honest word — the pixel figure is
 * quantised and the character width depends on the workbook's default font — which is why the promise here is
 * that a spreadsheet *resembles* the page rather than reproduces it.
 */
internal fun Float.toColumnWidthUnits(): Int {
    val pixels = this * 96f / 72f
    val characters = ((pixels - 5f) / 7f).coerceAtLeast(0.05f)
    // 255 characters is Excel's own ceiling; asking for more throws.
    return (characters * 256f).roundToInt().coerceIn(1, 255 * 256)
}

/**
 * How wide text is, in points, without a font engine.
 *
 * These are Helvetica's own advance widths, in thousandths of an em, which is what the PDF renderer measures
 * against for the same text — so the two agree on which column is wide and which is narrow even though only
 * one of them has real font metrics. Bold is not a separate table: Helvetica-Bold is a few percent wider
 * across the board and one factor captures that closely enough for a column width.
 *
 * A character outside the table falls back to the width of a digit. That is wrong for CJK, which is roughly
 * twice as wide, and right for the accented Latin these documents actually contain.
 */
internal class HelveticaMeasurer(private val defaultSizePoints: Float) : TextMeasurer {

    override fun widthOf(text: String, style: TextStyle?): Float {
        if (text.isEmpty()) return 0f
        val size = style?.sizePoints ?: defaultSizePoints
        val emphasis = style?.emphasis ?: Emphasis.Normal
        val boldFactor = if (emphasis == Emphasis.Bold || emphasis == Emphasis.BoldItalic) BOLD_FACTOR else 1f
        // Multi-line text is as wide as its widest line, since that is what has to fit.
        return text.split('\n').maxOf { line ->
            line.sumOf { advanceOf(it).toDouble() }.toFloat() / 1000f * size * boldFactor
        }
    }

    private fun advanceOf(char: Char): Int {
        val code = char.code
        return if (code in ADVANCE_FIRST..ADVANCE_LAST) ADVANCES[code - ADVANCE_FIRST] else DIGIT_ADVANCE
    }

    private companion object {
        const val BOLD_FACTOR = 1.06f
        const val DIGIT_ADVANCE = 556
        const val ADVANCE_FIRST = 32
        const val ADVANCE_LAST = 126

        /** Helvetica advance widths for U+0020..U+007E, in 1/1000 em, straight from the AFM. */
        val ADVANCES = intArrayOf(
            278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
            1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
            333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
            556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
        )
    }
}
