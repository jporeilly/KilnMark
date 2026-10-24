import { useCallback, useEffect, useState } from "react";
import {
  allows,
  api,
  formatNumber,
  formatTime,
  type AccessRole,
  type ImportBatch,
  type ImportOptions,
  type ImportReport,
  type Source,
} from "../api";
import { AddConnection } from "../components/AddConnection";
import { ScanAction } from "../components/ScanAction";
import { Status } from "../components/Status";
import { useTitle } from "../components/useTitle";
import { href } from "../router";

const ORDER = ["databases", "nosql", "files", "streaming", "applications", "lakehouse", "other"];
const CATEGORY: Record<string, string> = {
  databases: "Databases",
  nosql: "NoSQL",
  files: "Files",
  streaming: "Streaming",
  applications: "Applications",
  lakehouse: "Lakehouse",
  other: "Other",
};

const busy = (s: Source) => s.lastRun?.status === "queued" || s.lastRun?.status === "running";

/** Sources and their scans. Stewards can start scans and open a source to change it; admins can also add and import sources. */
export function SourcesPage({ role }: { role: AccessRole }) {
  useTitle("Sources");
  const canScan = allows(role, "steward");
  const canAdd = allows(role, "admin");
  const [sources, setSources] = useState<Source[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [category, setCategory] = useState("all");
  const [grouped, setGrouped] = useState(false);

  const load = useCallback(() => {
    api
      .sources()
      .then((s) => {
        setSources(s);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  useEffect(load, [load]);

  // While a scan runs, refresh every two seconds.
  useEffect(() => {
    if (!sources?.some(busy)) return;
    const t = window.setInterval(load, 2000);
    return () => window.clearInterval(t);
  }, [sources, load]);

  const categories = [...new Set((sources ?? []).map((s) => s.category))].sort(
    (a, b) => ORDER.indexOf(a) - ORDER.indexOf(b),
  );

  const table = (list: Source[], label: string) => (
    <div className="table-scroll" role="region" aria-label={`${label} (scrolls sideways on small screens)`} tabIndex={0}>
      <table>
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Where</th>
            <th scope="col">Last scan</th>
            {canScan && (
              <th scope="col">
                <span className="visually-hidden">Actions</span>
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {list.map((s) => (
            <tr key={s.name}>
              <th scope="row">
                <a href={href.source(s.name)}>{s.displayName}</a>
                {s.displayName !== s.name && <div className="small muted mono">{s.name}</div>}
              </th>
              <td className="small">
                <div className="muted">
                  {CATEGORY[s.category] ?? s.category} · {s.kind}
                </div>
                <div className="mono">{s.settings.url ?? s.settings.path ?? s.settings.bucket ?? "–"}</div>
                {s.settings.schemas && <div className="muted">schemas: {s.settings.schemas}</div>}
                {s.settings.containers && <div className="muted">folders: {s.settings.containers}</div>}
                {Object.keys(s.formats).length > 0 && (
                  <div>
                    Formats found:{" "}
                    {Object.entries(s.formats)
                      .map(([f, n]) => `${f} ${formatNumber(n)}`)
                      .join(", ")}
                  </div>
                )}
              </td>
              <td>
                <Status run={s.lastRun} />
                {s.lastRun && (
                  <div className="small muted">
                    {formatTime(s.lastRun.finishedAt ?? s.lastRun.startedAt ?? s.lastRun.requestedAt)}
                    {s.lastRun.status !== "queued" && (
                      <>
                        {" · "}
                        {s.lastRun.assetsSeen} assets, {formatNumber(s.lastRun.rowsRead)} rows
                      </>
                    )}
                  </div>
                )}
                {s.lastRun?.detail && s.lastRun.status !== "succeeded" && (
                  <div className="small">{s.lastRun.detail}</div>
                )}
              </td>
              {canScan && (
                <td>
                  <ScanAction
                    source={s}
                    onQueued={(m) => {
                      setNotice(m);
                      load();
                    }}
                    onError={setError}
                  />
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );

  return (
    <>
      <h1 tabIndex={-1}>Sources</h1>
      <p className="lead">
        Databases, folders and buckets Kilnmark reads. A scan records every table, view and file, and profiles each
        column. Open a source to edit, test or delete it.
      </p>
      <p role="status" className="notice">
        {notice}
      </p>
      {error && <p role="alert" className="error">{error}</p>}

      <section aria-labelledby="list-h" className="panel">
        <h2 id="list-h">Registered sources</h2>
        {sources === null && !error && <p>Loading…</p>}
        {sources?.length === 0 && <p>None yet. Add one below.</p>}
        {sources && sources.length > 0 && (
          <div className="field-row list-controls">
            <div className="field">
              <label htmlFor="src-category">Show</label>
              <select id="src-category" value={category} onChange={(e) => setCategory(e.target.value)}>
                <option value="all">Every category</option>
                {categories.map((c) => (
                  <option key={c} value={c}>
                    {CATEGORY[c] ?? c}
                  </option>
                ))}
              </select>
            </div>
            <div className="check">
              <input id="src-group" type="checkbox" checked={grouped} onChange={(e) => setGrouped(e.target.checked)} />
              <label htmlFor="src-group">Group by category</label>
            </div>
          </div>
        )}
        {sources &&
          sources.length > 0 &&
          (grouped
            ? categories
                .filter((c) => category === "all" || c === category)
                .map((c) => (
                  <section key={c} aria-labelledby={`cat-${c}`}>
                    <h3 id={`cat-${c}`}>{CATEGORY[c] ?? c}</h3>
                    {table(sources.filter((s) => s.category === c), CATEGORY[c] ?? c)}
                  </section>
                ))
            : table(sources.filter((s) => category === "all" || s.category === category), "Sources"))}
      </section>

      {!canScan && (
        <p className="hint">Your role ({role}) can read sources. Ask an admin for a steward token to start scans.</p>
      )}
      {canAdd ? (
        <>
          <AddConnection
            onSaved={(s, queued) => {
              setNotice(
                queued
                  ? `Source ${s.displayName} added, and its scan queued.`
                  : `Source ${s.displayName} added. Scan it to fill the catalogue.`,
              );
              load();
            }}
          />
          <ImportSources onImported={load} />
        </>
      ) : (
        <p className="hint">Only admins can add sources. Ask an admin if a database is missing.</p>
      )}
    </>
  );
}

const STATUS: Record<string, string> = {
  created: "Created",
  updated: "Updated",
  unchanged: "Unchanged",
  failed: "Needs fixing",
};
const DRY: Record<string, string> = {
  created: "Would be created",
  updated: "Would be updated",
  unchanged: "Unchanged",
  failed: "Needs fixing",
};
const TEST: Record<string, string> = {
  waiting: "Waiting",
  testing: "Testing…",
  ok: "Connected",
  failed: "Could not connect",
  skipped: "Not tested",
};
const SCAN: Record<string, string> = { waiting: "Waiting", queued: "Scan queued", skipped: "Not scanned", failed: "Not queued" };

/**
 * Import many sources from a CSV or JSON file. Re-runnable: rows create sources, update them to
 * match, or leave them unchanged. Optionally tests each one and queues scans, with progress here.
 */
function ImportSources({ onImported }: { onImported: () => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [options, setOptions] = useState<Omit<ImportOptions, "dryRun">>({
    skipErrors: false,
    remapHosts: false,
    test: false,
    scan: false,
  });
  const [report, setReport] = useState<ImportReport | null>(null);
  const [batch, setBatch] = useState<ImportBatch | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [working, setWorking] = useState(false);

  // Poll the follow-up tests and scans until they finish.
  useEffect(() => {
    const id = report?.batchId;
    if (id == null || (batch && batch.id === id && batch.status !== "running")) return;
    const t = window.setTimeout(() => {
      api
        .importBatch(id)
        .then((b) => {
          setBatch(b);
          if (b.status !== "running") onImported();
        })
        .catch((e: Error) => setError(e.message));
    }, 1000);
    return () => window.clearTimeout(t);
  }, [report, batch, onImported]);

  const send = async (dryRun: boolean) => {
    if (!file) return;
    setWorking(true);
    setError(null);
    setBatch(null);
    try {
      const format = file.name.toLowerCase().endsWith(".json") ? "json" : "csv";
      const r = await api.importSources(await file.text(), format, { dryRun, ...options });
      setReport(r);
      if (r.applied) onImported();
    } catch (e) {
      setReport(null);
      setError((e as Error).message);
    } finally {
      setWorking(false);
    }
  };

  const option = (key: keyof typeof options, label: string, hint: string) => (
    <div className="check">
      <input
        type="checkbox"
        id={`import-${key}`}
        checked={options[key]}
        onChange={(e) => setOptions({ ...options, [key]: e.target.checked })}
        aria-describedby={`import-${key}-hint`}
      />
      <label htmlFor={`import-${key}`}>{label}</label>
      <p id={`import-${key}-hint`} className="hint">
        {hint}
      </p>
    </div>
  );

  const progress = (line: number) => batch?.rows.find((b) => b.line === line);
  const summary = (r: ImportReport) => {
    const n = (status: string) => r.rows.filter((x) => x.status === status).length;
    const done = r.applied;
    const parts = [
      n("created") && `${n("created")} ${done ? "created" : "to create"}`,
      n("updated") && `${n("updated")} ${done ? "updated" : "to update"}`,
      n("unchanged") && `${n("unchanged")} unchanged`,
      n("failed") && `${n("failed")} ${n("failed") === 1 ? "needs" : "need"} fixing`,
    ].filter(Boolean);
    const head = r.dryRun ? "Checked, nothing changed: " : done ? "Imported: " : "Nothing was imported: ";
    return head + (parts.join(", ") || "no rows") + ".";
  };

  return (
    <section aria-labelledby="import-h" className="panel">
      <h2 id="import-h">Import sources from a file</h2>
      <p className="hint">
        A CSV with a header row: <span className="mono">name</span> (the source ID),{" "}
        <span className="mono">kind</span>, <span className="mono">display_name</span>,{" "}
        <span className="mono">password</span> or <span className="mono">password_ref</span>, then one column per
        setting (for PostgreSQL <span className="mono">url, username, schemas</span>, or{" "}
        <span className="mono">host, port, database</span> instead of the URL), and the settings every source has:{" "}
        <span className="mono">description, ai_egress, include, exclude</span>. Lists are separated by semicolons. Or a
        JSON array with the same keys. Use <span className="mono">password_ref</span> as{" "}
        <span className="mono">env:VARIABLE</span> or <span className="mono">secret:NAME</span> to keep passwords out
        of the file. Run the same file again at any time: each source is set to what its row says.
      </p>
      <div className="form">
        <div className="field">
          <label htmlFor="import-file">File</label>
          <input
            id="import-file"
            type="file"
            accept=".csv,.json,text/csv,application/json"
            onChange={(e) => {
              setFile(e.target.files?.[0] ?? null);
              setReport(null);
              setBatch(null);
            }}
          />
        </div>
        <fieldset className="group">
          <legend>Options</legend>
          {option("skipErrors", "Import the rows that are right, even if others need fixing", "Otherwise nothing changes until every row is right.")}
          {option("remapHosts", "Rewrite host names for this server", "Uses the host map an administrator set (kilnmark.sources.host-map), for example localhost to host.docker.internal, so sources written from a desktop work from inside the container.")}
          {option("test", "Test each connection afterwards", "Up to 15 seconds each, a few at a time. Progress shows below.")}
          {option("scan", "Queue a scan of each source afterwards", "With testing on, only sources that connected are scanned. A check of the file never scans.")}
        </fieldset>
        <div className="buttons">
          <button type="button" onClick={() => send(true)} disabled={!file || working}>
            Check the file
          </button>
          <button type="button" className="primary" onClick={() => send(false)} disabled={!file || working}>
            Import
          </button>
        </div>
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <div role="status" aria-live="polite">
        {report && <p>{summary(report)}</p>}
        {batch && batch.status !== "running" && (
          <p>{batch.status === "finished" ? "Tests and scans are done." : "The server stopped before finishing. Run the import again."}</p>
        )}
      </div>
      {report && (
        <div className="table-scroll" role="region" aria-label="Import results by line" tabIndex={0}>
          <table>
            <thead>
              <tr>
                <th scope="col">Line</th>
                <th scope="col">Source ID</th>
                <th scope="col">Result</th>
                {report.batchId != null && <th scope="col">Connection</th>}
                {report.batchId != null && !report.dryRun && <th scope="col">Scan</th>}
              </tr>
            </thead>
            <tbody>
              {report.rows.map((r) => {
                const p = progress(r.line);
                return (
                  <tr key={r.line}>
                    <td>{r.line}</td>
                    <th scope="row" className="mono">
                      {r.name && r.status !== "failed" && report.applied ? <a href={href.source(r.name)}>{r.name}</a> : (r.name ?? "–")}
                    </th>
                    <td className={r.status === "failed" ? "error" : undefined}>
                      {(report.applied ? STATUS : DRY)[r.status] ?? r.status}
                      {r.error && <div>{r.error}</div>}
                      {r.notes?.map((n) => (
                        <div key={n} className="small muted">
                          {n}
                        </div>
                      ))}
                    </td>
                    {report.batchId != null && (
                      <td>
                        {p ? TEST[p.test] : r.status === "failed" ? "–" : "Waiting"}
                        {p?.problem && <div className="small">{p.problem}</div>}
                      </td>
                    )}
                    {report.batchId != null && !report.dryRun && <td>{p ? SCAN[p.scan] : "–"}</td>}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
