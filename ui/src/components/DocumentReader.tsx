import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from "react";
import { documentsApi, type DocumentPage, type FindResult } from "../api";
import { SnippetText } from "./SnippetText";
import "./documents.css";

/** Characters shown before "Show more" on a long page. */
const PREVIEW = 6000;

const KIND_NAME: Record<DocumentPage["pageKind"], string> = {
  page: "Page",
  slide: "Slide",
  sheet: "Sheet",
  part: "Part",
};

/** Plain text with every occurrence of `find` (any case) marked. Never markup. */
function marked(text: string, find: string): ReactNode[] {
  if (!find) return [text];
  const lower = text.toLowerCase();
  const needle = find.toLowerCase();
  const out: ReactNode[] = [];
  let at = 0;
  let i = lower.indexOf(needle);
  let n = 0;
  while (i >= 0 && n < 2000) {
    if (i > at) out.push(text.slice(at, i));
    out.push(<mark key={n++}>{text.slice(i, i + needle.length)}</mark>);
    at = i + needle.length;
    i = lower.indexOf(needle, at);
  }
  if (at < text.length) out.push(text.slice(at));
  return out;
}

/**
 * A document's text to read: page by page (PDF), slide by slide, sheet by sheet (workbooks) or in
 * parts, with find. The text is shown as text, so nothing in a document can run in the browser.
 */
export function DocumentReader({ urn, name }: { urn: string; name: string }) {
  const [page, setPage] = useState(1);
  const [doc, setDoc] = useState<DocumentPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [all, setAll] = useState(false);
  const [query, setQuery] = useState("");
  const [found, setFound] = useState<FindResult | null>(null);
  const [finding, setFinding] = useState(false);
  const [findError, setFindError] = useState<string | null>(null);

  useEffect(() => {
    setPage(1);
    setFound(null);
    setQuery("");
  }, [urn]);

  useEffect(() => {
    let live = true;
    setError(null);
    setAll(false);
    documentsApi
      .text(urn, page)
      .then((d) => live && setDoc(d))
      .catch((e: Error) => live && setError(e.message));
    return () => {
      live = false;
    };
  }, [urn, page]);

  const kind = doc ? KIND_NAME[doc.pageKind] : "Page";
  const label = (n: number) => {
    const l = doc?.pages[n - 1]?.label;
    return l ? `${kind} ${n}: ${l}` : `${kind} ${n}`;
  };
  const shown = doc && !all && doc.text.length > PREVIEW ? doc.text.slice(0, PREVIEW) : (doc?.text ?? "");
  const activeFind = found?.query ?? "";
  const body = useMemo(() => marked(shown, activeFind), [shown, activeFind]);

  const onFind = (e: FormEvent) => {
    e.preventDefault();
    const q = query.trim();
    if (q.length < 2) {
      setFindError("Type at least two characters to find.");
      return;
    }
    setFinding(true);
    setFindError(null);
    documentsApi
      .find(urn, q)
      .then((r) => {
        setFound(r);
        if (r.pages.length > 0 && !r.pages.some((p) => p.number === page)) setPage(r.pages[0].number);
      })
      .catch((err: Error) => setFindError(err.message))
      .finally(() => setFinding(false));
  };

  return (
    <section aria-labelledby="reader-h" className="panel">
      <h2 id="reader-h">Text</h2>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {doc && (
        <>
          {doc.extractedNow && (
            <p className="muted small">
              This source keeps no text in Kilnmark, so this was read from the source just now. It is not stored.
            </p>
          )}
          <form className="reader-bar" onSubmit={onFind} role="search" aria-label={`Find in ${name}`}>
            <label htmlFor="doc-find">Find in document</label>
            <input id="doc-find" type="search" value={query} onChange={(e) => setQuery(e.target.value)} />
            <button type="submit" disabled={finding}>
              {finding ? "Finding…" : "Find"}
            </button>
            {found && (
              <button
                type="button"
                onClick={() => {
                  setFound(null);
                  setQuery("");
                }}
              >
                Clear
              </button>
            )}
          </form>
          <div role="status" className="small">
            {findError ??
              (found
                ? found.total === 0
                  ? `“${found.query}” is not in this document.`
                  : `“${found.query}”: ${found.total} ${found.total === 1 ? "match" : "matches"} on ${found.pages.length} ${
                      found.pages.length === 1 ? kind.toLowerCase() : `${kind.toLowerCase()}s`
                    }`
                : "")}
          </div>
          {found && found.pages.length > 0 && (
            <ul className="find-results" aria-label="Where it was found">
              {found.pages.map((p) => (
                <li key={p.number}>
                  <button type="button" onClick={() => setPage(p.number)} aria-current={p.number === page ? "true" : undefined}>
                    <strong>{label(p.number)}</strong> ({p.count} {p.count === 1 ? "match" : "matches"}):{" "}
                    <SnippetText snippet={p.snippet} />
                  </button>
                </li>
              ))}
            </ul>
          )}
          {doc.pageCount > 1 && (
          <nav className="reader-bar" aria-label={`${kind}s of ${name}`}>
            <button type="button" onClick={() => setPage(page - 1)} disabled={page <= 1}>
              Previous {kind.toLowerCase()}
            </button>
            <label htmlFor="doc-page">{kind}</label>
            <select id="doc-page" value={page} onChange={(e) => setPage(Number(e.target.value))}>
              {doc.pages.map((p) => (
                <option key={p.number} value={p.number}>
                  {label(p.number)}
                </option>
              ))}
            </select>
            <span className="muted small">of {doc.pageCount}</span>
            <button type="button" onClick={() => setPage(page + 1)} disabled={page >= doc.pageCount}>
              Next {kind.toLowerCase()}
            </button>
          </nav>
          )}
          <div className="doc-text" role="region" aria-label={`${label(doc.page)} of ${name}`} tabIndex={0}>
            {doc.text ? body : <span className="muted">This {kind.toLowerCase()} has no text.</span>}
          </div>
          {doc.text.length > PREVIEW && (
            <p>
              <button type="button" aria-expanded={all} onClick={() => setAll(!all)}>
                {all ? "Show less" : `Show more (${Math.round((doc.text.length - PREVIEW) / 1000)}k more characters)`}
              </button>
            </p>
          )}
          {doc.truncated && (
            <p className="muted small">Kilnmark read only the first part of this document: the rest is not shown.</p>
          )}
        </>
      )}
      {!doc && !error && <p role="status">Loading the text…</p>}
    </section>
  );
}
