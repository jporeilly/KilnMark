import { useEffect, useState } from "react";
import { pipelinesApi, type ReadSql } from "../../api";

/** How long the YAML must stay still before the server is asked again. */
const WAIT_MS = 400;

/**
 * The SQL a read node sends to its database, as the server works it out from the YAML as it stands
 * (POST /api/v1/pipelines/read-sql): never rebuilt in the browser, so what is shown is what runs.
 * `sql` is the last answer, kept while the next is asked for so the box never empties and moves the
 * settings under it as someone types; undefined before the first, null when the server could not be
 * asked. `current` says whether it answers the YAML as it stands now.
 */
export function useReadSql(yaml: string | undefined, node: string): { sql: ReadSql | null | undefined; current: boolean } {
  const [state, setState] = useState<{ key: string; sql: ReadSql | null } | null>(null);
  const key = `${node}\n${yaml ?? ""}`;
  useEffect(() => {
    if (yaml == null) return;
    const ask = new AbortController();
    const timer = window.setTimeout(() => {
      pipelinesApi
        .readSql(yaml, node, ask.signal)
        .then((sql) => setState({ key, sql }))
        .catch(() => {
          if (!ask.signal.aborted) setState({ key, sql: null });
        });
    }, WAIT_MS);
    return () => {
      window.clearTimeout(timer);
      ask.abort();
    };
  }, [yaml, node, key]);
  return { sql: state ? state.sql : undefined, current: state?.key === key };
}

/**
 * A read node's SQL, read-only, under its Query box: what Kilnmark sends for the table, columns,
 * where and row limit chosen (or the query as it is sent), or why there is none yet.
 */
export function ReadSqlShown({ id, yaml, node, query }: { id: string; yaml: string | undefined; node: string; query: boolean }) {
  const { sql: shown, current } = useReadSql(yaml, node);
  if (yaml == null) return null;
  return (
    <div className="cv-field cv-field-wide" data-setting="sql-shown" aria-busy={!current || undefined}>
      <span className="cv-label" id={`${id}-label`}>
        SQL Kilnmark runs
      </span>
      {shown === undefined ? (
        <p className="small muted">Working out the SQL…</p>
      ) : shown === null ? (
        <p className="small muted">The SQL could not be worked out just now. It is worked out again as the pipeline changes.</p>
      ) : shown.sql ? (
        <pre className="mono cv-sql-shown" role="region" tabIndex={0} aria-labelledby={`${id}-label`} aria-describedby={`${id}-help`}>
          {shown.sql}
        </pre>
      ) : null}
      {shown && (
        <p className="hint" id={`${id}-help`}>
          {shown.sql
            ? [
                query
                  ? "Read-only: the query as Kilnmark sends it, on a read-only connection; a row limit wraps it."
                  : "Read-only: worked out by the server from the settings here and the table as last scanned, as a run sets the read up.",
                shown.values > 0 ? `Each ? is a value from Where, bound as the read runs (${shown.values}).` : null,
                shown.note,
              ]
                .filter(Boolean)
                .join(" ")
            : shown.note}
        </p>
      )}
    </div>
  );
}
