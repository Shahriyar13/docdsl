# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project intends to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) from 1.0.0 onward.

## [Unreleased]

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

[Unreleased]: https://github.com/Shahriyar13/docdsl/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Shahriyar13/docdsl/releases/tag/v0.1.0
