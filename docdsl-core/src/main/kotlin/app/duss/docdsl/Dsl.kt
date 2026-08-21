package app.duss.docdsl

/**
 * Confines the builder receivers, so `table { row { ... } }` cannot accidentally reach the document scope from
 * inside a cell and add a page break in the middle of a row.
 */
@DslMarker
public annotation class DocDsl

/**
 * Describes a document.
 *
 * ```
 * val spec = document {
 *     pageFooter {
 *         paragraph(align = Align.End) {
 *             text("Page ")
 *             currentPage()
 *             text(" of ")
 *             totalPages()
 *         }
 *     }
 *
 *     paragraph("Invoice", bold = true, size = TextStyle.TITLE, align = Align.Center)
 *     spacer()
 *
 *     table {
 *         column("Item no.", width = ColumnWidth.Auto)
 *         column("Description of Goods", width = ColumnWidth.Flexible, align = Align.Start)
 *         column("Qty", width = ColumnWidth.Auto, hideWhenEmpty = true)
 *         column("Total (EUR)", width = ColumnWidth.Auto, align = Align.End)
 *
 *         for (line in lines) row {
 *             cell(line.number)
 *             cell {
 *                 text(line.name, bold = true)
 *                 text("\nType: ${line.type}")
 *             }
 *             cell(line.quantity)
 *             cell(line.total, align = Align.End)
 *         }
 *     }
 *
 *     totals(widthFraction = 0.4f, align = Align.End) {
 *         line("Total Value:", total)
 *         if (payable != null) line("Payable:", payable)
 *     }
 * }
 * ```
 */
public fun document(build: DocumentScope.() -> Unit): DocumentSpec {
    val scope = DocumentScope()
    scope.build()
    return DocumentSpec(body = scope.blocks(), frame = scope.frame())
}

// ---------------------------------------------------------------------------------------------------------
//  Block scope — shared by the document body, a page frame and a table cell
// ---------------------------------------------------------------------------------------------------------

/**
 * Somewhere blocks can be added. The same vocabulary works in the document body, in a header or footer, and
 * inside a cell, which is why a table nested in a cell needs no separate spelling.
 */
@DocDsl
public open class BlockScope internal constructor() {

    private val collected: MutableList<Block> = mutableListOf()

    internal fun blocks(): List<Block> = collected.toList()

    /** Adds an already-built block. The escape hatch for content assembled elsewhere. */
    public fun add(block: Block) {
        collected += block
    }

    /** A one-style paragraph. */
    public fun paragraph(
        text: String?,
        bold: Boolean = false,
        italic: Boolean = false,
        size: Float? = null,
        color: DocColor? = null,
        align: Align = Align.Start,
    ) {
        if (text.isNullOrEmpty()) return
        collected += Block.Paragraph(
            runs = listOf(TextRun(text, textStyle(bold, italic, size, color))),
            align = align,
        )
    }

    /**
     * A paragraph of several differently-styled runs — a bold lead-in followed by normal detail.
     *
     * [size] and [color] set what the runs inside start from, exactly as they do on the single-string
     * overload; a run that names its own wins.
     */
    public fun paragraph(
        size: Float? = null,
        color: DocColor? = null,
        align: Align = Align.Start,
        build: TextScope.() -> Unit,
    ) {
        val scope = TextScope(size, color)
        scope.build()
        val runs = scope.runs()
        if (runs.isEmpty()) return
        collected += Block.Paragraph(runs = runs, align = align)
    }

    /** Blank vertical space. */
    public fun spacer(points: Float = 10f) {
        collected += Block.Spacer(points)
    }

    /** Start a new page. */
    public fun pageBreak() {
        collected += Block.PageBreak
    }

    /** A grid. See [TableScope]. */
    public fun table(style: TableStyle = TableStyle(), build: TableScope.() -> Unit) {
        val scope = TableScope()
        scope.build()
        collected += Block.Table(columns = scope.columns(), rows = scope.rows(), style = style)
    }

