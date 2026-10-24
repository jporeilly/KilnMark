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
| `apache-mime4j-core`, `-dom` 0.8.12 (the version the Quarkus platform pins for the server; the plug-ins and their tests are pinned to it too, so tests read mail with what ships) | Apache-2.0 | 0.46 MB |
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
`XSSFReader` for the parts and StAX pulling one row at a time, so memory holds a row (at most
16,384 cells, Excel's own limit; a wider row stops the sheet) plus the shared strings, which are
read through a stream that stops at `max-part-mb`. Legacy .xls is read whole with HSSF, which is
why legacy files have their own size limit. Sheet reads take one of the document readers; the
document time limit applies to progress (every 1,024 rows), so a large sheet may take as long as it
needs while a stalled one stops.

Sheet names and attachment names come from inside files: control and formatting characters (NUL,
U+202E and the like) become `_` and they are cut at 200 characters, so a name can neither break the
database nor disguise a file's type.

**Asset paths.** For files, `%` and `#` in a path are escaped (`%25`, `%23`) in the asset path and
URN, so `#` only ever separates a file from something inside it: a sheet (`book.xlsx#Data`) or an
attachment (`message.eml#attachments/1/report.pdf`). A file named `book.xlsx#Data` becomes
`book.xlsx%23Data` and cannot pass for the sheet. Assets keep the real path in their `file` hint.

**Language** is found by our own detector (`TextStats`, 200 lines): text mostly in one language's
own script (Greek, Hebrew, Arabic, Thai, Hangul, kana, Han, Devanagari, Armenian, Georgian) is that
language; otherwise the language whose common short words (articles, prepositions) make up the
largest share, among sixteen European languages (Catalan among them) and Turkish. Arabic script is
Arabic, Persian or Urdu only when letters only one of them uses say so; Devanagari is Hindi, Marathi
or Nepali only when their common words do. It says nothing ("not clear") otherwise, and for lists,
tables of figures or a few words.

**Chunks.** Text is split into chunks of about 1,500 characters (`kilnmark.documents.chunk-characters`)
along paragraphs; a longer paragraph is cut after a sentence or at a space; each chunk repeats the
last 150 characters (`chunk-overlap`) of the one before, from a word start. Each chunk keeps its
page (or slide, or sheet) and its character offsets in the extracted text. They are stored in
`document_chunk` (migration V7), replaced whenever the document is read again, deleted with the
asset, and searched through a `tsvector` (`simple` configuration, like asset names) ranked below
name and description matches. A search ranks the 200 documents with the most matching chunks
(counted through the index; ties by name, never by age), and makes a snippet only from each one's
best chunk. A source with `keep_text=false` keeps none: its
documents' profiles are still worked out from the text, which is then dropped, and text stored
before is deleted at the next scan. Its text is not shown either, unless the source also has
`read_text_on_demand=true`: then stewards (not viewers, until sources have their own access rules)
can read it on the asset page. The server reads the document from the source for that request, on
a document reader of its own (one, apart from the scans' readers), with the same limits, at most six
documents a minute per person, and holds the text in memory for five minutes at most (four
documents at most), never on disk.

**Failures that say nothing about the document** (every reader busy, the time limit, a stopped scan,
the source not reachable, a workbook's sheets that could not be listed) keep what an earlier scan
stored: profile, text, sheets and attachments stay, with a note saying the last scan could not read
the document again. Other failures (too large, damaged, protected, a zip bomb) replace it: the
document is recorded as not readable and its text is deleted.

## Safety

Documents are untrusted input. Every limit below is a server setting (`kilnmark.documents.*`):

- **Size.** Documents over 100 MB (`max-file-mb`), legacy Office files over 30 MB
  (`max-legacy-file-mb`, read whole into memory) are not read. S3 objects are streamed into a
  temporary folder only the server's user can read (`kilnmark-documents-<process>-*` under
  `java.io.tmpdir`; folders left by a server that is no longer running are deleted at start),
  stopping at the limit. A copy is deleted when its read ends, except workbooks: a scan needs one
  to list its sheets, to read each sheet and to read its text, so that copy is shared and deleted
  when the scan ends or after ten minutes unused (at most 2 GB of such copies are kept). POI's temporary files go to the
  same folder; PDFBox's scratch file (past 64 MB of a PDF's working memory) goes to `java.io.tmpdir`,
  which Tika does not let us change.
- **Office parts.** Every document, and every file attached to or embedded in one, is opened with
  Commons Compress (the ZIP reader POI uses, which finds the archive from its end) before a parser
  sees it. Whether it is an archive is decided only by whether it opens, never by its name, first
  bytes or declared type, so a preamble before the archive does not hide it. An archive is refused
  when two entries share a name (ignoring case), when an entry's local header does not match the
  central directory, when entries overlap, or when it has more than 10,000 entries. Every entry is
  expanded and its real size counted; a file where one expands past 64 MB (`max-part-mb`) is not
  read. Parsers find parts by relationships and content types, not by paths, so in Office Open XML
  an entry is exempt only when both agree that parsers stream it: a worksheet or slide that the
  main part's relationships name as one, a Word document's main part named by the package's
  relationships, or an image named only as an image, each with the matching content type, and no
  relationship of another kind to it. Content types and relationships are read with a hardened
  XML reader and size caps; when either cannot be read exactly (encoded or duplicate names,
  unresolvable targets), nothing is exempt. OpenDocument and EPUB have every entry counted. Formats
  that are always archives (Office Open XML, OpenDocument, EPUB) are refused when they do not
  open.
- **Text.** At most 1,000,000 characters (`max-characters`) per document, attachments included;
  the collector stops the parser when the budget is spent.
- **Time.** Each document is read on one of 2 (`threads`) threads of its own and abandoned after
  60 seconds (`timeout-seconds`); waiting for a free thread is not timed, so healthy documents queue
  rather than fail. The abandoned read is asked to stop: the text collector checks a flag and ends
  the parse the next time the parser reports text or an element (the parsers' own streams do not
  check it). It keeps its thread until it really ends, so stalled reads never take more than
  `threads` processors; when every thread is held by an abandoned read, the next documents fail at
  once, saying so, and keep what earlier scans stored.
- **Depth.** Documents inside documents are read three levels deep (`max-depth`), and at most 100 of
  them per document (images and other files that are not read do not count); the rest are noted.
- **Zip bombs.** Office files are ZIP archives. POI refuses parts that expand more than 100 times or
  past 1 GiB. Tika's OOXML parser turns POI's ratio check off when its class loads (Tika relies on
  checks we do not use), so `DocumentRuntime` loads it first and turns the check back on; a part
  Tika reads around because POI refused it makes the whole document not readable. ODF and EPUB are
  read with `java.util.zip`: the character budget and the time limit bound them.
- **XML** is parsed by Tika's and POI's hardened readers: no external entities, no network, and
  entity expansion is limited (POI refuses a DOCTYPE in Office parts outright). HTML's DOCTYPE with
  an internal subset is dropped before jsoup reads the page, so its declarations are not text. The
  tests check every XML-based format against a file-reading entity and a network listener.
- **Logs.** POI logs damaged files at FATAL with a stack trace; the server turns POI's log off
  (`quarkus.log.category."org.apache.poi".level=OFF`), since each document already reports its
  reason.
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
- From S3 a workbook is fetched once per scan (its copy is found again by size, time and ETag), and
  deleted when the scan ends, or after ten minutes unused.
- An abandoned read keeps its thread until the parser notices; a parser stuck in a loop that never
  produces text holds one of the `threads` slots until the server restarts, and when all are held,
  documents are not read until then.
- With 64 MB of shared strings, POI's read-only table can need a few hundred MB of heap; lower
  `max-part-mb` on small servers.
- Paths of files whose names hold `%` or `#` changed URN with this record.

- `MetadataEvent.Kind` gained `DOCUMENT`; `AssetInfo.readable` now also means "read as text" for
  documents.
- Reading the text of a `keep_text=false` source on demand re-reads the document from the source, for
  each person, every five minutes at most.
