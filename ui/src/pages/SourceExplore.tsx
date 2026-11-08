import { useEffect, useState } from "react";
import {
  api,
  formatNumber,
  type AssetList,
  type ContainerList,
  type Preview,
  type TableDetail,
  type TableForeignKey,
  plural,
} from "../api";
import { PreviewTable } from "../components/PreviewTable";
import { GenerateSql } from "./GenerateSql";
import { Button, DataTable, TabPanel, Tabs, useTabsId } from "../components/ui";
import { href } from "../router";

const TABS = [
  { id: "columns", label: "Columns" },
  { id: "keys", label: "Keys" },
  { id: "indexes", label: "Indexes" },
  { id: "preview", label: "Preview" },
  { id: "sql", label: "SQL" },
];

/** Opens a schema or table in the address (replaced, so Back leaves the source rather than stepping back through tables). */
function show(source: string, schema: string | null, table: string | null) {
  window.history.replaceState(null, "", href.source(source, schema, table));
  window.dispatchEvent(new HashChangeEvent("hashchange"));
}

/**
 * Explore (ADR 0045): a database source's schemas, the tables and views in one, and a table's
 * columns, keys, indexes and rows, read live and never stored. The preview is the source's own,
 * masked as every view of values is.
 */
export function SourceExplore({
  source,
  kind,
  schema,
  table,
}: {
  source: string;
  kind: string;
  schema: string | null;
  table: string | null;
}) {
  const [schemas, setSchemas] = useState<ContainerList | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setError(null);
    api
      .containers(source)
      .then(setSchemas)
      .catch((e: Error) => setError(e.message));
  }, [source]);

  return (
    <section aria-labelledby="explore-h" className="panel explore">
      <h2 id="explore-h">Explore</h2>
      <p className="hint">
        What the database holds now, read live and never kept: schemas, tables and views, their columns, keys and
        indexes, and a preview of the first rows. Marked columns are masked as everywhere else.
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {!schemas && !error && <p role="status">Listing schemas…</p>}
      {schemas && (
        <div className="explore-panes">
          <nav aria-label={schemas.label} className="explore-pane">
            <h3>{schemas.label}</h3>
            {schemas.containers.length === 0 ? (
              <p className="muted">None the login can see.</p>
            ) : (
              <ul className="explore-list">
                {schemas.containers.map((c) => (
                  <li key={c.name}>
                    <button
                      type="button"
                      className="link-button"
                      aria-current={c.name === schema ? "true" : undefined}
                      onClick={() => show(source, c.name, null)}
                    >
                      <span className="mono">{c.name}</span>
                    </button>{" "}
                    <span className="muted small num">{formatNumber(c.count)}</span>
                  </li>
                ))}
              </ul>
            )}
          </nav>
          {schema && <Tables source={source} schema={schema} table={table} />}
          {schema && table && <Table key={`${schema}.${table}`} source={source} kind={kind} path={`${schema}.${table}`} />}
          {!schema && <p className="muted explore-pane">Choose a schema to see its tables and views.</p>}
        </div>
      )}
    </section>
  );
}

