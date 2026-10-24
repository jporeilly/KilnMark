import { lazy, Suspense, useEffect, useState, type FormEvent } from "react";
import { go, href } from "../router";
import { browseApi, type Diagram, type DiagramScope, type Relationship } from "./browseApi";
import { RelationshipTable } from "./RelationshipTable";

const SchemaDiagram = lazy(() => import("./SchemaDiagram"));

/**
 * A diagram of a container, or of one asset and its neighbours, with the same relationships as a
 * table beneath it for keyboard and screen-reader users, and for stewards' decisions.
 */
export function DiagramSection({ scope, title }: { scope: DiagramScope; title: string }) {
  const [diagram, setDiagram] = useState<Diagram | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState(scope.kind === "container" ? scope.q ?? "" : "");
  const [applied, setApplied] = useState(filter);
  const [depth, setDepth] = useState<1 | 2>(scope.kind === "asset" ? scope.depth : 1);
  const key = scope.kind === "container" ? `${scope.source}\u0000${scope.container}` : scope.urn;

  useEffect(() => {
    setFilter("");
    setApplied("");
  }, [key]);

  useEffect(() => {
    let live = true;
    setError(null);
    const s: DiagramScope = scope.kind === "container" ? { ...scope, q: applied } : { ...scope, depth };
    browseApi
      .diagram(s)
      .then((d) => live && setDiagram(d))
      .catch((e: Error) => live && setError(e.message));
    return () => {
      live = false;
    };
  }, [key, applied, depth]);

  const onChange = (updated: Relationship) =>
    setDiagram((d) => d && { ...d, relationships: d.relationships.map((r) => (r.id === updated.id ? updated : r)) });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setApplied(filter.trim());
  };

  const own = diagram?.tables.filter((t) => !t.external) ?? [];
  const centre = scope.kind === "asset" ? scope.urn : undefined;

  return (
    <section aria-labelledby="diagram-h" className="panel">
      <h2 id="diagram-h">{title}</h2>
      <div className="dg-controls">
        {scope.kind === "container" && (
          <form className="inline-form" onSubmit={submit} role="search" aria-label="Tables in the diagram">
            <div className="field">
              <label htmlFor="dg-filter">Show tables whose name contains</label>
              <input id="dg-filter" type="search" value={filter} onChange={(e) => setFilter(e.target.value)} />
            </div>
            <button type="submit">Show</button>
          </form>
        )}
        {own.length > 0 && (
          <div className="field">
            <label htmlFor="dg-centre">Centre on a table and its neighbours</label>
            <select
              id="dg-centre"
              value=""
              onChange={(e) => {
                if (e.target.value) go(href.neighbours(e.target.value));
              }}
            >
              <option value="">Choose a table</option>
              {own.map((t) => (
                <option key={t.urn} value={t.urn}>
                  {t.name}
                </option>
              ))}
            </select>
          </div>
        )}
        {scope.kind === "asset" && (
          <div className="field">
            <label htmlFor="dg-depth">How far to look</label>
            <select id="dg-depth" value={depth} onChange={(e) => setDepth(e.target.value === "2" ? 2 : 1)}>
              <option value="1">Directly related tables</option>
              <option value="2">Two steps away</option>
            </select>
          </div>
        )}
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {!diagram && !error && <p role="status">Loading the diagram…</p>}
      {diagram && (
        <>
          <p className="muted" role="status">
            {diagram.truncated
              ? `Showing ${diagram.tables.filter((t) => !t.external).length} of ${diagram.total} tables. ` +
                "Narrow them with the filter, or centre on one table to see it and its neighbours."
              : diagram.tables.length === 0
                ? applied
                  ? "No tables match the filter."
                  : "No tables with columns here."
                : `${diagram.tables.length} ${diagram.tables.length === 1 ? "table" : "tables"}, ` +
                  `${diagram.relationships.filter((r) => r.status !== "rejected").length} relationships. ` +
                  "Solid lines are declared or accepted; dashed lines are proposals."}
          </p>
          {diagram.relationshipsTruncated && (
            <p className="warning" role="status">
              These tables have more than {diagram.relationships.length.toLocaleString("en-GB")} relationships; only
              that many are drawn and listed. Narrow the tables with the filter, or centre on one table.
            </p>
          )}
          {diagram.tables.length > 0 && (
            <p>
              <a
                href="#rel-list-h"
                onClick={(e) => {
                  e.preventDefault();
                  document.getElementById("rel-list-h")?.focus();
                }}
              >
                Skip to the relationship table
              </a>
            </p>
          )}
          {diagram.tables.length > 0 && (
            <Suspense fallback={<p role="status">Loading the diagram…</p>}>
              <SchemaDiagram
                tables={diagram.tables}
                relationships={diagram.relationships}
                centre={centre}
                label={`${title}: tables and their relationships`}
              />
            </Suspense>
          )}
          <h3 id="rel-list-h" tabIndex={-1}>
            Relationships
          </h3>
          <RelationshipTable
            relationships={diagram.relationships}
            caption={`Relationships in ${title}`}
            perspective={centre}
            onChange={onChange}
          />
        </>
      )}
    </section>
  );
}
