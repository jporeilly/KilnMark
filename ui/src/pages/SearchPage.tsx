import { useEffect, useState } from "react";
import { api, formatNumber, type SearchHit } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";

export function SearchPage({ q }: { q: string }) {
  useTitle(q ? `Search: ${q}` : "Search");
  const [hits, setHits] = useState<SearchHit[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!q) return;
    let live = true;
    setHits(null);
    setError(null);
    api
      .search(q)
      .then((r) => live && setHits(r.hits))
      .catch((e: Error) => live && setError(e.message));
    return () => {
      live = false;
    };
  }, [q]);

  return (
    <>
      <h1 tabIndex={-1}>{q ? <>Results for “{q}”</> : "Search"}</h1>
      {!q && <p>Type in the search bar above: a table name, a column name, or words from a description.</p>}
      <div role="status" className="result-count">
        {error ? "" : q && hits === null ? "Searching…" : hits ? `${hits.length} ${hits.length === 1 ? "result" : "results"}` : ""}
      </div>
      {error && <p role="alert" className="error">{error}</p>}
      {hits?.length === 0 && (
        <p>
          Nothing matched. Try part of a name, or check the source has been <a href={href.sources()}>scanned</a>.
        </p>
      )}
      {hits && hits.length > 0 && (
        <ol className="results">
          {hits.map((h) => (
            <li key={h.urn} className="result">
              <h2>
                <a href={href.asset(h.urn)}>
                  <span className="muted mono">{h.container}.</span>
                  <span className="mono">{h.name}</span>
                </a>
              </h2>
              <p className="meta">
                <span className="tag">{h.kind}</span> in <strong>{h.source}</strong>
                {h.rowCount != null && <> · {formatNumber(h.rowCount)} rows</>}
              </p>
              {h.description && <p>{h.description}</p>}
              {h.matchedFields.length > 0 && (
                <p className="meta">
                  Matching columns:{" "}
                  {h.matchedFields.slice(0, 6).map((f, i) => (
                    <span key={f}>
                      {i > 0 && ", "}
                      <span className="mono">{f}</span>
                    </span>
                  ))}
                </p>
              )}
            </li>
          ))}
        </ol>
      )}
    </>
  );
}
