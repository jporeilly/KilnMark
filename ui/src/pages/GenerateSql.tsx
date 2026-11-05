import { useEffect, useId, useState } from "react";
import { api, type GeneratedSql, type SqlStatement, type TableDetail } from "../api";
import { Button } from "../components/ui";

/** The databases the generator writes for, as source kinds (ADR 0045). */
export const DIALECTS: { kind: string; label: string }[] = [
  { kind: "postgresql", label: "PostgreSQL" },
  { kind: "sqlserver", label: "SQL Server" },
  { kind: "mysql", label: "MySQL" },
  { kind: "mariadb", label: "MariaDB" },
  { kind: "oracle", label: "Oracle" },
];

const STATEMENTS: { id: SqlStatement; label: string }[] = [
  { id: "create-table", label: "CREATE TABLE for this table" },
  { id: "add-columns", label: "ALTER TABLE: add a column" },
  { id: "create-index", label: "CREATE INDEX" },
  { id: "drop-index", label: "DROP INDEX" },
  { id: "drop-table", label: "DROP TABLE" },
  { id: "truncate", label: "TRUNCATE TABLE" },
];

/** The kinds a new column may have, as Kilnmark names them, with what people read. */
export const KINDS: { kind: string; label: string }[] = [
  { kind: "text", label: "Text" },
  { kind: "int32", label: "Whole number" },
  { kind: "int64", label: "Big whole number" },
  { kind: "decimal", label: "Decimal" },
  { kind: "float64", label: "Floating point" },
  { kind: "boolean", label: "True or false" },
  { kind: "date", label: "Date" },
  { kind: "timestamp", label: "Date and time" },
  { kind: "timestamp_tz", label: "Date and time with zone" },
  { kind: "binary", label: "Bytes" },
];

/** A file name for the saved SQL: the table and the statement, in plain letters. */
export function sqlFileName(path: string, statement: string): string {
  return `${path}-${statement}`.replace(/[^A-Za-z0-9._-]+/g, "_") + ".sql";
}

/** The statements as one script, each ended with a semicolon. */
export function script(g: GeneratedSql): string {
  return g.sql.map((s) => `${s};`).join("\n\n") + "\n";
}

/**
 * The SQL generator (ADR 0045, D2): statements for this table in its database's dialect or
 * another's, written by Kilnmark as text, to copy or save. Nothing here runs a statement.
 */
