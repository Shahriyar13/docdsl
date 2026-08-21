package app.duss.docdsl.openpdf

import app.duss.docdsl.Emphasis
import org.openpdf.text.Font
import org.openpdf.text.pdf.BaseFont

/**
 * The typeface a document is drawn in.
 *
 * Two quite different things wear this one type, because a caller should not have to care which it is holding.
 * [Helvetica] and its siblings are the standard PDF families: every reader already has them, so nothing is
 * shipped and nothing is embedded, and the file stays small. The [embedded] factories are the other kind — a
 * real font file, carried inside the PDF.
 *
 * **Why embedding is not merely a nicety.** The standard families cover Latin-1 and nothing else. openpdf does
 * not fail loudly on the rest: meeting any character above U+00FF in such a chunk, it quietly substitutes a
 * Liberation Sans bundled inside its own jar. That rescues Latin, Greek, Cyrillic and Hebrew — and silently
 * drops the bold or italic you asked for, because the substitution path skips openpdf's emphasis simulation.
 * For Persian, Arabic and CJK it rescues nothing: Liberation Sans has no glyphs for them, so the text is
 * simply absent from the page. That is the sort of defect that reaches a customer before it reaches a
 * developer. Any of those scripts needs an embedded font and the `Identity-H` encoding, which is what
 * [embedded] sets up.
 *
 * openpdf names a standard family with a bare `Int`, which is tolerable inside a renderer and poor in a
 * published API — an `Int` parameter accepts anything and tells a reader nothing. Nothing here exposes it.
 */
