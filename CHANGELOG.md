# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project intends to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) from 1.0.0 onward.

## [Unreleased]

### Known gap

`TextStyle`'s own documentation says a null field means "whatever applies here — the enclosing block, column
or table", and for a **body cell that is not true**: `chunkOf` goes straight from the run to the theme, so a
table cannot supply a size the way `headerStyle` now supplies one for headings. Every body cell that wants a
size still has to say so itself. The fix is a `TableStyle.cellStyle` merged field-wise with each run — the
counterpart of `headerStyle` — and it is not in this release.

## [0.4.1] — 2026-09-11

A picture ignored the bounds it was given. Found the way the last one was, by measuring a real document: a
letterhead logo asked to fit a 200x60pt box arrived **2166 points wide** — `cx="27508200"` in the drawing
XML, 41 columns across and 26 rows down, the whole letterhead and most of the document under a company badge.

### Fixed

- **`picture(source, maxWidthPoints = …, maxHeightPoints = …)` is honoured in a workbook.** The bounds were
  read into the layout and then thrown away at emission, because placing a picture ended in POI's `resize()`
  — which means "resize to the image's *original* size". One scale is now derived for both axes, so the
  image keeps its proportions, and it is never above 1: a bound is a ceiling, not a size to grow into.

  `resize(scale)` is not the fix it looks like, and is worth recording so it is not reached for again: it
  scales the **anchor's current extent**, not the image. A ratio worked out from the natural size therefore
  lands the width correctly and the height anywhere at all, and the two disagree by however much the rows the
  anchor happens to span differ from the rows it should have spanned.

- **A picture is placed after the row heights are applied.** Its anchor is stated as a cell plus an offset
  into it, so placing one means walking out across the columns and rows it covers — and until the rows are
  their final height, that walk measures nothing. This is also why the old `resize()` was inconsistent rather
  than merely wrong: it measured rows still sitting at the default height, so the same document produced a
  different overshoot depending on how tall its letterhead was.

- **The shape states its own size too.** A two-cell anchor is what decides the size on screen, but `ext`
  inside the shape is meant to agree with it, and POI keeps the two in step when it does the resizing itself.
  A shape claiming to be nothing by nothing is left for no reader to interpret.

## [0.4.0] — 2026-09-10

The release that makes the nesting idiom actually work. `Block.Table` has said since 0.1.0 that where a span
would be reached for, these documents "nest a full-width table inside a single cell" — and a nested table was
never full-width. It was inset by the parent cell's padding, so the sub-grid was drawn a few points inside
the very box it was supposed to complete, and no amount of border styling could make the two meet.

Found by measuring a real document. On one proforma invoice the header had **twenty distinct vertical border
positions where the form has six**, and fifty-four horizontal segments that started inside the frame and
stopped short of it: the outer frame at x=40.25 and every sub-grid's left edge at x=43.25, the 45/55 splitter
at x=272.00 and the sub-grids at x=275.00, the right frame at x=554.75 and the sub-grids at x=551.75. Three
points, both sides, every row — the `Padding(0, 3, 3, 3)` the table had asked for.

### Fixed

- **A nested grid now spans its cell.** Padding is space around a cell's *content*, and a grid is not
  content — it stands in for column spanning, so its borders are meant to continue the parent's. The
  horizontal padding therefore comes off a cell that holds one, and the vertical padding comes off the end
  the grid actually reaches. A cell holding a heading **above** a table keeps the space above the heading and
  loses the space below the table, which is where the doubled border was.

  The padding that kept text off the border is not lost: it is re-applied to the paragraphs, lists and images
  sharing the cell, as `indentationLeft`/`indentationRight` and, for the first and last block, as
  `spacingBefore`/`spacingAfter`. Measured on the item-list case, the group heading starts at **x=82.90 both
  before and after** while the sub-table's edges move from 83.15/552.75 onto the column's own 81.15/554.75.

  **An explicit `Cell.padding` is still honoured as written.** That is the escape hatch, and it is deliberate
  rather than incidental: a `cellOf(padding = Padding(5f, …)) { … }` around a nested table is a statement
  about that cell, and a document that wants a sub-table inset says so there. Only padding inherited from
  `TableStyle.cellPadding` is moved, because that is a default for text and was never a statement about the
  grid.

