# docdsl

Describe a business document — an invoice, a purchase order, an inspection request — as a value, and let a
renderer turn it into a file. The description says what the document *is*: these columns, this nested table,
this footer. It does not know that a PDF library exists, which is why a second renderer is a sibling module
rather than a reason to describe every document again.

```kotlin
val spec = document {
    paragraph("Invoice", bold = true, size = TextStyle.TITLE, align = Align.Center)
    spacer()
    table {
        column("Item no.", width = ColumnWidth.Auto)
        column("Description of Goods", width = ColumnWidth.Flexible, align = Align.Start)
        column("Total (EUR)", width = ColumnWidth.Auto, align = Align.End)
        row {
            cell("1")
            cell("Wagon tippler, mechanical")
            cell("1.234.567,89", align = Align.End)
        }
    }
}

OpenPdfRenderer().render(spec, File("invoice.pdf").outputStream())
```

## Why it exists

It grew out of a Spring Boot application that generates about ten kinds of trade document with OpenPDF, and it
solves two problems that drawing them by hand does not.

**A footer can say "Page 3 of 7."** While a PDF is being written the total page count is not yet known, so a
page-event callback cannot print it. docdsl writes a placeholder into the footer and fills it in when the
document closes and the total is finally known. In the DSL that is just a token:

```kotlin
pageFooter {
    paragraph(align = Align.End) {
        text("Page ")
        currentPage()
        text(" of ")
        totalPages()
    }
}
```

**Table columns can be sized by measurement rather than by guess.** A column declared `ColumnWidth.Auto` is
measured against the glyphs it will actually be drawn in, using the font metrics of the very font the renderer
will use, and is then given that much room plus a small slack (`PdfTheme.autoColumnSlackPoints`, 6pt by
default) so the glyphs are not flush against the border. The code this replaced estimated width as
`text.length * 1.5`, which ignores the font: at 8pt Helvetica a digit is about 4.45pt and a space about 2.2pt,
so the same character count can differ twofold in real width. That is how a long description ends up crowding
a price into a few percent of the page and wrapping `1.234.567,89` onto three lines. Declaring the *intent* —
this column must not wrap, that one is prose and should — moves the arithmetic out of the caller entirely.

## Installing

```kotlin
dependencies {
    implementation("app.duss.docdsl:docdsl-openpdf:0.1.0")
}
```

That pulls in `docdsl-core` and OpenPDF transitively. Depend on `app.duss.docdsl:docdsl-core:0.1.0` alone if
you only want to build and pass around document descriptions — to unit-test the shape of a document, say —
without a PDF library on the classpath.

**0.1.0 is early.** The API is still moving; while the version is below 1.0.0 a minor bump may change or
remove public declarations. Pin an exact version rather than a range.

While developing against a local checkout, a composite build avoids publishing anything at all. In the
consuming project's `settings.gradle.kts`:

```kotlin
includeBuild("../docdsl")
```

Gradle then substitutes the sibling checkout wherever the `app.duss.docdsl` coordinates appear, so the
dependency line above needs no change and every edit to the library is picked up on the next build.

## Requirements

JVM 17 or newer. The artifacts are compiled to JVM 17 bytecode against the JDK 17 API.

## Tables

A table declares its columns, then its rows. Cells are added in column order; a short row is padded for you.

```kotlin
table {
    column("Item no.", width = ColumnWidth.Auto)
    column("Description of Goods", width = ColumnWidth.Flexible, align = Align.Start)
    column("Qty", width = ColumnWidth.Auto)
    column("Unit Price", width = ColumnWidth.Auto, align = Align.End, hideWhenEmpty = true)

    row {
        cell("1")
        cell {
            text("Wagon tippler", bold = true)
            text("\nType: Mechanical")
        }
        cell("12 pieces")
        cell(null)
    }
}
```

Three things there are worth naming.

`cell { }` takes a block of runs rather than a string, so part of a cell can be bold without the cell becoming
a special case. It accepts `size` and `color` too, which set what the runs inside start from — otherwise a cell
would lose the ability to state its own size the moment it needed two differently-styled runs, and would fall
back to the theme default. Any run that names its own size still wins. `paragraph { }` works the same way.

`cellOf { }` goes further and takes *blocks*, which is how a cell holds several paragraphs, an image, or
another whole table:

```kotlin
row {
    cell("1")
    cellOf {
        paragraph("Assembly", bold = true)
        table {
            column("#", width = ColumnWidth.Auto)
            column("Sub-item", width = ColumnWidth.Flexible, align = Align.Start)
            row { cell("1"); cell("Impeller") }
            row { cell("2"); cell("Shaft") }
        }
    }
}
```

`hideWhenEmpty` drops a column — its header, its width and every one of its cells — when no row put anything
in it. An item table hides its price columns until something is priced. Saying so on the column is what stops
the three parallel lists of titles, widths and cells from drifting apart, which is the usual way such a table
breaks: get them out of step and OpenPDF rejects the whole table rather than the one bad row.

### Column widths

| Strategy               | Behaviour                                                     | Use for                                              |
| ---------------------- | ------------------------------------------------------------- | ---------------------------------------------------- |
| `ColumnWidth.Auto`     | Measured against its widest cell in the font it will be drawn in | Quantities, prices, dates, codes, row numbers — anything that must not wrap |
| `ColumnWidth.Flexible` | Takes what is left; several share the remainder evenly        | Prose, which is the thing that *should* wrap          |
| `ColumnWidth.Weight`   | A fixed share of the table                                    | Layouts about geometry rather than content — a 45/55 information grid, a 50/50 pane split |

