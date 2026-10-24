#!/usr/bin/env python3
"""Writes the reference lab's synthetic documents. Standard library only; run from anywhere:

    python3 lab/make-documents.py              # lab/files/share/documents and lab/files/bucket/documents
    python3 lab/make-documents.py --fixtures   # also the legacy Word test fixture (needs LibreOffice)

Everything here is made up: names, places, figures and addresses (example.com, .org, .net).
The output is the same on every run (fixed dates, no randomness), so the files can be committed.
"""
import email.policy
import io
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from email.message import EmailMessage
from pathlib import Path

LAB = Path(__file__).resolve().parent
ROOT = LAB.parent
FIXED = (2025, 4, 1, 9, 0, 0)  # zip entry times, so files do not change between runs


# ---------------------------------------------------------------- PDF

def pdf(title, author, created, pages):
    """A PDF with one page per list of paragraphs, in Helvetica, with title, author and date."""
    objects = []

    def add(body):
        objects.append(body)
        return len(objects)

    def esc(s):
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    font = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    pages_id = len(objects) + 1 + 2 * len(pages)  # after every page and content stream
    kids = []
    for paragraphs in pages:
        lines = []
        y = 720
        for i, p in enumerate(paragraphs):
            size = 16 if i == 0 else 11
            for line in wrap(p, 90 if i else 60):
                lines.append(f"BT /F1 {size} Tf 72 {y} Td ({esc(line)}) Tj ET")
                y -= size + 4
            y -= 14
        stream = "\n".join(lines).encode("cp1252")
        content = add(b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream")
        kids.append(add(("<< /Type /Page /Parent %d 0 R /MediaBox [0 0 612 792] /Contents %d 0 R "
                         "/Resources << /Font << /F1 %d 0 R >> >> >>" % (pages_id, content, font)).encode()))
    assert add(("<< /Type /Pages /Kids [%s] /Count %d >>" % (" ".join(f"{k} 0 R" for k in kids), len(kids)))
               .encode()) == pages_id
    catalog = add(b"<< /Type /Catalog /Pages %d 0 R >>" % pages_id)
    info = add(("<< /Title (%s) /Author (%s) /CreationDate (D:%s) /Producer (Kilnmark lab) >>"
                % (esc(title), esc(author), created)).encode("cp1252"))
    out = io.BytesIO()
    out.write(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for i, body in enumerate(objects, 1):
        offsets.append(out.tell())
        out.write(b"%d 0 obj\n" % i + body + b"\nendobj\n")
    xref = out.tell()
    out.write(b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1))
    for o in offsets:
        out.write(b"%010d 00000 n \n" % o)
    out.write(b"trailer\n<< /Size %d /Root %d 0 R /Info %d 0 R >>\nstartxref\n%d\n%%%%EOF\n"
              % (len(objects) + 1, catalog, info, xref))
    return out.getvalue()


def wrap(text, width):
    words, line, out = text.split(), "", []
    for w in words:
        if line and len(line) + 1 + len(w) > width:
            out.append(line)
            line = w
        else:
            line = (line + " " + w).strip()
    if line:
        out.append(line)
    return out


# ---------------------------------------------------------------- Office Open XML

def ooxml(parts):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, text in parts.items():
            info = zipfile.ZipInfo(name, FIXED)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, text)
    return out.getvalue()


def xml_escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def core(title, creator, created):
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" '
            'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" '
            'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">'
            f'<dc:title>{xml_escape(title)}</dc:title><dc:creator>{xml_escape(creator)}</dc:creator>'
            f'<dcterms:created xsi:type="dcterms:W3CDTF">{created}</dcterms:created></cp:coreProperties>')


RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
OFFICE_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
CORE_REL = "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"


def docx(title, creator, created, paragraphs):
    body = "".join(
        (f'<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>{xml_escape(p[2:])}</w:t></w:r></w:p>'
         if p.startswith("# ") else f'<w:p><w:r><w:t xml:space="preserve">{xml_escape(p)}</w:t></w:r></w:p>')
        for p in paragraphs)
    return ooxml({
        "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
            '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>',
        "_rels/.rels": f'<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="{RELS_NS}">'
            f'<Relationship Id="rId1" Type="{OFFICE_DOC}" Target="word/document.xml"/>'
            f'<Relationship Id="rId2" Type="{CORE_REL}" Target="docProps/core.xml"/></Relationships>',
        "word/document.xml": '<?xml version="1.0" encoding="UTF-8"?><w:document '
            'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>' + body + '</w:body></w:document>',
        "word/_rels/document.xml.rels": f'<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="{RELS_NS}"/>',
        "docProps/core.xml": core(title, creator, created),
    })