- **A nested grid now fills its cell's height too**, which is the same sentence on the other axis. A cell
  whose whole content is one grid is built as a **table cell** rather than as a composite of elements: a
  composite lays the nested table out at its natural height and then positions it, so a one-line grid
  beside a two-line one was a box floating inside the row with white space above and below, and a grid
  whose cells were all empty collapsed to its padding — 3pt against a neighbour's 12pt. On the reported
  header the "Main Supplier" half drew at y 571.40–559.20 inside a row spanning 576.00–554.60; it now
  draws 576.00–554.60 like the half beside it, and the two collapsed value halves are gone.

  **A vertical alignment opts out of it.** Measured on OpenPDF 3.0.0: a one-line grid beside a two-line one
  fills its 16pt row as a table cell and shrinks back to a floating 8pt box the moment `verticalAlignment`
  is set to anything but the default. That is coherent rather than a workaround — there is nothing to align
  something that fills its container — so an explicit `Cell.vAlign` says "position it, do not fill",
  exactly as an explicit `Cell.padding` says "inset it, do not span". `TableStyle.cellVAlign` is not an
  explicit statement about a cell and no longer suppresses the fill.

- **A nested block is measured against the width it is given.** `bodyCell` handed its children the full
  column width while placing them in the column *less* the padding, so `TableLayout` sized an `Auto` column
  inside a padded cell against a few points it never got — the same class of error as the squeezed price
  column in 0.2.1. Only cells containing a table or a group are affected; nothing else reads that width.

- **`Padding` now resolves field by field**, which is what its own documentation has always claimed: a cell
  stating only a `top` takes the other three from the table. `applyPadding` skips nulls, so an unstated
  side previously kept whatever the `PdfPCell` constructor defaulted to — and the two constructors above do
  not default to the same thing, so the old behaviour was not even stable.

### Note on spanning

Still **no `colSpan`/`rowSpan`**. What changed is that the documented substitute now behaves as documented.
Real spanning remains a separate question, and the reconciliation named in 0.3.0 still applies: the PDF
renderer would take it through `PdfPCell`, while the spreadsheet renderer already merges regions to place a
nested table.

`docdsl-poi` is untouched. A sheet cell has no padding to move — see 0.2.0's note — so the idiom was never
broken there.

### Added

- **`totals(size = …)` and `line(size = …)`.** A totals block's figures were fixed at the renderer's default
  text size, because `totals` renders each line as `cell(label, bold = emphasised)` and stated no size — so
  the only way to set one was to abandon the helper and write the table out by hand. The block-level `size`
  applies to every line and the per-line one overrides it, which is the grand total set larger than the
  figures above it.

  Put on the run rather than resolved by a renderer, so `TableLayout` measures each figure at the size it
  will be drawn at. A totals column measured at 10pt and drawn at 12 is how `84.024,59 EUR` came to break
  across two lines in 0.2.1. Written for 0.3.1 and never released; it ships here.

## [0.3.0] — 2026-09-09

Two things a table could not previously say about itself.

### Added

- **Headings can be styled.** `TableStyle.headerStyle` sets the size, weight and colour of every heading in
  a table, and `Column.headerStyle` overrides it for one column — the same shape as the existing
  `headerAlign`. A heading was fixed at the renderer's default text style, so there was no way to ask for a
  heading smaller or heavier than the body it sits over.

  A styled heading is **measured** in its own font, not the default one: `TableLayout.columnWidths` takes the
  table's header style so the arithmetic that sizes a column and the drawing that fills it agree. Sizing a
  column at 10pt and then drawing its heading at 12pt is how a heading comes to wrap inside a column that was
  supposed to fit it.
- **`TableStyle.headerPadding`**, which falls back to `cellPadding` — what the header row was previously fixed
  at. A table can now give its headings room without loosening every row as well, which on a long item table
  is the difference between a readable header and six extra pages.
