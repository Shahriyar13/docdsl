package app.duss.docdsl

/**
 * A document, described rather than drawn.
 *
 * Nothing here knows how a PDF is written, which is the point: a renderer turns this into pages, and a
 * different renderer can turn the same description into a spreadsheet or HTML without the document having to
 * be expressed twice. Build one with the [document] builder rather than by hand.
 */
public data class DocumentSpec(
    public val body: List<Block>,
    /** Repeated on every page. Empty when the medium has no notion of a page. */
    public val frame: PageFrame = PageFrame(),
)

/**
 * What repeats on every page.
 *
 * These are ordinary [Block]s, which is what makes a running header describable rather than something only the
 * renderer can express. A footer that reads "Page 2 of 7" is a [Block.Paragraph] holding
 * [DocToken.CurrentPage] and [DocToken.TotalPages]; the renderer works out what those are.
 */
public data class PageFrame(
    public val header: List<Block> = emptyList(),
    public val footer: List<Block> = emptyList(),
)

// ---------------------------------------------------------------------------------------------------------
//  Blocks
// ---------------------------------------------------------------------------------------------------------

/** One thing in the document's flow, or inside a table cell. */
public sealed interface Block {

    /** A run of text on its own — the default unit of content. */
    public data class Paragraph(
        public val runs: List<Run>,
        public val align: Align = Align.Start,
    ) : Block

    /**
     * A grid, and the workhorse of the whole model.
     *
     * Item lists, the two-column information grid at the top of a document, totals blocks, side-by-side panes,
     * signature boxes and full-width section banners are all this one type with different [columns] — a banner
     * is a single unbordered column, a totals block is two columns with no titles.
     *
     * **There is no column or row spanning**, because none of the documents this was built for use one. Where a
     * span would be reached for they nest a full-width table inside a single cell, and since [Cell.content]
     * holds blocks, nesting needs nothing extra.
     */
    public data class Table(
        public val columns: List<Column>,
        public val rows: List<Row>,
        public val style: TableStyle = TableStyle(),
    ) : Block

    /** A bullet or numbered list. Nests through [ListEntry.Sub]. */
    public data class Bullets(
        public val entries: List<ListEntry>,
        public val numbered: Boolean = false,
        public val indentPoints: Float = 15f,
    ) : Block

    /** An image, scaled to fit the given box while keeping its proportions. */
    public data class Picture(
        public val source: ImageSource,
        public val maxWidthPoints: Float? = null,
        public val maxHeightPoints: Float? = null,
        public val align: Align = Align.Start,
    ) : Block

    /**
     * Several blocks treated as one, optionally kept on the same page.
     *
     * What a heading plus its table wants: splitting them leaves a title stranded at the bottom of a page.
     */
    public data class Group(
        public val blocks: List<Block>,
        public val keepTogether: Boolean = false,
    ) : Block

    /** Blank vertical space. */
    public data class Spacer(public val points: Float = 10f) : Block

    /** Start a new page here. */
    public data object PageBreak : Block
}

/** One entry in a [Block.Bullets]. */
public sealed interface ListEntry {
    public data class Item(public val runs: List<Run>) : ListEntry
    public data class Sub(public val list: Block.Bullets) : ListEntry
}

/** Where an image's bytes come from, named without depending on any imaging library. */
public sealed interface ImageSource {
    /** A file on the machine doing the rendering. */
    public data class Path(public val value: String) : ImageSource

    /** The encoded bytes of a PNG, JPEG or similar. */
    public data class Bytes(public val value: ByteArray) : ImageSource {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()
    }
}

// ---------------------------------------------------------------------------------------------------------
//  Inline content
// ---------------------------------------------------------------------------------------------------------

/** A piece of inline content. Several in a row make the mixed-weight text these documents use throughout. */
public sealed interface Run {
    public val style: TextStyle?
}

/** Literal text. */
public data class TextRun(
    public val text: String,
    override val style: TextStyle? = null,
) : Run

/**
 * Something only the renderer can know, written where it belongs and resolved when it is known.
 *
 * This is how a page count gets into a document. A streamed PDF cannot know its own length while being
 * written, so the document says [DocToken.TotalPages] and the renderer reserves the space and fills it in at
 * the end. A medium with no pages simply renders nothing for it.
 */
public data class TokenRun(
    public val token: DocToken,
    override val style: TextStyle? = null,
) : Run

