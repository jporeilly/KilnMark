import { useEffect, useState } from "react";
import { Card, EmptyState, PageHeader, StatusChip } from "../components/ui";
import { Icon } from "../components/icons";
import { aiApi, type AskResult, type HybridHit, type QueryPlan, type SearchHitWithSnippet, plural } from "../api";
import { go, href } from "../router";
import { useTitle } from "../components/useTitle";
import { KindIcon } from "../components/KindIcon";
import { SnippetText } from "../components/SnippetText";

const MATCHED: Record<string, string> = { words: "words", meaning: "meaning", filters: "the question's filters" };

/**
 * Search results, by words and (with an embedding model) by meaning. In "ask" mode the query is a
 * question: a model turns it into a search, and the page shows exactly what was searched.
 */
export function SearchPage({ q, ask }: { q: string; ask: boolean }) {
  useTitle(q ? `${ask ? "Ask" : "Search"}: ${q}` : "Search");
  const [hits, setHits] = useState<HybridHit[] | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [answer, setAnswer] = useState<AskResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [meaning, setMeaning] = useState(true);

  // Say "and meaning" only when an embedding model is set.
  useEffect(() => {
    aiApi
      .status()
      .then((s) => setMeaning(s.searchByMeaning))
      .catch(() => setMeaning(false));
  }, []);

  useEffect(() => {
    if (!q) return;
    let live = true;
    setHits(null);
    setNote(null);
    setAnswer(null);
    setError(null);
    const work = ask
      ? aiApi.ask(q).then((r) => {
          if (!live) return;
          setAnswer(r);
          setHits(r.hits);
          setNote(r.searchNote);
        })
      : aiApi.search(q).then((r) => {
          if (!live) return;
          setHits(r.hits);
          setNote(r.note);
        });
    work.catch((e: Error) => live && setError(e.message));
    return () => {
      live = false;
    };
  }, [q, ask]);

  return (
    <>
      <PageHeader title={q ? <>{ask ? "Answers for" : "Results for"} “{q}”</> : "Search"} />
      <div className="search-mode" role="group" aria-label="How to search">
        <button type="button" aria-pressed={!ask} onClick={() => go(href.search(q))}>
          {meaning ? "Search names and meaning" : "Search names"}
        </button>
        <button type="button" aria-pressed={ask} onClick={() => go(href.ask(q))}>
          Ask a question
        </button>
      </div>
      {!q && (
        <p>
          {ask
            ? "Type a question in the search bar above, for example: which tables hold customer email addresses?"
            : "Type in the search bar above: a table or file name, a column name, or words from a description or a document."}
        </p>
      )}
      {answer && <Interpreted answer={answer} />}
      <div role="status" className="result-count">
        {error ? "" : q && hits === null ? (ask ? "Working out your question…" : "Searching…") : hits ? `${plural(hits.length, "result")}` : ""}
      </div>
      {note && <p className="meta">{note}</p>}
      {error && <p role="alert" className="error">{error}</p>}
      {hits?.length === 0 && (
        <Card>
          <EmptyState icon={<Icon name="search" size={22} />} title="Nothing matched">
            Try part of a name, or check the source has been <a href={href.sources()}>scanned</a>.
          </EmptyState>
        </Card>
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
                <StatusChip tone="sky">
                  <KindIcon kind={h.kind} name={h.name} />
                  {h.kind}
                </StatusChip>{" "}
                in <strong>{h.source}</strong>
                {h.rowCount != null && <> · {plural(h.rowCount, "row")}</>}
                {h.matchedBy?.length > 0 && <> · Matched by {h.matchedBy.map((m) => MATCHED[m] ?? m).join(" and ")}</>}
              </p>
              {h.description && <p>{h.description}</p>}
              {(h as SearchHitWithSnippet).snippet && (
                <p>
                  <span className="visually-hidden">From its text: </span>
                  <SnippetText snippet={(h as SearchHitWithSnippet).snippet!} />
                </p>
              )}
              {h.matchedFields.length > 0 && (
                <p className="meta">
                  Matching columns:{" "}
                  {h.matchedFields.slice(0, 6).map((f, i) => (
                    <span key={f}>
                      {i > 0 && " "}
                      <span className="km-code">{f}</span>
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

/** What the question was turned into, so a person can check it. */
function Interpreted({ answer }: { answer: AskResult }) {
  const plan: QueryPlan | null = answer.plan;
  return (
    <section aria-labelledby="plan-h" className="panel plan">
      <h2 id="plan-h">How your question was searched</h2>
      {plan ? (
        <>
          <dl className="plan-list">
            <div>
              <dt>Words</dt>
              <dd>{plan.text ? <span className="mono">{plan.text}</span> : "None"}</dd>
            </div>
            <div>
              <dt>Kinds</dt>
              <dd>{plan.kinds.length ? plan.kinds.join(", ") : "Any"}</dd>
            </div>
            <div>
              <dt>Sources</dt>
              <dd>{plan.sources.length ? plan.sources.join(", ") : "Any"}</dd>
            </div>
            <div>
              <dt>Must have fields</dt>
              <dd>{plan.fields.length ? plan.fields.map((f) => <span key={f} className="km-code">{f}</span>) : "Any"}</dd>
            </div>
          </dl>
          {plan.ignored.length > 0 && (
            <p className="meta">Not used: {plan.ignored.join("; ")}.</p>
          )}
          <p className="hint">Interpreted by model {answer.interpretedBy}. AI can misread a question: check the search above.</p>
        </>
      ) : (
        <p>{answer.note}</p>
      )}
    </section>
  );
}
