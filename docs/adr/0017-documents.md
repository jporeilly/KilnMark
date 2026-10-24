# 0017 Documents: Apache Tika's parsers, POI's streaming reader for sheets, text in chunks

Status: Accepted (24 October 2026)

## Decision

The `files` and `s3` connectors read the text of documents, not just their size and time:

| Format | Read by | Notes |
| --- | --- | --- |
| PDF | Tika's PDF parser (PDFBox 3) | Pages, title, author, dates. No OCR |
| Word .docx, .docm, .doc | Tika's OOXML (streaming) and OLE2 parsers (POI) | |
| Excel .xlsx, .xlsm, .xls | Tika for text; POI's streaming reader for sheets as tables | Each sheet is a page of text |
| PowerPoint .pptx, .pptm, .ppt | Tika's OOXML (streaming) and OLE2 parsers | Each slide is a page of text |
| OpenDocument .odt, .ods, .odp | Tika's OpenDocument parser | |
| EPUB | Tika's EPUB parser | In the OpenDocument module: no extra jar |
| RTF | Tika's RTF parser | |
| HTML, XHTML | Tika's jsoup parser | Scripts, styles and the head are dropped |
| XML | Tika's XML parser | Text content only |
| Mail .eml, Outlook .msg | Tika's RFC 822 parser (mime4j); POI's HSMF | Attachments become assets of their own |
| Plain text, Markdown | Our own parser (80 lines) | Encoding found as for CSV (ADR 0015) |

Anything else (images, archives, media) is recorded as before: size and time, not read.

**Libraries.** Tika's core and only the parser modules for these formats, with a parser built from
exactly the parsers listed (no automatic loading of every parser on the class path). Left out of
their dependencies: Bouncy Castle (8 MB; encrypted files and signed mail), the Access and PST
readers, PDFBox's command-line tools, JAXB, the full OOXML schemas (POI's lite schemas are
enough) and juniversalchardet (MPL, not on our licence list). The server's runtime gains:

| Jar | Licence | Size |
| --- | --- | --- |
| `tika-core` 3.3.3 | Apache-2.0 | 0.77 MB |
| `tika-parser-pdf`, `-microsoft`, `-mail`, `-miscoffice`, and the `-html`, `-xml`, `-text`, `-zip-commons`, `-mail-commons`, `-xmp-commons` modules they need, 3.3.3 | Apache-2.0 | 1.43 MB |
| `poi`, `poi-scratchpad`, `poi-ooxml` 5.5.1 | Apache-2.0 | 6.65 MB |
| `poi-ooxml-lite` 5.5.1 (the OOXML schemas POI uses) | Apache-2.0 | 5.72 MB |
| `xmlbeans` 5.3.0 | Apache-2.0 | 2.11 MB |
| `pdfbox`, `pdfbox-io`, `fontbox`, `xmpbox` 3.0.8; `jempbox` 1.8.17 | Apache-2.0 | 3.77 MB |
| `commons-math3` 3.6.1 (POI's formulas) | Apache-2.0 | 2.11 MB |
| `commons-compress`, `commons-io`, `commons-lang3`, `commons-collections4`, `commons-csv`, `commons-logging` | Apache-2.0 | 3.35 MB |
| `apache-mime4j-core`, `-dom` 0.8.12 (the version the Quarkus platform pins) | Apache-2.0 | 0.46 MB |
| `jsoup` 1.23.2 | MIT | 0.51 MB |
| `curvesapi` 1.08 | BSD-3-Clause | 0.11 MB |
| `SparseBitSet` 1.3 | Apache-2.0 | 0.02 MB |
| `log4j-api` 2.26.0, `log4j2-jboss-logmanager` 2.0.1 (POI logs through Log4j's API; this sends it to the server's log) | Apache-2.0 | 0.35 MB |

In all 27.4 MB. `./gradlew :server:checkLicense` passes.

**Sheets as tables.** Each sheet of a workbook whose first non-empty row is a header (two or more
names, all text, side by side) and that has rows under it becomes an asset of kind `sheet` inside
the workbook (`book.xlsx#Sheet1`), read into Arrow and profiled like any table. Columns are typed
when every sampled cell is: boolean, int64, float64, date or timestamp; otherwise text. Other
sheets are recorded with the reason they are not tables. .xlsx is read with POI's streaming API:
`XSSFReader` for the parts and StAX pulling one row at a time, so memory holds a row plus the shared
strings (refused above 128 MB). Legacy .xls is read whole with HSSF, which is why legacy files have
their own size limit.

**Language** is found by our own detector (`TextStats`, 200 lines): text mostly in one language's
own script (Greek, Hebrew, Arabic, Thai, Hangul, kana, Han, Devanagari, Armenian, Georgian) is that
language; otherwise the language whose common short words (articles, prepositions) make up the
largest share, among fifteen European languages and Turkish. It says nothing for lists, tables
of figures or a few words.

**Chunks.** Text is split into chunks of about 1,500 characters (`kilnmark.documents.chunk-characters`)
along paragraphs; a longer paragraph is cut after a sentence or at a space; each chunk repeats the
last 150 characters (`chunk-overlap`) of the one before, from a word start. Each chunk keeps its
page (or slide, or sheet) and its character offsets in the extracted text. They are stored in
`document_chunk` (migration V7), replaced whenever the document is read again, deleted with the
asset, and searched through a `tsvector` (`simple` configuration, like asset names) ranked below
name and description matches. A source with `keep_text=false` keeps none: its documents' profiles
are still worked out from the text, which is then dropped. Its text can still be read on the asset
page: the server reads the document from the source again for that request, with the same limits,
and holds it in memory for five minutes at most (four documents at most), never on disk.

## Safety

Documents are untrusted input. Every limit below is a server setting (`kilnmark.documents.*`):

- **Size.** Documents over 100 MB (`max-file-mb`), legacy Office files over 30 MB
  (`max-legacy-file-mb`, read whole into memory) are not read. S3 objects are streamed into a
  temporary folder only the server's user can read, stopping at the limit, and deleted after.
- **Text.** At most 1,000,000 characters (`max-characters`) per document, attachments included;
  the collector stops the parser when the budget is spent.
- **Time.** Each document is read on one of 2 (`threads`) threads of its own and abandoned after
  60 seconds (`timeout-seconds`): the scan carries on at once and the document is marked not
  readable. The abandoned read is asked to stop (its text collector and the parser's streams check
  a flag) and keeps its thread until it really ends, so stalled reads never take more than
  `threads` processors; if all are stalled, the next documents wait up to the timeout and are then
  marked not readable, saying so.
- **Depth.** Documents inside documents are read three levels deep (`max-depth`), and at most 100 of
  them per document; the rest are noted.
- **Zip bombs.** Office files are ZIP archives. POI refuses parts that expand more than 100 times or
  past 1 GiB. Tika's OOXML parser turns POI's ratio check off when its class loads (Tika relies on
  checks we do not use), so `DocumentRuntime` loads it first and turns the check back on; a part
  Tika reads around because POI refused it makes the whole document not readable. ODF and EPUB are
  read with `java.util.zip`: the character budget and the time limit bound them.
- **XML** is parsed by Tika's and POI's hardened readers: no DTDs, no external entities.
- **Nothing runs.** No OCR, no macros, no PDF actions, no scripts; nothing is fetched from a network;
  HTML is reduced to text. The API returns text only, and the web UI shows it as text: search
  highlights are positions, not markup.
- **Elements** nested more than 1,000 deep stop the read.

A document that cannot be read never fails a scan: it is recorded as not readable with a reason in
plain English (too large, protected with a password, damaged or not what its name says, a zip
bomb, too slow, ...).

## Why

- **Tika** reads every common office format through one interface, with the format-specific work done
  by POI and PDFBox, the libraries everyone else uses too; writing parsers is not our business.
  Tika 3.3 rather than 4.x: 4.x is a new major version that changes configuration; 3.3 is
  maintained.
- **Only the modules needed**, and a parser assembled from named classes: the standard package
  brings dozens of parsers (Access, PST, scientific formats) and dependencies (Bouncy Castle, MPL
  code) we do not want, and Tika's `DefaultParser` loads whatever is on the class path.
- **Our own plain-text parser and encoding rules**: Tika's text module detects encodings with
  juniversalchardet (MPL) or ICU heuristics. Ours matches CSV's (byte-order mark, declared charset,
  UTF-8, windows-1252), so a folder of files is read the same way.