function Tables({ source, schema, table }: { source: string; schema: string; table: string | null }) {
  const [list, setList] = useState<AssetList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [offset, setOffset] = useState(0);
  useEffect(() => {
    setOffset(0);
  }, [source, schema]);
  useEffect(() => {
    // A slower answer for a schema chosen before must not land under the one chosen now.
    let current = true;
    setError(null);
    setList(null);
    api
      .assets(source, schema, offset)
      .then((l) => current && setList(l))
      .catch((e: Error) => current && setError(e.message));
    return () => {
      current = false;
    };
  }, [source, schema, offset]);
  const page = (to: number) => setOffset(to);

  return (
    <nav aria-label={`Tables and views in ${schema}`} className="explore-pane">
      <h3>
        Tables and views in <span className="mono">{schema}</span>
      </h3>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {!list && !error && <p role="status">Listing…</p>}
      {list && list.items.length === 0 && <p className="muted">No tables or views.</p>}
      {list && list.items.length > 0 && (
        <>
          <ul className="explore-list">
            {list.items.map((a) => (
              <li key={a.path}>
                <button
                  type="button"
                  className="link-button"
                  aria-current={a.name === table ? "true" : undefined}
                  onClick={() => show(source, schema, a.name)}
                >
                  <span className="mono">{a.name}</span>
                </button>{" "}
                <span className="muted small">{a.kind}</span>
              </li>
            ))}
          </ul>
          {list.total > list.items.length && (
            <div className="buttons">
              <Button disabled={list.offset === 0} onClick={() => page(Math.max(0, list.offset - 50))}>
                Previous
              </Button>
              <span className="small muted">
                {formatNumber(list.offset + 1)}–{formatNumber(list.offset + list.items.length)} of {formatNumber(list.total)}
              </span>
              <Button disabled={list.offset + list.items.length >= list.total} onClick={() => page(list.offset + 50)}>
                Next
              </Button>
            </div>
          )}
        </>
      )}
    </nav>
  );
}

/** "about 1,204,000 rows (the database's estimate)", or what is known when the database keeps none. */
export function estimateText(t: TableDetail): string {
  if (t.kind !== "table" && t.kind !== "partitioned table") return "A view: its rows are counted when read.";
  if (t.estimatedRows == null) return "The database keeps no estimate of its rows.";
  return `About ${plural(t.estimatedRows, "row")} (the database's estimate).`;
}

function Table({ source, kind, path }: { source: string; kind: string; path: string }) {
  const [detail, setDetail] = useState<TableDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState("columns");
  const [count, setCount] = useState<number | null>(null);
  const [countError, setCountError] = useState<string | null>(null);
  const [counting, setCounting] = useState(false);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const idBase = useTabsId();

  useEffect(() => {
    api
      .explore(source, path)
      .then(setDetail)
      .catch((e: Error) => setError(e.message));
  }, [source, path]);

  useEffect(() => {
    if (tab !== "preview" || preview || previewError) return;
    api
      .preview(source, path, 50)
      .then(setPreview)
      .catch((e: Error) => setPreviewError(e.message));
  }, [tab, preview, previewError, source, path]);

  const countNow = async () => {
    setCounting(true);
    setCountError(null);
    try {
      setCount((await api.countRows(source, path)).rows);
    } catch (e) {
      setCountError((e as Error).message);
    } finally {
      setCounting(false);
    }
  };

  return (
    <div className="explore-pane explore-table">
      <h3 className="mono">{path}</h3>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {!detail && !error && <p role="status">Reading the table…</p>}
      {detail && (
        <>
          <p className="small" aria-live="polite">
            {count != null ? `${plural(count, "row")}, counted just now.` : estimateText(detail)}{" "}
            {count == null && (
              <Button onClick={countNow} disabled={counting}>
                {counting ? "Counting…" : "Count exactly"}
              </Button>
            )}
          </p>
          {countError && (
            <p role="alert" className="error">
              {countError}
            </p>
          )}
          <Tabs tabs={TABS} selected={tab} onSelect={setTab} label={`About ${path}`} idBase={idBase} />
          <TabPanel idBase={idBase} selected={tab}>
            {tab === "columns" && <Columns detail={detail} />}
            {tab === "keys" && <Keys detail={detail} />}
            {tab === "indexes" && <Indexes detail={detail} />}
            {tab === "sql" && <GenerateSql source={source} sourceKind={kind} detail={detail} />}
            {tab === "preview" &&
              (preview ? (
                <PreviewTable preview={preview} hint="The first 50 rows, read live and not kept. Marked columns are masked." />
              ) : previewError ? (
                <div>
                  <p role="alert" className="error">
                    {previewError}
                  </p>
                  <Button onClick={() => setPreviewError(null)}>Try again</Button>
                </div>
              ) : (
                <p role="status">Reading the first rows…</p>
              ))}
          </TabPanel>
        </>
      )}
    </div>
  );
}

