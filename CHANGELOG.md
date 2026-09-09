# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project intends to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) from 1.0.0 onward.

## [Unreleased]

### Added

- **`totals(size = …)` and `line(size = …)`.** A totals block's figures were fixed at the renderer's default
  text size, because `totals` renders each line as `cell(label, bold = emphasised)` and stated no size — so
  the only way to set one was to abandon the helper and write the table out by hand. The block-level `size`
  applies to every line and the per-line one overrides it, which is the grand total set larger than the
  figures above it.

  Put on the run rather than resolved by a renderer, so `TableLayout` measures each figure at the size it
  will be drawn at. A totals column measured at 10pt and drawn at 12 is how `84.024,59 EUR` came to break
  across two lines in 0.2.1.

### Known gap

`TextStyle`'s own documentation says a null field means "whatever applies here — the enclosing block, column
or table", and for a **body cell that is not true**: `chunkOf` goes straight from the run to the theme, so a
table cannot supply a size the way `headerStyle` now supplies one for headings. Every body cell that wants a
size still has to say so itself. The fix is a `TableStyle.cellStyle` merged field-wise with each run — the
counterpart of `headerStyle` — and it is not in this release.

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

[Unreleased]: https://github.com/Shahriyar13/docdsl/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/Shahriyar13/docdsl/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/Shahriyar13/docdsl/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/Shahriyar13/docdsl/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Shahriyar13/docdsl/releases/tag/v0.1.0
