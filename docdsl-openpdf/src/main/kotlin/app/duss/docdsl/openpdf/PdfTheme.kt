package app.duss.docdsl.openpdf

import app.duss.docdsl.Align
import app.duss.docdsl.DocColor
import app.duss.docdsl.Emphasis
import app.duss.docdsl.TextStyle
import org.openpdf.text.Element
import org.openpdf.text.Font
import org.openpdf.text.PageSize
import org.openpdf.text.Rectangle
import java.awt.Color

/**
 * Everything a document leaves unsaid.
 *
 * A [app.duss.docdsl.DocumentSpec] states what it is, not what it looks like: it says "this run is bold", never
 * "this run is 8pt Helvetica bold". The gap between the two lives here, in one place, so a house style is a
 * value you pass rather than a hundred literals spread through the generators.
 */
public data class PdfTheme(
    /** One of openpdf's family names — [Font.HELVETICA], [Font.TIMES_ROMAN], [Font.COURIER]. */
    public val fontFamily: String = Font.HELVETICA,
    /** Used by any run that does not name a size. Most of these documents live at 8pt. */
    public val defaultSizePoints: Float = TextStyle.SMALL,
    public val defaultColor: DocColor = DocColor.Black,
    public val page: PageGeometry = PageGeometry(),
    /** The grid weight a table uses when its style asks for borders. */
    public val defaultBorderWidth: Float = 0.5f,
    /** Extra width allowed on top of measured text, so a column sized to its glyphs does not wrap. */
    public val autoColumnSlackPoints: Float = 6f,
    /** A [app.duss.docdsl.ColumnWidth.Flexible] column never shrinks below this. */
    public val minFlexibleColumnPoints: Float = 90f,
)

/** Paper and margins, in points. */
public data class PageGeometry(
    public val widthPoints: Float = PageSize.A4.width,
    public val heightPoints: Float = PageSize.A4.height,
    public val marginStart: Float = 40f,
    public val marginEnd: Float = 40f,
    public val marginTop: Float = 40f,
    public val marginBottom: Float = 60f,
) {
    /** The width a full-width block actually has to work with. */
    public val contentWidthPoints: Float get() = widthPoints - marginStart - marginEnd

    internal fun rectangle(): Rectangle = Rectangle(widthPoints, heightPoints)
}

// ---------------------------------------------------------------------------------------------------------
//  Translation into openpdf's vocabulary
// ---------------------------------------------------------------------------------------------------------

internal fun DocColor.toAwt(): Color = Color(red, green, blue)

internal fun Align.toElementAlignment(): Int = when (this) {
    Align.Start -> Element.ALIGN_LEFT
    Align.Center -> Element.ALIGN_CENTER
    Align.End -> Element.ALIGN_RIGHT
    Align.Justify -> Element.ALIGN_JUSTIFIED
}

internal fun Emphasis.toFontStyle(): Int = when (this) {
    Emphasis.Normal -> Font.NORMAL
    Emphasis.Bold -> Font.BOLD
    Emphasis.Italic -> Font.ITALIC
    Emphasis.BoldItalic -> Font.BOLDITALIC
}

/**
 * The font a run is drawn in: whatever it asked for, and the theme's answer for everything it did not.
 *
 * This is the only place a style becomes a font, which is what makes measurement and drawing agree — a column
 * measured in one font and drawn in another is how text ends up wrapping when the arithmetic said it fits.
 */
internal fun PdfTheme.fontFor(style: TextStyle?): Font = Font(
    fontFamily,
    style?.sizePoints ?: defaultSizePoints,
    (style?.emphasis ?: Emphasis.Normal).toFontStyle(),
    (style?.color ?: defaultColor).toAwt(),
)

/**
 * How wide this text really is, in points, in the font it will be drawn in.
 *
 * The measurement the old table lacked. It estimated width as `text.length * 1.5`, which ignores the font
 * entirely: at 8pt Helvetica a digit is about 4.45pt and a space about 2.2pt, so the same character count can
 * differ twofold in real width — and a long description was therefore able to crowd every price into a few
 * percent of the page.
 *
 * Multi-line text is measured by its widest line, since that is what has to fit.
 */
internal fun Font.widthOf(text: String): Float {
    if (text.isEmpty()) return 0f
    val base = runCatching { getCalculatedBaseFont(false) }.getOrNull()
        ?: return text.length * calculatedSize * 0.5f
    val size = calculatedSize.takeIf { it > 0f } ?: TextStyle.SMALL
    return text.split('\n').maxOf { line -> base.getWidthPoint(line, size) }
}