- **`TableStyle.allowRowSplit`.** False moves a row too tall for what is left of the page onto the next page
  whole, instead of breaking it across the boundary. Worth turning off for a row that has to be read as one
  thing — a line item whose description, HS code and country of origin mean little three lines at a time on
  one page and two on the next.

  Defaults to true, which is what every document already does, so nothing changes without asking. It costs
  white space, which is why it is not the default.

  Ignored by the spreadsheet renderer, along with `headerPadding`: a sheet has no page boundary to split a
  row across until it is printed, and a spreadsheet cell has no padding to set. `headerStyle` does carry over.
- **The composite helpers take a style.** `banner` already did; `panes`, `totals` and `section` baked theirs
  in, so the combined layouts they build — a full-width band, columns side by side, a label-and-amount block
  — could not be given different padding without abandoning the helper and hand-building its table.

  - `panes(…, style = …, vAlign = …)`. The style is the table the panes sit in, and its `cellPadding` is
    what separates one pane from the next, a pane being a cell. `vAlign` was fixed at `Top`.
  - `totals(…, style = …)`, defaulting to the 4pt padding it always used. `widthFraction` and `align` still
    win over the same two fields, since every caller states them.
  - `section(…, headingStyle = …)`, for a heading that wants a colour or a weight rather than just a size.

  Every default is what the helper produced before, and EasyProject's ten generators compile against the new
  signatures unchanged.

### Note on spanning

There is still **no `colSpan`/`rowSpan`**, and a combined cell is still a nested full-width table inside a
single cell — whose own `TableStyle` has always been settable. What changed is the helpers above, which had
that style hardcoded. Real spanning remains a separate question: the PDF renderer would take it through
`PdfPCell`, but the spreadsheet renderer already merges regions to place a nested table, so the two would
need reconciling rather than a flag adding.

## [0.2.1] — 2026-08-31

The first release driven by looking at the output. Nine documents had been converted, compiled, and covered by
structural tests; none had been put beside the PDF it replaced. A proforma invoice that was 31 pages came out
at 46, and every cause was in this library rather than in any document.

### Fixed

- **`TableStyle.cellPadding` defaulted to 8pt at the bottom of every cell.** Invisible on a five-row table and
  six pages on a five-hundred-row one. Now 2pt all round, which is what a PDF cell has by default.
- **Paragraph leading was OpenPDF's 1.5×**, which reads as generously spaced prose in a document that is
  mostly dense tabular fact. `PdfTheme.lineSpacing`, 1.15 by default.
- **A spacer cost a whole line of leading plus its spacing**, so `spacer(10f)` opened about 25pt. Its line now
  has zero leading, so the gap is the spacing and nothing else.
- **`autoColumnSlackPoints` 6 → 12.** The slack is the only breathing room a column measuring the width of
  "9" ever gets, and at 6pt the digits sat on the grid lines.
- **A numbered list reserved a fixed 15pt for its marker** — enough for "9." and not for "10.", so a ten-item
  list printed `10Order Confirmation`. The gutter is measured from the real item count in the font it will be
  drawn in.
- **A measured column could be squeezed until it wrapped.** When the columns wanted more than the table had, a
  `Flexible` column insisted on `minFlexibleColumnPoints` and everything was then scaled down together —
  including an `Auto` price column, which broke `84.024,59 EUR` after the number. Prose gives way first now,
  down to the new `PdfTheme.hardMinColumnPoints`, and a measured column is only touched once there is nothing
  left to give.

### Added

- **`Column.headerAlign`**, with `headerAlignOrDefault` deriving the rule: a heading is centred over its
  column **unless the column is prose**, in which case it aligns with the text it describes. Previously a
  heading took its column's alignment, so a price column's heading sat hard right and read as the first value
  rather than as a label. Derived rather than defaulted, so no existing table declaration changed.

## [0.2.0] — 2026-08-28

A second renderer, and the first evidence that the module split was worth having: **not one line of
`docdsl-core`'s document model changed to add it**, and no document has to know which medium it will be
rendered to.