    /**
     * A full-width centred title band — one unbordered column with one row.
     *
     * Sugar, not a new concept: it builds the same [Block.Table] as anything else.
     */
    public fun banner(
        title: String,
        size: Float = TextStyle.LARGE,
        style: TableStyle = TableStyle(cellBorders = Borders.None, headerBackground = null),
    ) {
        table(style) {
            column(align = Align.Center, width = ColumnWidth.Flexible)
            row { cell(title, bold = true, size = size, align = Align.Center) }
        }
    }

    /**
     * A label-and-amount block: two columns, no header, amounts hard against the right.
     *
     * What every totals table in these documents is. Rows are added conditionally by writing `if` around
     * [TotalsScope.line], so a figure that does not apply simply is not there.
     */
    public fun totals(
        widthFraction: Float = 1f,
        align: Align = Align.End,
        build: TotalsScope.() -> Unit,
    ) {
        val scope = TotalsScope()
        scope.build()
        val lines = scope.lines()
        if (lines.isEmpty()) return
        table(
            TableStyle(
                widthFraction = widthFraction,
                flowAlign = align,
                cellPadding = Padding.all(4f),
            )
        ) {
            column(width = ColumnWidth.Flexible, align = Align.Start)
            column(width = ColumnWidth.Auto, align = Align.End)
            lines.forEach { (label, amount, emphasised) ->
                row {
                    cell(label, bold = emphasised, align = Align.Start)
                    cell(amount, bold = emphasised, align = Align.End)
                }
            }
        }
    }

    /**
     * Places blocks side by side — a notes column beside a totals block, say.
     *
     * A layout table: no grid, no padding, columns weighted by [weights].
     */
    public fun panes(vararg weights: Float, build: PanesScope.() -> Unit) {
        val scope = PanesScope()
        scope.build()
        val panes = scope.panes()
        if (panes.isEmpty()) return
        table(TableStyle.Layout) {
            panes.forEachIndexed { index, _ ->
                column(width = ColumnWidth.Weight(weights.getOrElse(index) { 1f }), align = Align.Start)
            }
            row { panes.forEach { blocks -> cellOf(vAlign = VAlign.Top) { blocks.forEach(::add) } } }
        }
    }

    /** A titled section, dropped entirely when its body turns out empty. */
    public fun section(heading: String, size: Float = TextStyle.MEDIUM, build: BlockScope.() -> Unit) {
        val scope = BlockScope()
        scope.build()
        val body = scope.blocks()
        if (body.isEmpty()) return
        collected += Block.Group(
            blocks = listOf(Block.Paragraph(listOf(TextRun(heading, TextStyle(Emphasis.Bold, size))))) + body,
        )
    }

    /** Keeps these blocks on one page if it can. */
    public fun keepTogether(build: BlockScope.() -> Unit) {
        val scope = BlockScope()
        scope.build()
        collected += Block.Group(blocks = scope.blocks(), keepTogether = true)
    }

    /** A bullet or numbered list. */
    public fun bullets(numbered: Boolean = false, build: ListScope.() -> Unit) {
        val scope = ListScope()
        scope.build()
        val entries = scope.entries()
        if (entries.isEmpty()) return
        collected += Block.Bullets(entries = entries, numbered = numbered)
    }

    /** An image. */
    public fun picture(
        source: ImageSource,
        maxWidthPoints: Float? = null,
        maxHeightPoints: Float? = null,
        align: Align = Align.Start,
    ) {
        collected += Block.Picture(source, maxWidthPoints, maxHeightPoints, align)
    }
}

/** The document itself: blocks, plus what repeats on every page. */
@DocDsl
public class DocumentScope internal constructor() : BlockScope() {

    private var header: List<Block> = emptyList()
    private var footer: List<Block> = emptyList()

    internal fun frame(): PageFrame = PageFrame(header, footer)

    /** Repeated at the top of every page. */
    public fun pageHeader(build: BlockScope.() -> Unit) {
        val scope = BlockScope()
        scope.build()
        header = scope.blocks()
    }

    /** Repeated at the bottom of every page. Where [DocToken] runs earn their keep. */
    public fun pageFooter(build: BlockScope.() -> Unit) {
        val scope = BlockScope()
        scope.build()
        footer = scope.blocks()
    }
}

