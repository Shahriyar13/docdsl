# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project intends to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) from 1.0.0 onward.

## [Unreleased]

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

[Unreleased]: https://github.com/Shahriyar13/docdsl/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/Shahriyar13/docdsl/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Shahriyar13/docdsl/releases/tag/v0.1.0