Weighted columns are resolved first, `Auto` columns are then measured, and `Flexible` columns divide whatever
remains — never shrinking below `PdfTheme.minFlexibleColumnPoints`.

## Beyond tables

`document { }` and every nested block scope offer `paragraph`, `spacer`, `pageBreak`, `table`, `bullets`,
`picture`, `section`, `keepTogether`, and `banner`, `panes` and `totals`, which are ordinary tables underneath:

```kotlin
banner("Detailed Item List")          // a full-width centred title band

panes(60f, 40f) {                     // side-by-side columns of blocks
    pane { paragraph("Notes about the payment.") }
    pane {
        totals(align = Align.End) {
            line("Total Value:", "1.234,56 €")
            line("Payable:", null)                       // absent, so it does not appear
            line("Balance:", "0,00 €", emphasised = true)
        }
    }
}
```

Note `line("Payable:", null)`. Throughout the DSL a null or empty string means "this does not apply", and the
block is simply not emitted — so a figure that should not appear is expressed by passing the value you already
have rather than by an `if` around the call. Conditional content is otherwise just Kotlin: a section that does
not apply is an `if` that adds nothing, with no parallel list of widths or headers to keep in step.

## Styling

A `DocumentSpec` says "this run is bold", never "this run is 8pt Helvetica bold". Everything it leaves unsaid
lives in `PdfTheme`, so a house style is a value you pass rather than a hundred literals spread through the
code:

```kotlin
val theme = PdfTheme(
    defaultSizePoints = TextStyle.SMALL,
    page = PageGeometry(marginStart = 40f, marginEnd = 40f),
)
OpenPdfRenderer(theme).render(spec, out)
```

### Fonts and non-Latin text

`PdfFontFamily` defaults to Helvetica, one of the five standard PDF families that every reader already has —
nothing is shipped and nothing is embedded, and the file stays small. **The standard families cover Latin-1 and
nothing else,** and what OpenPDF does about that is worth knowing, because it does not fail loudly. On meeting
a character above U+00FF it quietly substitutes a Liberation Sans bundled in its own jar. Greek, Cyrillic and
Hebrew therefore survive — minus any bold or italic you asked for, since the substitution path skips OpenPDF's
emphasis simulation. Persian, Arabic and CJK do not survive at all: that font has no glyphs for them, so the
text is simply missing from the page. Those scripts need an embedded font:

```kotlin
val bytes = javaClass.getResourceAsStream("/fonts/Vazirmatn-Regular.ttf")!!.readBytes()
val theme = PdfTheme(fontFamily = PdfFontFamily.embedded(bytes))
```

`embedded` takes optional `bold`, `italic` and `boldItalic` faces too. Supply them if you have them: given only
a regular face, docdsl asks OpenPDF to synthesise the emphasis, which strokes an outline around the regular
glyphs rather than using the heavier shapes a designer drew. Where a real face *is* supplied, the synthetic
stroke is suppressed so the two do not compound. There is a filesystem-path overload, and `PdfFontFamily.of`
accepts fonts you have constructed with OpenPDF yourself.

## Adopting it gradually

An application that already draws PDFs with OpenPDF does not have to convert everything at once.
`renderBody` renders a document's body into a `Document` somebody else opened, and leaves the page furniture
alone:

```kotlin
class InspectionPdfGenerator : PdfGenerator<Inspection>() {
    override fun generate(document: Document, entity: Inspection, user: User) {
        val theme = PdfTheme(
            page = PageGeometry(
                widthPoints = document.pageSize.width,
                heightPoints = document.pageSize.height,
                marginStart = document.leftMargin(),
                marginEnd = document.rightMargin(),
            ),
        )
        OpenPdfRenderer(theme).renderBody(specFor(entity), into = document)
    }
}
```

The existing logo, company block, title band and footer keep working, because they are still drawn by the code
that always drew them; only the body is described. One document can move while the other nine do not, which is
what makes the migration incremental rather than all-or-nothing. Build the theme from the host document's own
page size and margins, as above — pass the wrong width and tables are measured against a page they are not on.

## Modules

| Module           | Contents                                   | Dependencies                    |
| ---------------- | ------------------------------------------ | ------------------------------- |
| `docdsl-core`    | The document model and the builder DSL     | The Kotlin standard library only |
| `docdsl-openpdf` | Renders a `DocumentSpec` to PDF            | `docdsl-core`, OpenPDF          |

The split is not ceremony. Keeping the model free of any renderer is what allows a spreadsheet or HTML renderer
to consume the same descriptions later, and it keeps `docdsl-core`'s licensing free of any renderer's terms.

## Limitations

- **No colspan or rowspan**, by design. Spans are how a table model acquires most of its complexity, and the
  documents this was built for do not need them: a cell that wants to look merged holds a nested table.
- **Only PDF renders today.** The module layout anticipates others; none exists yet.
- **Text outside Latin-1 needs an embedded font.** See above. The default font cannot draw it.
- **The API is not stable below 1.0.0.**

## Licence

docdsl is licensed under the [Apache License 2.0](LICENSE).

`docdsl-core` depends on nothing but the Kotlin standard library, which is Apache-2.0 as well.
`docdsl-openpdf` depends on
[OpenPDF](https://github.com/LibrePDF/OpenPDF), which is licensed `MPL-2.0 OR LGPL-2.1+`; docdsl exercises the
MPL-2.0 option and uses OpenPDF unmodified, so no file in this project is Covered Software under the MPL. A
consumer of `docdsl-openpdf` takes on OpenPDF's terms exactly as it would by depending on OpenPDF directly.
See [NOTICE](NOTICE) for the full attribution.