// ---------------------------------------------------------------------------------------------------------
//  Inline text
// ---------------------------------------------------------------------------------------------------------

/** Builds a sequence of styled runs. */
@DocDsl
public class TextScope internal constructor(
    /**
     * The size and colour every run here starts from, set by whoever opened the scope.
     *
     * Without these a block of runs could not be sized at all: `cell("8pt text", size = SMALL)` states the
     * size once, but the moment a cell needs two differently-emphasised runs and becomes `cell { }`, there
     * would be nowhere left to say it — every run would silently fall back to the theme default, which is how
     * one table ends up two points larger than its neighbours. A run that names its own size still wins.
     */
    private val defaultSize: Float? = null,
    private val defaultColor: DocColor? = null,
) {

    private val collected: MutableList<Run> = mutableListOf()

    internal fun runs(): List<Run> = collected.toList()

    public fun text(
        value: String?,
        bold: Boolean = false,
        italic: Boolean = false,
        size: Float? = null,
        color: DocColor? = null,
    ) {
        if (value.isNullOrEmpty()) return
        collected += TextRun(value, textStyle(bold, italic, size ?: defaultSize, color ?: defaultColor))
    }

    /** The page this ends up on. Resolved by the renderer. */
    public fun currentPage(bold: Boolean = false, size: Float? = null, color: DocColor? = null) {
        collected += TokenRun(
            DocToken.CurrentPage,
            textStyle(bold, italic = false, size = size ?: defaultSize, color = color ?: defaultColor),
        )
    }

    /**
     * How many pages the document has.
     *
     * Unknowable while a PDF is being written — the renderer reserves room and fills it in once the document
     * closes. Stating it here means the document does not have to care.
     */
    public fun totalPages(bold: Boolean = false, size: Float? = null, color: DocColor? = null) {
        collected += TokenRun(
            DocToken.TotalPages,
            textStyle(bold, italic = false, size = size ?: defaultSize, color = color ?: defaultColor),
        )
    }
}

// ---------------------------------------------------------------------------------------------------------
//  Tables
// ---------------------------------------------------------------------------------------------------------

/** Declares a table's columns, then its rows. */
@DocDsl
public class TableScope internal constructor() {

    private val declaredColumns: MutableList<Column> = mutableListOf()
    private val collectedRows: MutableList<Row> = mutableListOf()

    internal fun columns(): List<Column> = declaredColumns.toList()
    internal fun rows(): List<Row> = collectedRows.toList()

    /**
     * Declares a column. Order is the column order.
     *
     * Leave [title] null on every column and the table has no header row — which is what a totals block, a
     * banner or a layout pane is.
     */
    public fun column(
        title: String? = null,
        align: Align = Align.Center,
        width: ColumnWidth = ColumnWidth.Auto,
        hideWhenEmpty: Boolean = false,
    ) {
        declaredColumns += Column(title, align, width, hideWhenEmpty)
    }

    /** One row. Add cells in column order; a short row is padded by the renderer. */
    public fun row(build: RowScope.() -> Unit) {
        val scope = RowScope()
        scope.build()
        collectedRows += Row(scope.cells())
    }
}

/** The cells of one row. */
@DocDsl
public class RowScope internal constructor() {

    private val collected: MutableList<Cell> = mutableListOf()

    internal fun cells(): List<Cell> = collected.toList()

    /** A cell of plain text. */
    public fun cell(
        text: String?,
        bold: Boolean = false,
        italic: Boolean = false,
        size: Float? = null,
        color: DocColor? = null,
        align: Align? = null,
        vAlign: VAlign? = null,
        borders: Borders? = null,
        padding: Padding? = null,
    ) {
        collected += Cell(
            content = if (text.isNullOrEmpty()) {
                emptyList()
            } else {
                listOf(
                    Block.Paragraph(
                        runs = listOf(TextRun(text, textStyle(bold, italic, size, color))),
                        align = align ?: Align.Start,
                    )
                )
            },
            align = align,
            vAlign = vAlign,
            borders = borders,
            padding = padding,
        )
    }