### Added

- **`docdsl-poi`** — renders a `DocumentSpec` to an .xlsx workbook through Apache POI. `ExcelRenderer` offers
  the same three entry points as `OpenPdfRenderer`: `render`, `renderToBytes`, and `renderBody` for writing
  into a sheet somebody else created.
- `ExcelTheme` and `SheetGeometry`, the counterparts of `PdfTheme` and `PageGeometry`. Stated in points for
  the same reason: a `ColumnWidth.Weight(45)` column has to come out 45% of the same width in both media, or
  an information grid is a different shape in the workbook than on the page.
- **`TableLayout` and `TextMeasurer` in `docdsl-core`.** Column sizing was private to the PDF renderer;
  a document that renders to two media has to place its columns the same way in both, so the arithmetic is
  shared and only the measurement differs — real font metrics for a PDF, published Helvetica advance widths
  for a sheet, whose own column width is quantised to characters anyway.

### Notes on the spreadsheet renderer

A page is free-form and a sheet has exactly one column grid. The reconciliation is to lay the document out in
points as usual and then make the sheet's physical columns the **union of every x position any table edge
lands on**, merging each logical cell across the ones it covers. A 45/55 information grid and a five-column
item table below it therefore keep their real proportions and still share one grid.

Four things a spreadsheet cannot reproduce, and does not pretend to:

- **Row heights are estimated.** Excel will not auto-fit a row containing a merged cell, and nearly every row
  produced this way has one.
- **Pagination is Excel's.** `pageBreak()` becomes a print break; where the other pages fall is the print
  setup's decision.
- **A cell holding a nested table** becomes a region of sheet cells with a box drawn around it.
- **Cell padding** has no equivalent; it survives as row height rather than as space inside the cell.

### Changed

- `OpenPdfRenderer` now sizes columns through `TableLayout`. Behaviour is unchanged — the code moved, the
  arithmetic did not.

## [0.1.0] — 2026-08-21

The first release. The API is not yet stable: while the version stays below 1.0.0, a minor bump may change or
remove public declarations. Pin an exact version.

### Added

- **`docdsl-core`** — a document model (`DocumentSpec`, `Block`, `Run`, `Cell`, `Column`) and a Kotlin builder
  DSL (`document { }`) for describing business documents. No third-party dependencies, and no knowledge of any
  rendering library, so the same description can drive more than one renderer.
- **`docdsl-openpdf`** — renders a `DocumentSpec` to PDF through OpenPDF.
- Page frames (`pageHeader`, `pageFooter`) with deferred token resolution, so a footer can read
  `Page 3 of 7`. The total is written into a placeholder once the last page is known, which is not something a
  header callback can do on its own.
- Automatic column widths. `ColumnWidth.Auto` measures the text in the font it will actually be drawn in, so a
  price or a quantity stops wrapping onto a second line; `ColumnWidth.Flexible` divides what is left, and
  `ColumnWidth.Weight` keeps a fixed ratio.
- `hideWhenEmpty` on a column, which drops the column and keeps the header, widths and cells in step — going
  out of step is what makes OpenPDF reject a whole table.
- Tables nested inside cells, to any depth, as ordinary blocks rather than a special case.
- `renderBody(spec, into = existingDocument)` for incremental adoption: a codebase already drawing PDFs with
  OpenPDF can move one document at a time and keep its existing headers, footers and page furniture.
- `PdfTheme` as the single place a house style lives, and `PdfFontFamily` for the typeface — the five standard
  PDF families, or an embedded TrueType/OpenType font via `PdfFontFamily.embedded`, which is what any script
  outside Latin-1 requires.

[Unreleased]: https://github.com/Shahriyar13/docdsl/compare/v0.4.1...HEAD
[0.4.1]: https://github.com/Shahriyar13/docdsl/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/Shahriyar13/docdsl/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/Shahriyar13/docdsl/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/Shahriyar13/docdsl/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/Shahriyar13/docdsl/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Shahriyar13/docdsl/releases/tag/v0.1.0