function typeText(c: TableDetail["columns"][number]): string {
  if (c.size == null) return c.sourceType;
  return c.scale != null ? `${c.sourceType}(${c.size},${c.scale})` : `${c.sourceType}(${c.size})`;
}

function Columns({ detail }: { detail: TableDetail }) {
  const key = new Set(detail.primaryKey);
  return (
    <DataTable label={`Columns of ${detail.path}`}>
      <thead>
        <tr>
          <th scope="col">Column</th>
          <th scope="col">Type</th>
          <th scope="col">Nulls</th>
          <th scope="col">Filled by</th>
          <th scope="col">Key</th>
        </tr>
      </thead>
      <tbody>
        {detail.columns.map((c) => (
          <tr key={c.name}>
            <td className="mono">{c.name}</td>
            <td className="mono">{typeText(c)}</td>
            <td>{c.nullable ? "Allowed" : "Not null"}</td>
            <td>{c.generated ? "The database" : c.hasDefault ? "A default when left out" : "–"}</td>
            <td>{key.has(c.name) ? "Yes" : ""}</td>
          </tr>
        ))}
      </tbody>
    </DataTable>
  );
}

function KeyRow({ k, own }: { k: TableForeignKey; own: boolean }) {
  return (
    <li>
      <span className="mono">{own ? k.columns.join(", ") : `${k.table} (${k.columns.join(", ")})`}</span> →{" "}
      <span className="mono">{own ? `${k.toTable} (${k.toColumns.join(", ")})` : k.toColumns.join(", ")}</span>
      {k.name && <span className="muted small"> · {k.name}</span>}
    </li>
  );
}

function Keys({ detail }: { detail: TableDetail }) {
  return (
    <dl className="facts">
      <div>
        <dt>Primary key</dt>
        <dd className="mono">{detail.primaryKey.length ? detail.primaryKey.join(", ") : "None"}</dd>
      </div>
      <div>
        <dt>Points to</dt>
        <dd>
          {detail.foreignKeys.length === 0 ? (
            "No foreign keys"
          ) : (
            <ul className="explore-keys">
              {detail.foreignKeys.map((k, i) => (
                <KeyRow key={i} k={k} own />
              ))}
            </ul>
          )}
        </dd>
      </div>
      <div>
        <dt>Pointed to by</dt>
        <dd>
          {detail.referencedBy.length === 0 ? (
            "No other table"
          ) : (
            <ul className="explore-keys">
              {detail.referencedBy.map((k, i) => (
                <KeyRow key={i} k={k} own={false} />
              ))}
            </ul>
          )}
        </dd>
      </div>
    </dl>
  );
}

function Indexes({ detail }: { detail: TableDetail }) {
  if (detail.indexes.length === 0) {
    return <p className="muted">{detail.kind === "table" || detail.kind === "partitioned table" ? "No indexes." : "Views have no indexes here."}</p>;
  }
  return (
    <DataTable label={`Indexes of ${detail.path}`}>
      <thead>
        <tr>
          <th scope="col">Index</th>
          <th scope="col">Columns, in order</th>
          <th scope="col">Unique</th>
          <th scope="col">Kind</th>
        </tr>
      </thead>
      <tbody>
        {detail.indexes.map((i) => (
          <tr key={i.name}>
            <td className="mono">{i.name}</td>
            <td className="mono">{i.columns.join(", ")}</td>
            <td>{i.primaryKey ? "Primary key" : i.unique ? (i.partial ? "Yes, among the rows it covers" : "Yes") : "No"}</td>
            <td>{[i.method, i.partial ? "partial" : null].filter(Boolean).join(", ") || "–"}</td>
          </tr>
        ))}
      </tbody>
    </DataTable>
  );
}
