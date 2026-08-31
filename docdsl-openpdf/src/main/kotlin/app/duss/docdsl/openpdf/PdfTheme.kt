package app.duss.docdsl.openpdf

import app.duss.docdsl.Align
import app.duss.docdsl.DocColor
import app.duss.docdsl.Emphasis
import app.duss.docdsl.TextStyle
import org.openpdf.text.Element
import org.openpdf.text.Font
import org.openpdf.text.PageSize
import org.openpdf.text.Rectangle
import org.openpdf.text.pdf.BaseFont
import java.awt.Color

/**
 * Everything a document leaves unsaid.
 *
 * A [app.duss.docdsl.DocumentSpec] states what it is, not what it looks like: it says "this run is bold", never
 * "this run is 8pt Helvetica bold". The gap between the two lives here, in one place, so a house style is a
 * value you pass rather than a hundred literals spread through the generators.
 */
public data class PdfTheme(
    /**
     * The typeface everything is drawn in. The default needs no font file, but it can only draw Latin-1 —
     * see [PdfFontFamily.embedded] for text in any other script.
     */
    public val fontFamily: PdfFontFamily = PdfFontFamily.Helvetica,
    /** Used by any run that does not name a size. Most of these documents live at 8pt. */
    public val defaultSizePoints: Float = TextStyle.SMALL,
    public val defaultColor: DocColor = DocColor.Black,
    public val page: PageGeometry = PageGeometry(),
    /** The grid weight a table uses when its style asks for borders. */
    public val defaultBorderWidth: Float = 0.5f,
    /**
     * Extra width allowed on top of measured text, so a column sized to its glyphs does not wrap.
     *
     * It is also the only breathing room a narrow column gets. A row-number column measures the width of "9"
     * and nothing else, so at 6pt of slack the digits sat against the grid lines; 12 gives them a margin
     * without widening a column that has real content in it, because the slack is a constant rather than a
     * proportion.
     */
    public val autoColumnSlackPoints: Float = 12f,
    /**
     * A [app.duss.docdsl.ColumnWidth.Flexible] column's *preferred* minimum.
     *
     * Preferred, not absolute: when the table cannot afford it, a flexible column gives way down to
     * [hardMinColumnPoints] rather than letting a measured [app.duss.docdsl.ColumnWidth.Auto] column be
     * squeezed below what its content needs. Prose can wrap; a price cannot.
     */
    public val minFlexibleColumnPoints: Float = 90f,
    /** No column is ever narrower than this, whatever the arithmetic says. */
    public val hardMinColumnPoints: Float = 24f,
    /**
     * Line height, as a multiple of the font size.
     *
     * OpenPDF's own default is 1.5, which is generous for a form and turns a long item list into pages of
     * white. 1.15 is the spacing these documents were drawn at before they were described.
     */
    public val lineSpacing: Float = 1.15f,
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
internal fun PdfTheme.fontFor(style: TextStyle?): Font {
    val emphasis = style?.emphasis ?: Emphasis.Normal
    val size = style?.sizePoints ?: defaultSizePoints
    val color = (style?.color ?: defaultColor).toAwt()

    // A standard family has no face of its own; openpdf resolves the Int and the style together.
    val face = fontFamily.faceFor(emphasis)
        ?: return Font(fontFamily.standardFamily ?: Font.HELVETICA, size, emphasis.toFontStyle(), color)

    // Synthesise only the emphasis the chosen face does not already carry. A real bold face asked for bold
    // needs nothing added — stroking a synthetic bold over a genuine one prints heavier than the designer
    // drew. But a family holding regular and bold, asked for bold-italic, gets the bold face and still needs
    // the italic skew, and asking for BOLDITALIC there would double the weight it already has.
    val synthetic = syntheticStyle(requested = emphasis, supplied = fontFamily.suppliedEmphasisFor(emphasis))
    return Font(face, size, synthetic, color)
}

private val Emphasis.isBold: Boolean get() = this == Emphasis.Bold || this == Emphasis.BoldItalic
private val Emphasis.isItalic: Boolean get() = this == Emphasis.Italic || this == Emphasis.BoldItalic

/** What openpdf still has to fake, given what the face already provides. */
private fun syntheticStyle(requested: Emphasis, supplied: Emphasis): Int {
    val needsBold = requested.isBold && !supplied.isBold
    val needsItalic = requested.isItalic && !supplied.isItalic
    return when {
        needsBold && needsItalic -> Font.BOLDITALIC
        needsBold -> Font.BOLD
        needsItalic -> Font.ITALIC
        else -> Font.NORMAL
    }
}

/**
 * The font openpdf quietly substitutes for a standard family when it meets a character it cannot draw.
 *
 * Loaded once, lazily, and only if something actually needs measuring in it. It ships inside the openpdf jar,
 * so this resolves through the classloader rather than the filesystem.
 */
private val fallbackBaseFont: BaseFont? by lazy {
    runCatching {
        BaseFont.createFont(OPENPDF_FALLBACK_FONT, BaseFont.IDENTITY_H, BaseFont.EMBEDDED)
    }.getOrNull()
}

private const val OPENPDF_FALLBACK_FONT: String = "font-fallback/LiberationSans-Regular.ttf"

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
    val size = calculatedSize.takeIf { it > 0f } ?: TextStyle.SMALL
    return text.split('\n').maxOf { line -> widthOfLine(line, size) }
}

/**
 * One line, measured in the font openpdf will really draw it in.
 *
 * The subtlety that makes this more than a one-liner: a standard family has no `BaseFont` of its own, and when
 * openpdf meets a character above U+00FF in such a chunk it silently swaps in a bundled Liberation Sans rather
 * than failing. Measuring that line in Helvetica returns **zero** — Helvetica's metrics simply have no entry
 * for the glyph — so an [app.duss.docdsl.ColumnWidth.Auto] column holding Cyrillic or Greek would be sized to
 * nothing but the slack and then drawn at full width, spilling over its neighbours. Mirroring openpdf's own
 * substitution rule here is what keeps the measured font and the drawn font the same font, which is the entire
 * premise of measuring at all.
 *
 * A zero from any other cause — a glyph missing from an embedded subset, a font that will not report metrics —
 * falls back to a rough estimate. Being approximately right beats confidently collapsing a column.
 */
private fun Font.widthOfLine(line: String, size: Float): Float {
    if (line.isEmpty()) return 0f
    val substituted = baseFont == null && line.any { it.code > 0xFF }
    val base = if (substituted) fallbackBaseFont else runCatching { getCalculatedBaseFont(false) }.getOrNull()
    val measured = base?.let { runCatching { it.getWidthPoint(line, size) }.getOrNull() } ?: 0f
    return if (measured > 0f) measured else line.length * size * 0.5f
}
