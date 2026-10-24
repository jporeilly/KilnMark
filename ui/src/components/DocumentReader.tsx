import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from "react";
import { documentsApi, type DocumentPage, type DocumentProfile, type FindResult } from "../api";
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

/** A pattern finding `find` in any case, on the text itself (lower-casing can change lengths). */
function pattern(find: string) {
  return new RegExp(find.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "giu");
}

/** Plain text with every occurrence of `find` (any case) marked. Never markup. */
function marked(text: string, find: string): ReactNode[] {
  if (!find) return [text];
  const out: ReactNode[] = [];
  let at = 0;
  let n = 0;
  for (const m of text.matchAll(pattern(find))) {
    if (n >= 2000 || m.index === undefined || m[0].length === 0) break;
    if (m.index > at) out.push(text.slice(at, m.index));
    out.push(<mark key={n++}>{m[0]}</mark>);
    at = m.index + m[0].length;
  }
  if (at < text.length) out.push(text.slice(at));
  return out;
}

/** Where `find` first occurs in `text`, in any case; -1 when it does not. */
function firstMatch(text: string, find: string) {
  const m = pattern(find).exec(text);
  return m ? m.index : -1;
}

/**
 * A document's text to read: page by page (PDF), slide by slide, sheet by sheet (workbooks) or in
 * parts, with find. The text is shown as text, so nothing in a document can run in the browser.
 */
export function DocumentReader({ urn, name, profile }: { urn: string; name: string; profile: DocumentProfile }) {
  if (!profile.textKept && !profile.textOnDemand) {
    return (
      <section aria-labelledby="reader-h" className="panel">
        <h2 id="reader-h">Text</h2>
        <p>
          Kilnmark keeps no text for documents in this source, so only their properties are shown. An administrator
          can turn on <span className="mono">read_text_on_demand</span> for the source to let stewards read the text
          from the source.
        </p>
      </section>
    );
  }
  return <Reader urn={urn} name={name} />;
}

function Reader({ urn, name }: { urn: string; name: string }) {
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
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
    setLoading(true);
    documentsApi
      .text(urn, page)
      .then((d) => live && setDoc(d))
      .catch((e: Error) => live && setError(e.message))
      .finally(() => live && setLoading(false));
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

  // a match past the preview: show the whole page, so the match can be seen
  useEffect(() => {
    if (doc && activeFind && !all && doc.text.length > PREVIEW && firstMatch(doc.text, activeFind) >= PREVIEW - activeFind.length) {
      setAll(true);
    }
  }, [doc, activeFind, all]);

  const go = (to: number) => {
    if (doc && to >= 1 && to <= doc.pageCount) setPage(to);
  };

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
            <button type="button" onClick={() => go(page - 1)} aria-disabled={page <= 1}>
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
            <button type="button" onClick={() => go(page + 1)} aria-disabled={page >= doc.pageCount}>
              Next {kind.toLowerCase()}
            </button>
          </nav>
          )}
          <p className="small muted" role="status" aria-live="polite">
            {loading ? `Loading ${kind.toLowerCase()} ${page}…` : `Showing ${label(doc.page)} of ${doc.pageCount}`}
          </p>
          <div className="doc-text" role="region" aria-busy={loading} aria-label={`${label(doc.page)} of ${name}`} tabIndex={0}>
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