- **Our own language detector**: Tika's small n-gram detector (0.2 MB) was tried first. It named a
  Spanish paragraph Catalan and a list of figures Norwegian, and never reported itself certain.
  Optimaize needs Guava (about 5 MB together), OpenNLP a separate model of about 10 MB, and Lingua
  ships models for every language in one large jar.
- **POI's streaming reader for sheets**: the usual `XSSFWorkbook` holds a whole workbook in memory
  (often ten times its size on disk).

## Left out, for later

- **OCR** (scanned PDFs, images): Tesseract is a native program, and slow. A later option, as a
  separate worker image or an AI step (round 3 can read images with a vision model).
- **Encrypted documents** (password-protected Office files and PDFs, S/MIME mail): they need Bouncy
  Castle (8 MB) and the password. Recorded as not readable, saying they are protected.
- **Mailboxes** (.pst, .ost, .mbox), **Access** (.mdb, .accdb), Apple iWork, WordPerfect, Visio,
  Publisher, Hangul .hwp: rare in the estates we target; each is a Tika module away.
- **Archives** (.zip, .tar.gz): not opened. A document inside an archive is not found.
- **Tables inside documents** (Word tables, PDF tables) are text, not table assets.
- **Embeddings**: round 3 (below).

## For round 3: embedding chunks

Chunks leave the reader as Arrow rows (`DocumentEvents.CHUNK_SCHEMA`: `asset_urn`, `sequence`,
`text`, `page`, `start_offset`, `end_offset`), and the server's document pipeline is
`read` (`DocumentInput`) then `store-text` (`ChunkRows` in `DocumentScan.read`). An embedding step
goes between the two: it takes the batches, adds a vector column from `text`, and passes them on;
`ChunkRows` and `DocumentStore.replaceChunks` then store the vector in a new `embedding vector(n)`
column on `document_chunk` (with the model name, as dimensions differ). Chunks are about 1,500
characters (roughly 350 to 400 tokens), within every common embedding model's input. The step must
honour the source's `ai_egress` setting, and a source with `keep_text=false` has no chunks to embed.
Re-embedding after a model change can read stored chunks (`GET /api/v1/documents/chunks`, or
`DocumentStore.chunks`) without reading the documents again.

## Cost

- 27.4 MB of jars, most of it POI (its schemas and formula library come along).
- Turning POI's zip-bomb check back on is JVM-wide: a genuine workbook that compresses more than
  100 to 1 (very repetitive XML) is refused, and any other POI use in the server gets the same check.
- From S3 a workbook is fetched once to list its sheets, once per sheet read and once for its text.
- An abandoned read keeps its thread until the parser notices; a parser stuck in a loop that never
  produces text nor reads its stream holds one of the `threads` slots until the server restarts.
- `MetadataEvent.Kind` gained `DOCUMENT`; `AssetInfo.readable` now also means "read as text" for
  documents.
- Reading the text of a `keep_text=false` source re-reads the document from the source, for each
  person, every five minutes at most.
