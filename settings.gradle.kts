rootProject.name = "docdsl"

//  docdsl-core    — the document model and the builder DSL. Pure Kotlin, zero dependencies, so it can be
//                   published under a permissive licence and consumed anywhere.
//  docdsl-openpdf — renders a document to PDF with openpdf. The only module that knows a PDF library exists.
//  docdsl-poi     — renders the same document to an .xlsx workbook with Apache POI. The sibling that proves
//                   the point: it was added without the document model changing, and without a single
//                   generator being told which medium it was being rendered to.
include(":docdsl-core")
include(":docdsl-openpdf")
include(":docdsl-poi")
