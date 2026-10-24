import { formatNumber, formatTime, type AssetLink, type DocumentAsset, type DocumentProfile } from "../api";
import { href } from "../router";
import { KindIcon } from "./KindIcon";
import "./documents.css";

const languages = (() => {
  try {
    return new Intl.DisplayNames(["en-GB"], { type: "language" });
  } catch {
    return null;
  }
})();

function languageName(code: string | null) {
  if (!code) return "Not clear";
  return languages?.of(code) ?? code;
}

function size(bytes: number | null) {
  if (bytes == null) return "–";
  if (bytes < 1024) return `${bytes} bytes`;
  if (bytes < 1024 * 1024) return `${Math.ceil(bytes / 1024)} KB`;
  return `${(bytes / 1048576).toFixed(1)} MB`;
}

function pagesLabel(d: DocumentProfile) {
  const f = (d.format ?? "").toLowerCase();
  if (["xlsx", "xlsm", "xls", "ods"].includes(f)) return "Sheets";
  if (["pptx", "pptm", "ppt", "odp"].includes(f)) return "Slides";
  return "Pages";
}

/** The asset this one sits inside: a sheet's workbook, an attachment's message. */
export function PartOf({ parent, kind }: { parent: AssetLink; kind: string }) {
  const what = kind === "sheet" ? "A sheet in" : "Attached to";
  return (
    <p className="meta">
      {what}{" "}
      <a href={href.asset(parent.urn)}>
        <KindIcon kind={parent.kind} name={parent.name} />
        <span className="mono">{parent.name}</span>
      </a>
    </p>
  );
}

/** What reading a document found: its problem if any, mail headers, properties and parts. */
export function DocumentDetails({ asset }: { asset: DocumentAsset }) {
  const d = asset.document;
  if (!d) {
    return (
      <section aria-labelledby="doc-h" className="panel">
        <h2 id="doc-h">Document</h2>
        <p>
          {asset.description ??
            "Kilnmark has not read this document yet, or does not read this kind of file. Scan its source to read it."}
        </p>
      </section>
    );
  }
  const parts = asset.parts ?? [];
  const sheets = parts.filter((p) => p.kind === "sheet");
  const attachments = parts.filter((p) => p.kind !== "sheet");
  return (
    <>
      {!d.readable && (
        <div className="doc-problem" role="note">
          <strong>Not readable.</strong> Kilnmark could not read this document: {d.problem}
        </div>
      )}
      {d.mail && (
        <section aria-labelledby="mail-h" className="panel">
          <h2 id="mail-h">Message</h2>
          <dl className="mail-headers">
            <dt>From</dt>
            <dd>{d.mail.from ?? "–"}</dd>
            <dt>To</dt>
            <dd>{d.mail.to ?? "–"}</dd>
            {d.mail.cc && (
              <>
                <dt>Cc</dt>
                <dd>{d.mail.cc}</dd>
              </>
            )}
            <dt>Date</dt>
            <dd>{formatTime(d.mail.date)}</dd>
            <dt>Subject</dt>
            <dd>{d.mail.subject ?? "–"}</dd>
          </dl>
        </section>
      )}
      <dl className="facts">
        <div>
          <dt>Type</dt>
          <dd>
            <span className="mono">{(d.format ?? "–").toUpperCase()}</span>
            {d.contentType && <span className="muted small doc-ctype">{d.contentType}</span>}
          </dd>
        </div>
        <div>
          <dt>Size</dt>
          <dd>{size(d.size)}</dd>
        </div>
        {d.pages != null && (
          <div>
            <dt>{pagesLabel(d)}</dt>
            <dd>{formatNumber(d.pages)}</dd>
          </div>
        )}
        {d.readable && (
          <>
            <div>
              <dt>Words</dt>
              <dd>
                {formatNumber(d.words)}
                {d.truncated && <span className="muted"> (first part only)</span>}
              </dd>
            </div>
            <div>
              <dt>Language</dt>
              <dd>{languageName(d.language)}</dd>
            </div>
          </>
        )}
        <div>
          <dt>Read</dt>
          <dd>{formatTime(d.readAt)}</dd>
        </div>
      </dl>
      <dl className="facts">
        {!d.mail && (
          <div>
            <dt>Title</dt>
            <dd>{d.title ?? "–"}</dd>
          </div>
        )}
        {!d.mail && (
          <div>
            <dt>Author</dt>
            <dd>{d.author ?? "–"}</dd>
          </div>
        )}
        {!d.mail && (
          <div>
            <dt>Created</dt>
            <dd>{formatTime(d.created)}</dd>
          </div>
        )}
        {d.modified && (
          <div>
            <dt>Modified</dt>
            <dd>{formatTime(d.modified)}</dd>
          </div>
        )}
        {d.owner && (
          <div>
            <dt>Owner</dt>
            <dd>{d.owner}</dd>
          </div>
        )}
        {Object.keys(d.tags).length > 0 && (
          <div>
            <dt>Tags</dt>
            <dd>
              {Object.entries(d.tags).map(([k, v]) => (
                <span key={k} className="tag">
                  {k}: {v}
                </span>
              ))}
            </dd>
          </div>
        )}
        <div>
          <dt>Text kept</dt>
          <dd>
            {d.textKept
              ? "Yes, for search and reading"
              : d.textOnDemand
                ? "No: stewards can have it read from the source"
                : "No: this source keeps only properties"}
          </dd>
        </div>
      </dl>
      {d.notes.length > 0 && (
        <ul className="plain-list small">
          {d.notes.map((n, i) => (
            <li key={`note-${i}`}>{n}</li>
          ))}
        </ul>
      )}
      {sheets.length > 0 && <PartList title="Sheets in this workbook" id="sheets-h" parts={sheets} />}
      {attachments.length > 0 && <PartList title="Attachments" id="attachments-h" parts={attachments} />}
    </>
  );
}

function partStatus(p: AssetLink) {
  if (p.removed) return "no longer in the source";
  if (p.kind === "sheet") return p.readable ? "read as a table" : (p.description ?? "not read as a table");
  return p.readable ? "readable" : "not readable";
}

function PartList({ title, id, parts }: { title: string; id: string; parts: AssetLink[] }) {
  return (
    <section aria-labelledby={id} className="panel">
      <h2 id={id}>{title}</h2>
      <ul className="parts">
        {parts.map((p) => (
          <li key={p.urn}>
            <a href={href.asset(p.urn)}>
              <KindIcon kind={p.kind} name={p.name} />
              <span className="mono">{p.name}</span>
            </a>{" "}
            <span className="muted small">{partStatus(p)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