/** A value the renderer substitutes. */
public enum class DocToken { CurrentPage, TotalPages }

/**
 * How a run of text looks.
 *
 * Every field is optional, and null means "whatever applies here" — the enclosing block, column or table. A
 * document should be able to say "this is an item's name" without also having to know that item names are 8pt
 * Helvetica, so the renderer owns the defaults and the document states only its departures from them.
 */
public data class TextStyle(
    public val emphasis: Emphasis = Emphasis.Normal,
    public val sizePoints: Float? = null,
    public val color: DocColor? = null,
) {
    public companion object {
        /** The sizes these documents actually use, named so a caller need not remember the numbers. */
        public const val SMALL: Float = 8f
        public const val MEDIUM: Float = 10f
        public const val LARGE: Float = 12f
        public const val TITLE: Float = 14f
    }
}

public enum class Emphasis { Normal, Bold, Italic, BoldItalic }

/** A colour, without dragging in a graphics library. */
public data class DocColor(public val red: Int, public val green: Int, public val blue: Int) {
    public companion object {
        public val Black: DocColor = DocColor(0, 0, 0)
        public val DarkGray: DocColor = DocColor(64, 64, 64)
        public val Gray: DocColor = DocColor(128, 128, 128)
        public val LightGray: DocColor = DocColor(192, 192, 192)
        public val Red: DocColor = DocColor(200, 0, 0)
    }
}

/**
 * Horizontal placement. Start/End rather than Left/Right, so the same document could be rendered
 * right-to-left later without every alignment in it being wrong.
 */
public enum class Align { Start, Center, End, Justify }

/** Vertical placement within a cell. */
public enum class VAlign { Top, Middle, Bottom }

// ---------------------------------------------------------------------------------------------------------
//  Tables
// ---------------------------------------------------------------------------------------------------------

/**
 * One column.
 *
 * [title] is what makes a table headed: when no column has a title there is no header row at all, which is how
 * banners, totals blocks and layout panes are expressed without needing their own types.
 */
public data class Column(
    public val title: String? = null,
    public val align: Align = Align.Center,
    public val width: ColumnWidth = ColumnWidth.Auto,
    /**
     * How the header sits over the column, when it should not sit the way [headerAlignOrDefault] would put it.
     *
     * Null almost always. See [headerAlignOrDefault] for the rule it overrides.
     */
    public val headerAlign: Align? = null,
    /**
     * Drop this column entirely — its header, its width and every one of its cells — when no row has anything
     * in it.
     *
     * These documents do it constantly: an item table hides its price columns until something is priced, and
     * its quantity column until something has a quantity. Expressing it here rather than at the call site is
     * what stops the three parallel lists (titles, widths, cells) drifting apart, which is the usual way such
     * a table breaks.
     */
    public val hideWhenEmpty: Boolean = false,
) {
    /**
     * Where this column's heading goes: **centred over the column, unless the column is prose.**
     *
     * A heading is a label for a column, not the first value in it, so centring it is what makes it read as
     * one — over quantities, over prices whose figures are hard right so their decimals line up, over row
     * numbers. Prose is the exception and the only one: a description column's heading belongs at the left
     * edge with the text it describes, and a centred "Item Description" floating over a paragraph looks like
     * a title that has come adrift.
     *
     * Derived rather than defaulted so the rule lives in one place instead of at every table that has ever
     * declared a column. [headerAlign] overrides it where a document really does want something else.
     */
    public val headerAlignOrDefault: Align
        get() = headerAlign ?: if (align == Align.Start) Align.Start else Align.Center
}

/**
 * How wide a column should be.
 *
 * This is the piece the hand-rolled table could not express. Widths were bare floats meaning a percentage at
 * some call sites and an arbitrary weight at others, compared against a *character count* — so a long
 * description crowded every number into a few percent of the page and prices wrapped onto two lines. Saying
 * what a column IS lets the renderer measure it in the font it will be drawn in.
 */
public sealed interface ColumnWidth {
    /**
     * Exactly as wide as its widest cell needs. For anything that must not wrap: quantities, prices, dates,
     * codes, row numbers.
     */
    public data object Auto : ColumnWidth

    /**
     * Takes what is left over. For prose, which is the thing that should wrap. Several flexible columns share
     * the remainder evenly.
     */
    public data object Flexible : ColumnWidth

