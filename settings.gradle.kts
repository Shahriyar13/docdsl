rootProject.name = "docdsl"

//  docdsl-core    — the document model and the builder DSL. Pure Kotlin, zero dependencies, so it can be
//                   published under a permissive licence and consumed anywhere.
//  docdsl-openpdf — renders a document to PDF with openpdf. The only module that knows a PDF library exists;
//                   a future HTML or spreadsheet renderer is a sibling, not a rewrite.
include(":docdsl-core")
include(":docdsl-openpdf")