    /** A cell of several runs, styled independently — a bold name above its normal-weight description. */
    public fun cell(
        size: Float? = null,
        color: DocColor? = null,
        align: Align? = null,
        vAlign: VAlign? = null,
        borders: Borders? = null,
        padding: Padding? = null,
        build: TextScope.() -> Unit,
    ) {
        val scope = TextScope(size, color)
        scope.build()
        collected += Cell(
            content = listOf(Block.Paragraph(scope.runs(), align ?: Align.Start)),
            align = align,
            vAlign = vAlign,
            borders = borders,
            padding = padding,
        )
    }

    /**
     * A cell holding whole blocks — most often another table.
     *
     * The nesting that stands in for column spanning, and the reason a "cell containing a table" is not a
     * special case anywhere in this model.
     */
    public fun cellOf(
        align: Align? = null,
        vAlign: VAlign? = null,
        borders: Borders? = null,
        padding: Padding? = null,
        background: DocColor? = null,
        minHeightPoints: Float? = null,
        build: BlockScope.() -> Unit,
    ) {
        val scope = BlockScope()
        scope.build()
        collected += Cell(
            content = scope.blocks(),
            align = align,
            vAlign = vAlign,
            borders = borders,
            padding = padding,
            background = background,
            minHeightPoints = minHeightPoints,
        )
    }

    /** An empty cell, for holding a grid's shape. */
    public fun emptyCell(borders: Borders? = null) {
        collected += Cell(borders = borders)
    }
}

// ---------------------------------------------------------------------------------------------------------
//  Sugar scopes
// ---------------------------------------------------------------------------------------------------------

/** The label/amount pairs of a [BlockScope.totals] block. */
@DocDsl
public class TotalsScope internal constructor() {

    internal data class Line(val label: String, val amount: String, val emphasised: Boolean)

    private val collected: MutableList<Line> = mutableListOf()

    internal fun lines(): List<Line> = collected.toList()

    /**
     * One label-and-amount row, or nothing at all when there is no amount.
     *
     * Empty counts as absent as well as null, which matters because an absent figure usually arrives here
     * having been through a formatter — and a formatter given nothing returns `""`, not null. Treating only
     * null as absent would put a row with a blank amount on the document.
     */
    public fun line(label: String, amount: String?, emphasised: Boolean = false) {
        if (amount.isNullOrEmpty()) return
        collected += Line(label, amount, emphasised)
    }
}

/** The side-by-side panes of a [BlockScope.panes] row. */
@DocDsl
public class PanesScope internal constructor() {

    private val collected: MutableList<List<Block>> = mutableListOf()

    internal fun panes(): List<List<Block>> = collected.toList()

    public fun pane(build: BlockScope.() -> Unit) {
        val scope = BlockScope()
        scope.build()
        collected += scope.blocks()
    }
}

/** The entries of a [BlockScope.bullets] list. */
@DocDsl
public class ListScope internal constructor() {

    private val collected: MutableList<ListEntry> = mutableListOf()

    internal fun entries(): List<ListEntry> = collected.toList()

    public fun item(text: String) {
        collected += ListEntry.Item(listOf(TextRun(text)))
    }

    public fun item(size: Float? = null, color: DocColor? = null, build: TextScope.() -> Unit) {
        val scope = TextScope(size, color)
        scope.build()
        collected += ListEntry.Item(scope.runs())
    }

    /** A nested list under the previous level. */
    public fun sub(numbered: Boolean = false, build: ListScope.() -> Unit) {
        val scope = ListScope()
        scope.build()
        collected += ListEntry.Sub(Block.Bullets(scope.entries(), numbered))
    }
}

// ---------------------------------------------------------------------------------------------------------

/** Null when nothing was asked for, so the renderer's own defaults apply rather than being overwritten. */
internal fun textStyle(
    bold: Boolean,
    italic: Boolean,
    size: Float?,
    color: DocColor?,
): TextStyle? {
    val emphasis = when {
        bold && italic -> Emphasis.BoldItalic
        bold -> Emphasis.Bold
        italic -> Emphasis.Italic
        else -> Emphasis.Normal
    }
    if (emphasis == Emphasis.Normal && size == null && color == null) return null
    return TextStyle(emphasis, size, color)
}