public class PdfFontFamily private constructor(
    internal val standardFamily: Int?,
    private val faces: Map<Emphasis, BaseFont>,
    private val label: String,
) {

    /**
     * The face to draw this emphasis with, or `null` when this is a standard family.
     *
     * A family given only a regular face still answers for bold and italic: it falls back to the regular one
     * and lets the theme ask openpdf to synthesise the emphasis, which is far better than dropping the text.
     * Bold-italic degrades a step at a time rather than all the way — given regular and bold but no
     * bold-italic, using the bold face and skewing it keeps the real weight, where falling straight back to
     * regular would throw away a face the caller took the trouble to supply.
     */
    internal fun faceFor(emphasis: Emphasis): BaseFont? = faces[emphasis]
        ?: faces[nearestTo(emphasis)]
        ?: faces[Emphasis.Normal]

    /**
     * The emphasis the face chosen for [emphasis] already carries, so the theme knows what is left to fake.
     *
     * Returning the *supplied* emphasis rather than a yes/no answer is what lets bold-italic-on-a-bold-face
     * synthesise the italic alone instead of both.
     */
    internal fun suppliedEmphasisFor(emphasis: Emphasis): Emphasis = when {
        faces.containsKey(emphasis) -> emphasis
        faces.containsKey(nearestTo(emphasis)) -> nearestTo(emphasis)
        else -> Emphasis.Normal
    }

    /** The one step down from [emphasis]: bold-italic prefers bold, then italic. Everything else has none. */
    private fun nearestTo(emphasis: Emphasis): Emphasis = when {
        emphasis != Emphasis.BoldItalic -> Emphasis.Normal
        faces.containsKey(Emphasis.Bold) -> Emphasis.Bold
        else -> Emphasis.Italic
    }

    override fun toString(): String = label

    // PdfTheme is a data class, so a theme's equality and its copy() depend on this being value-like rather
    // than identity-based.
    override fun equals(other: Any?): Boolean = this === other ||
        (other is PdfFontFamily && standardFamily == other.standardFamily && faces == other.faces)

    override fun hashCode(): Int = 31 * (standardFamily ?: 0) + faces.hashCode()

    public companion object {

        // ---- the standard families, present in every reader ------------------------------------------

        public val Helvetica: PdfFontFamily = standard(Font.HELVETICA, "Helvetica")
        public val Times: PdfFontFamily = standard(Font.TIMES_ROMAN, "Times-Roman")
        public val Courier: PdfFontFamily = standard(Font.COURIER, "Courier")
        public val Symbol: PdfFontFamily = standard(Font.SYMBOL, "Symbol")
        public val ZapfDingbats: PdfFontFamily = standard(Font.ZAPFDINGBATS, "ZapfDingbats")

        private fun standard(family: Int, label: String): PdfFontFamily =
            PdfFontFamily(standardFamily = family, faces = emptyMap(), label = label)

        // ---- embedded fonts --------------------------------------------------------------------------

        /**
         * A font embedded from files on the filesystem.
         *
         * Supply as many faces as you have. A family given only [regular] still renders bold and italic text,
         * with openpdf synthesising the emphasis; supplying the real faces is better, because a synthesised
         * bold is a stroke drawn around the regular outline rather than the heavier shapes a designer drew.
         *
         * The default [encoding] is `Identity-H`, the encoding that admits the full Unicode range. It is also
         * what makes the font subset-embedded rather than merely referenced, so the file grows by roughly the
         * glyphs actually used instead of by the whole typeface.
         *
         * [regular] and its siblings need not be filesystem paths. openpdf tries the name as a file first and
         * then asks the classloader for it, so `"fonts/Vazirmatn-Regular.ttf"` resolves against
         * `src/main/resources` and keeps working once the application is packaged into a jar. Use the
         * [ByteArray] overload when the bytes come from somewhere a name cannot describe — a stream, a
         * database, a file chosen at runtime.
         *
         * @throws org.openpdf.text.DocumentException if a file is not a font openpdf can read
         * @throws java.io.IOException if a file cannot be read
         */
        @JvmStatic
        @JvmOverloads
        public fun embedded(
            regular: String,
            bold: String? = null,
            italic: String? = null,
            boldItalic: String? = null,
            encoding: String = BaseFont.IDENTITY_H,
        ): PdfFontFamily = PdfFontFamily(
            standardFamily = null,
            faces = buildFaces(
                regular = BaseFont.createFont(regular, encoding, BaseFont.EMBEDDED),
                bold = bold?.let { BaseFont.createFont(it, encoding, BaseFont.EMBEDDED) },
                italic = italic?.let { BaseFont.createFont(it, encoding, BaseFont.EMBEDDED) },
                boldItalic = boldItalic?.let { BaseFont.createFont(it, encoding, BaseFont.EMBEDDED) },
            ),
            label = regular,
        )

        /**
         * A font embedded from bytes already in hand.
         *
         * This is the overload for a font that lives on the classpath, since a packaged resource has no path
         * to open:
         *
         * ```
         * val bytes = javaClass.getResourceAsStream("/fonts/Vazirmatn-Regular.ttf")!!.readBytes()
         * val theme = PdfTheme(fontFamily = PdfFontFamily.embedded(bytes))
         * ```
         *
         * [format] only tells openpdf how to parse the bytes. OpenType files carrying TrueType outlines read
         * fine; a CFF-outline `.otf` may not, in which case the TrueType build of the same font is the answer.
         *
         * @throws org.openpdf.text.DocumentException if the bytes are not a font openpdf can read
         * @throws java.io.IOException if parsing fails
         */
        @JvmStatic
        @JvmOverloads
        public fun embedded(
            regular: ByteArray,
            bold: ByteArray? = null,
            italic: ByteArray? = null,
            boldItalic: ByteArray? = null,
            format: FontFormat = FontFormat.TrueType,
            encoding: String = BaseFont.IDENTITY_H,
        ): PdfFontFamily = PdfFontFamily(
            standardFamily = null,
            faces = buildFaces(
                regular = fromBytes(regular, "regular", format, encoding),
                bold = bold?.let { fromBytes(it, "bold", format, encoding) },
                italic = italic?.let { fromBytes(it, "italic", format, encoding) },
                boldItalic = boldItalic?.let { fromBytes(it, "bolditalic", format, encoding) },
            ),
            label = "embedded" + format.extension,
        )

        /**
         * A family built from fonts the caller has already constructed.
         *
         * The escape hatch. Anything [embedded] does not cover — a font from a stream, an unusual encoding, a
         * face pulled out of an existing PDF — can be prepared with openpdf directly and handed over here.
         */
        @JvmStatic
        @JvmOverloads
        public fun of(
            regular: BaseFont,
            bold: BaseFont? = null,
            italic: BaseFont? = null,
            boldItalic: BaseFont? = null,
        ): PdfFontFamily = PdfFontFamily(
            standardFamily = null,
            faces = buildFaces(regular, bold, italic, boldItalic),
            label = regular.postscriptFontName ?: "embedded",
        )

        private fun buildFaces(
            regular: BaseFont,
            bold: BaseFont?,
            italic: BaseFont?,
            boldItalic: BaseFont?,
        ): Map<Emphasis, BaseFont> = buildMap {
            put(Emphasis.Normal, regular)
            bold?.let { put(Emphasis.Bold, it) }
            italic?.let { put(Emphasis.Italic, it) }
            boldItalic?.let { put(Emphasis.BoldItalic, it) }
        }

        // `cached = false`, so the synthetic name below cannot collide with a different font already
        // registered under it. openpdf reads the name only to decide how to parse the bytes.
        private fun fromBytes(
            bytes: ByteArray,
            face: String,
            format: FontFormat,
            encoding: String,
        ): BaseFont = BaseFont.createFont(
            "docdsl-" + face + format.extension,
            encoding,
            BaseFont.EMBEDDED,
            false,
            bytes,
            null,
        )
    }
}

/** How to parse embedded font bytes. */
public enum class FontFormat(internal val extension: String) {
    TrueType(".ttf"),
    OpenType(".otf"),
}