def col(n):
    s = ""
    n += 1
    while n:
        n, r = divmod(n - 1, 26)
        s = chr(65 + r) + s
    return s


def excel_date(y, m, d):
    import datetime
    return (datetime.date(y, m, d) - datetime.date(1899, 12, 30)).days


def sheet_xml(rows):
    """rows: lists of str, int, float, bool, ('date', y, m, d) or None."""
    out = []
    for r, row in enumerate(rows, 1):
        cells = []
        for c, v in enumerate(row):
            ref = f"{col(c)}{r}"
            if v is None:
                continue
            if isinstance(v, bool):
                cells.append(f'<c r="{ref}" t="b"><v>{int(v)}</v></c>')
            elif isinstance(v, (int, float)):
                cells.append(f'<c r="{ref}"><v>{v}</v></c>')
            elif isinstance(v, tuple):
                cells.append(f'<c r="{ref}" s="1"><v>{excel_date(*v[1:])}</v></c>')
            else:
                cells.append(f'<c r="{ref}" t="inlineStr"><is><t>{xml_escape(v)}</t></is></c>')
        out.append(f'<row r="{r}">{"".join(cells)}</row>')
    return ('<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
            f'<sheetData>{"".join(out)}</sheetData></worksheet>')


def xlsx(title, creator, created, sheets):
    parts = {}
    overrides = "".join(
        f'<Override PartName="/xl/worksheets/sheet{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        for i in range(1, len(sheets) + 1))
    parts["[Content_Types].xml"] = ('<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
        '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
        + overrides + '</Types>')
    parts["_rels/.rels"] = (f'<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="{RELS_NS}">'
        f'<Relationship Id="rId1" Type="{OFFICE_DOC}" Target="xl/workbook.xml"/>'
        f'<Relationship Id="rId2" Type="{CORE_REL}" Target="docProps/core.xml"/></Relationships>')
    parts["xl/workbook.xml"] = ('<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>'
        + "".join(f'<sheet name="{xml_escape(n)}" sheetId="{i}" r:id="rId{i}"/>' for i, (n, _) in enumerate(sheets, 1))
        + '</sheets></workbook>')
    rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    parts["xl/_rels/workbook.xml.rels"] = (f'<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="{RELS_NS}">'
        + "".join(f'<Relationship Id="rId{i}" Type="{rel}/worksheet" Target="worksheets/sheet{i}.xml"/>'
                  for i in range(1, len(sheets) + 1))
        + f'<Relationship Id="rId{len(sheets) + 1}" Type="{rel}/styles" Target="styles.xml"/></Relationships>')
    parts["xl/styles.xml"] = ('<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>'
        '<fills count="1"><fill><patternFill patternType="none"/></fill></fills>'
        '<borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs>'
        '<cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14" applyNumberFormat="1"/></cellXfs></styleSheet>')
    for i, (_, rows) in enumerate(sheets, 1):
        parts[f"xl/worksheets/sheet{i}.xml"] = sheet_xml(rows)
    parts["docProps/core.xml"] = core(title, creator, created)
    return ooxml(parts)


# ---------------------------------------------------------------- the lab's documents

POLICY = pdf("Remote working policy", "Harbour Logistics Policy Team", "20250303093000Z", [
    ["Remote working policy",
     "Staff at Harbour Logistics may work from home for up to three days a week. Each manager agrees the pattern "
     "with their team, and the warehouse rota always comes first.",
     "Home working is a privilege, not a right. It can be paused during the busy season from November to January."],
    ["Equipment and expenses",
     "The company lends every home worker a laptop and a headset. Broadband costs are not repaid.",
     "Keep company equipment away from children and pets, and report any damage to the IT desk within two working days."],
])

ONBOARDING = docx("Onboarding guide", "Harbour Logistics People Team", "2025-01-06T09:00:00Z", [
    "# Welcome to Harbour Logistics",
    "This guide covers your first week. Your buddy will meet you at reception at nine on Monday.",
    "On day one you get a safety briefing, your pass and a tour of the warehouse.",
    "# Who to ask",
    "Questions about pay go to the payroll team. Questions about rotas go to the shift lead on duty.",
])

BUDGET = xlsx("Depot budget 2025", "Harbour Logistics Finance", "2025-02-14T12:00:00Z", [
    ("Expenses", [["month", "depot", "category", "amount", "approved_on", "approved"]] + [
        [m, d, c, round(a, 2), ("date", 2025, mi, 28), mi % 3 != 0]
        for mi, m in enumerate(["2025-01", "2025-02", "2025-03", "2025-04", "2025-05", "2025-06"], 1)
        for d, c, a in [("North depot", "Fuel", 1200.5 + mi * 10), ("South depot", "Repairs", 830.25 + mi * 7),
                        ("North depot", "Packaging", 410 + mi * 3)]]),
    ("Headcount", [["depot", "team", "staff", "contractors"],
                   ["North depot", "Loading", 24, 3], ["North depot", "Office", 6, 0],
                   ["South depot", "Loading", 18, 5], ["South depot", "Office", 4, 1]]),
    ("Notes", [["These figures are a draft for the spring budget meeting."], [None],
               ["Amounts are in pounds, before tax. Repairs exclude the roof work, which has its own budget."]]),
])

HANDBOOK = """# Team handbook

Welcome to the planning team. This handbook explains how we plan deliveries and share the work.

## Planning week

Routes for the following week are fixed by Thursday noon. After that, changes go through the shift lead.

## Holidays

Book holidays at least two weeks ahead in the team calendar. No more than two planners may be away at once.

## Tools

Route plans live in the planning folder. Never email route plans outside the company.
"""


def eml():
    m = EmailMessage(policy=email.policy.SMTP)
    m["From"] = "Planning Desk <planning@example.com>"
    m["To"] = "North Depot Stores <stores@example.org>"
    m["Cc"] = "Finance Team <finance@example.net>"
    m["Subject"] = "Delivery schedule for May"
    m["Date"] = "Tue, 29 Apr 2025 08:45:00 +0100"
    m["Message-ID"] = "<may-schedule-2025@example.com>"
    m.set_content("Hello stores team,\n\nThe delivery schedule for May is attached. The first two weeks are "
                  "confirmed; the rest may still move.\n\nThe remote working policy is attached too, as asked.\n\n"
                  "Planning Desk\n")
    m.add_attachment("# May delivery schedule\n\n| Week | Depot | Loads |\n| --- | --- | --- |\n"
                     "| 1 | North | 42 |\n| 2 | North | 38 |\n| 3 | South | 35 |\n| 4 | South | 40 |\n",
                     subtype="markdown", filename="may-schedule.md")
    m.add_attachment(POLICY, maintype="application", subtype="pdf", filename="remote-working-policy.pdf")
    m.set_boundary("lab-boundary-may-2025")  # fixed, so the file is the same on every run
    return m.as_bytes()


FILES = {
    "remote-working-policy.pdf": POLICY,
    "onboarding-guide.docx": ONBOARDING,
    "depot-budget-2025.xlsx": BUDGET,
    "2025-04-delivery-schedule.eml": eml(),
    "team-handbook.md": HANDBOOK.encode("utf-8"),
}


def fixtures():
    """The legacy Word fixture for the tests: no library here writes .doc, so LibreOffice converts one."""
    soffice = shutil.which("soffice") or shutil.which("libreoffice")
    if not soffice:
        sys.exit("--fixtures needs LibreOffice (soffice) on the PATH")
    target = ROOT / "kiln-plugins/src/test/resources/documents/memo.doc"
    with tempfile.TemporaryDirectory() as tmp:
        html = Path(tmp) / "memo.html"
        html.write_text('<html><head><meta charset="utf-8"><title>Legacy memo on stationery orders</title>'
                        '<meta name="author" content="Kilnmark Test Writer"></head><body>'
                        "<p>This legacy Word memo describes the stationery order for the Harbour Street office.</p>"
                        "<p>Pencils, paper and toner cartridges are ordered every quarter from the approved "
                        "supplier.</p></body></html>", encoding="utf-8")
        subprocess.run([soffice, "--headless", "--convert-to", "doc:MS Word 97", "--outdir", tmp, str(html)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(Path(tmp) / "memo.doc", target)
    print("wrote", target.relative_to(ROOT))


def main():
    for where in ("share", "bucket"):
        folder = LAB / "files" / where / "documents"
        folder.mkdir(parents=True, exist_ok=True)
        for name, data in FILES.items():
            (folder / name).write_bytes(data)
            print("wrote", (folder / name).relative_to(ROOT))
    if "--fixtures" in sys.argv[1:]:
        fixtures()


if __name__ == "__main__":
    main()