    /**
     * A deliberate share of the table, for layouts that are about geometry rather than content — the 45/55
     * information grid at the top of a proforma invoice, or a 50/50 pane split.
     */
    public data class Weight(public val value: Float) : ColumnWidth
}

/** One row. A row exists only if it was added, so conditional rows need no support of their own. */
public data class Row(public val cells: List<Cell>)

/**
 * One cell. Its content is blocks, which is what lets a cell hold text, several paragraphs, an image, or
 * another whole table without any of those being a special case.
 *
 * The style overrides are null when the cell is content with what its column and table say — keeping the
 * common case quiet and making the exceptions visible.
 */
public data class Cell(
    public val content: List<Block> = emptyList(),
    public val align: Align? = null,
    public val vAlign: VAlign? = null,
    public val borders: Borders? = null,
    public val padding: Padding? = null,
    public val background: DocColor? = null,
    /** Forces a minimum height — how a signature box reserves space to be written in by hand. */
    public val minHeightPoints: Float? = null,
)

/**
 * Which sides of a cell are drawn, and how thickly. A width of 0 means that side is absent.
 *
 * Widths rather than booleans because these documents do use two weights: a hairline grid at 0.5 with a
 * heavier 1.0 around a header band.
 */
public data class Borders(
    public val top: Float = 0f,
    public val bottom: Float = 0f,
    public val start: Float = 0f,
    public val end: Float = 0f,
    public val color: DocColor = DocColor.Gray,
) {
    public fun without(vararg sides: Side): Borders = Borders(
        top = if (Side.Top in sides) 0f else top,
        bottom = if (Side.Bottom in sides) 0f else bottom,
        start = if (Side.Start in sides) 0f else start,
        end = if (Side.End in sides) 0f else end,
        color = color,
    )

    public companion object {
        public val None: Borders = Borders()
        public fun box(width: Float = 0.5f, color: DocColor = DocColor.Gray): Borders =
            Borders(width, width, width, width, color)

        public fun topOnly(width: Float = 0.5f, color: DocColor = DocColor.Gray): Borders =
            Borders(top = width, color = color)

        public fun bottomOnly(width: Float = 0.5f, color: DocColor = DocColor.Gray): Borders =
            Borders(bottom = width, color = color)
    }
}

public enum class Side { Top, Bottom, Start, End }

/** Space inside a cell, in points. Null fields fall back to the table's own padding. */
public data class Padding(
    public val top: Float? = null,
    public val bottom: Float? = null,
    public val start: Float? = null,
    public val end: Float? = null,
) {
    public companion object {
        public val None: Padding = Padding(0f, 0f, 0f, 0f)
        public fun all(points: Float): Padding = Padding(points, points, points, points)
    }
}

/** How the table itself sits on the page. */
public data class TableStyle(
    /** 1.0 is the full width available; 0.4 is a totals block hugging one side. */
    public val widthFraction: Float = 1f,
    /** Which way a table narrower than the page floats. */
    public val flowAlign: Align = Align.Justify,
    /** The grid drawn around and between cells. [Borders.None] for a layout table that should be invisible. */
    public val cellBorders: Borders = Borders.box(),
    /** Shade behind the header row. Ignored when no column has a title. */
    public val headerBackground: DocColor? = DocColor.LightGray,
    /** Repeat the header row when the table runs onto another page. */
    public val repeatHeader: Boolean = true,
    /** Try not to split this table across a page boundary. */
    public val keepTogether: Boolean = false,
    /**
     * Applies to every cell that does not override it.
     *
     * Small on purpose. This was 8pt at the bottom of every cell, which is invisible on a five-row table and
     * costs six pages on a five-hundred-row one — a proforma invoice grew by half its length before anyone
     * looked at the two side by side. 2pt is what a PDF cell has by default and what these documents were
     * drawn with.
     */
    public val cellPadding: Padding = Padding(top = 2f, bottom = 2f, start = 2f, end = 2f),
    public val cellVAlign: VAlign = VAlign.Middle,
) {
    public companion object {
        /**
         * A table used purely to place things side by side: no grid, no shading, no padding.
         *
         * The "notes beside totals" pane and the header bands are this. Both were previously hand-built twice,
         * in two different ways, because there was no name for the idea.
         */
        public val Layout: TableStyle = TableStyle(
            cellBorders = Borders.None,
            headerBackground = null,
            cellPadding = Padding.None,
            cellVAlign = VAlign.Top,
        )
    }
}