export function GenerateSql({ source, sourceKind, detail }: { source: string; sourceKind: string; detail: TableDetail }) {
  const id = useId();
  const own = DIALECTS.some((d) => d.kind === sourceKind) ? sourceKind : "";
  const isTable = detail.kind === "table" || detail.kind === "partitioned table";
  const [statement, setStatement] = useState<SqlStatement>("create-table");
  const [dialect, setDialect] = useState(own);
  const [colName, setColName] = useState("");
  const [colKind, setColKind] = useState("text");
  const [colLength, setColLength] = useState("");
  const [colPrecision, setColPrecision] = useState("12");
  const [colScale, setColScale] = useState("2");
  const [colNullable, setColNullable] = useState(true);
  const [indexName, setIndexName] = useState("");
  const [indexColumns, setIndexColumns] = useState<string[]>([]);
  const [unique, setUnique] = useState(false);
  const [dropIndex, setDropIndex] = useState("");
  const [result, setResult] = useState<GeneratedSql | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const droppable = detail.indexes.filter((i) => !i.primaryKey);
  // An answer belongs to the choices it was made for: any change clears it, so Save never pairs
  // one statement's file name with another's SQL.
  useEffect(() => {
    setResult(null);
    setCopied(false);
  }, [statement, dialect, colName, colKind, colLength, colPrecision, colScale, colNullable, indexName, indexColumns, unique, dropIndex]);
  const whole = (v: string) => (v.trim() === "" ? null : Number(v));

  const generate = async () => {
    setBusy(true);
    setError(null);
    setResult(null);
    setCopied(false);
    try {
      setResult(
        await api.generateSql(source, {
          statement,
          asset: detail.path,
          dialect: dialect || null,
          columns:
            statement === "add-columns"
              ? [
                  {
                    name: colName,
                    kind: colKind,
                    length: colKind === "text" ? whole(colLength) : null,
                    precision: colKind === "decimal" ? whole(colPrecision) : null,
                    scale: colKind === "decimal" ? whole(colScale) : null,
                    nullable: colNullable,
                  },
                ]
              : null,
          index:
            statement === "create-index"
              ? { name: indexName, columns: indexColumns, unique }
              : statement === "drop-index"
                ? { name: dropIndex, columns: null, unique: null }
                : null,
        }),
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const copy = async () => {
    if (!result) return;
    try {
      await navigator.clipboard.writeText(script(result));
      setCopied(true);
    } catch {
      setError("The browser did not allow copying. Select the text and copy it instead.");
    }
  };

  const save = () => {
    if (!result) return;
    const url = URL.createObjectURL(new Blob([script(result)], { type: "application/sql" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = sqlFileName(detail.path, statement);
    a.click();
    // some browsers start the download after click returns: let go of the file a moment later
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  const toggleColumn = (name: string) =>
    setIndexColumns((cols) => (cols.includes(name) ? cols.filter((c) => c !== name) : [...cols, name]));

  return (
    <div className="generate-sql">
      <p className="hint">
        Kilnmark writes the SQL for you to copy or save; nothing here runs it. Names are quoted, types are the ones a
        pipeline's <span className="mono">create</span> would choose, and defaults, checks and comments are never copied.
      </p>
      <div className="field-row">
        <div className="field">
          <label htmlFor={`${id}-statement`}>Statement</label>
          <select id={`${id}-statement`} value={statement} onChange={(e) => setStatement(e.target.value as SqlStatement)}>
            {STATEMENTS.filter((s) => isTable || s.id === "create-table").map((s) => (
              <option key={s.id} value={s.id}>
                {s.label}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`${id}-dialect`}>Database</label>
          <select id={`${id}-dialect`} value={dialect} onChange={(e) => setDialect(e.target.value)}>
            {!own && <option value="">Choose a database</option>}
            {DIALECTS.map((d) => (
              <option key={d.kind} value={d.kind}>
                {d.label}
                {d.kind === own ? " (this source)" : ""}
              </option>
            ))}
          </select>
        </div>
      </div>

      {statement === "add-columns" && (
        <fieldset>
          <legend>The new column</legend>
          <div className="field-row">
            <div className="field">
              <label htmlFor={`${id}-col`}>Name</label>
              <input id={`${id}-col`} value={colName} onChange={(e) => setColName(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor={`${id}-kind`}>Kind</label>
              <select id={`${id}-kind`} value={colKind} onChange={(e) => setColKind(e.target.value)}>
                {KINDS.map((k) => (
                  <option key={k.kind} value={k.kind}>
                    {k.label}
                  </option>
                ))}
              </select>
            </div>
            {colKind === "text" && (
              <div className="field">
                <label htmlFor={`${id}-len`}>Length (empty: no limit)</label>
                <input id={`${id}-len`} type="number" min={1} value={colLength} onChange={(e) => setColLength(e.target.value)} />
              </div>
            )}
            {colKind === "decimal" && (
              <>
                <div className="field">
                  <label htmlFor={`${id}-prec`}>Digits</label>
                  <input id={`${id}-prec`} type="number" min={1} max={38} value={colPrecision} onChange={(e) => setColPrecision(e.target.value)} />
                </div>
                <div className="field">
                  <label htmlFor={`${id}-scale`}>After the point</label>
                  <input id={`${id}-scale`} type="number" min={0} max={38} value={colScale} onChange={(e) => setColScale(e.target.value)} />
                </div>
              </>
            )}
          </div>
          <label className="checkbox">
            <input type="checkbox" checked={colNullable} onChange={(e) => setColNullable(e.target.checked)} /> Allows nulls
          </label>
        </fieldset>
      )}

      {statement === "create-index" && (
        <fieldset>
          <legend>The index</legend>
          <div className="field">
            <label htmlFor={`${id}-ix`}>Name</label>
            <input id={`${id}-ix`} value={indexName} onChange={(e) => setIndexName(e.target.value)} />
          </div>
          <p className="small muted">Columns, in the order you tick them: {indexColumns.join(", ") || "none yet"}</p>
          <div className="explore-checks">
            {detail.columns.map((c) => (
              <label key={c.name} className="checkbox">
                <input type="checkbox" checked={indexColumns.includes(c.name)} onChange={() => toggleColumn(c.name)} />{" "}
                <span className="mono">{c.name}</span>
              </label>
            ))}
          </div>
          <label className="checkbox">
            <input type="checkbox" checked={unique} onChange={(e) => setUnique(e.target.checked)} /> Unique
          </label>
        </fieldset>
      )}

      {statement === "drop-index" && (
        <div className="field">
          <label htmlFor={`${id}-drop`}>Index</label>
          <select id={`${id}-drop`} value={dropIndex} onChange={(e) => setDropIndex(e.target.value)}>
            <option value="">Choose an index</option>
            {droppable.map((i) => (
              <option key={i.name} value={i.name}>
                {i.name}
              </option>
            ))}
          </select>
        </div>
      )}

      <div className="buttons">
        <Button variant="primary" onClick={generate} disabled={busy || !dialect}>
          {busy ? "Writing…" : "Generate SQL"}
        </Button>
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {result && (
        <div aria-live="polite">
          <pre className="sql-out" tabIndex={0} aria-label="The generated SQL">
            {script(result)}
          </pre>
          <div className="buttons">
            <Button onClick={copy}>{copied ? "Copied" : "Copy"}</Button>
            <Button onClick={save}>Save as .sql</Button>
          </div>
          {result.leftOut.length > 0 && (
            <>
              <h4>Left out or changed</h4>
              <ul>
                {result.leftOut.map((l) => (
                  <li key={l}>{l}</li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </div>
  );
}
