import { useCallback, useEffect, useRef, useState, type RefObject } from "react";
import {
  allows,
  api,
  formatNumber,
  type AccessRole,
  type ImportBatch,
  type ImportOptions,
  type ImportReport,
  type Source,
} from "../api";
import { AddConnection } from "../components/AddConnection";
import { SourceTile } from "../components/ConnectorTile";
import { useProductName } from "../components/Brand";
import { ScanAction } from "../components/ScanAction";
import { useTitle } from "../components/useTitle";
import { Icon } from "../components/icons";
import { useConnectorInfo } from "../theme/looks";
import { Button, Card, DataTable, EmptyState, FilterChips, PageHeader, Switch, type Tone } from "../components/ui";
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

const HEALTH: Record<string, { tone: Tone; word: string }> = {
  queued: { tone: "info", word: "Scan queued" },
  running: { tone: "info", word: "Scanning…" },
  succeeded: { tone: "good", word: "Healthy" },
  partial: { tone: "warn", word: "Partly scanned" },
  failed: { tone: "bad", word: "Last scan failed" },
};
const day = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short" });

/**
 * Sources as a gallery of cards: tile, name, kind, health in words, assets and when it was last
 * scanned, with Scan now for stewards. Admins add a connection (the connector gallery with the
 * connection panel beside it) or import sources from a file, from the buttons at the top.
 */
export function SourcesPage({ role, add = false }: { role: AccessRole; add?: boolean }) {
  useTitle("Sources");
  const product = useProductName();
  const canScan = allows(role, "steward");
  const canAdd = allows(role, "admin");
  const connectors = useConnectorInfo();
  const [sources, setSources] = useState<Source[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [category, setCategory] = useState("all");
  const [grouped, setGrouped] = useState(false);
  const [adding, setAdding] = useState(add && canAdd);
  const [importing, setImporting] = useState(false);
  const addHeading = useRef<HTMLHeadingElement>(null);
  const importHeading = useRef<HTMLHeadingElement>(null);

  useEffect(() => setAdding(add && canAdd), [add, canAdd]);

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

  const open = (which: "add" | "import") => {
    if (which === "add") setAdding(true);
    else setImporting(true);
    window.setTimeout(() => {
      const h = (which === "add" ? addHeading : importHeading).current;
      h?.scrollIntoView({ block: "start" });
      h?.focus();
    }, 0);
  };

  const categories = [...new Set((sources ?? []).map((s) => s.category))].sort(
    (a, b) => ORDER.indexOf(a) - ORDER.indexOf(b),
  );
  const healthy = (sources ?? []).filter((s) => s.lastRun?.status === "succeeded").length;

  const gallery = (list: Source[], label: string) => (
    <ul className="km-gallery" aria-label={label}>
      {list.map((s) => {
        const h = s.lastRun ? HEALTH[s.lastRun.status] : { tone: "neutral" as Tone, word: "Never scanned" };
        const when = s.lastRun?.finishedAt ?? s.lastRun?.requestedAt;
        return (
          <li key={s.name} className="km-card km-gallery-card">
            <div className="with-tile">
              <SourceTile kind={s.kind} size="md" />
              <div className="km-row-body">
                <h3 className="km-row-title km-one-line">
                  <a href={href.source(s.name)} className="km-strong-link km-card-link" title={s.displayName}>
                    {s.displayName}
                  </a>
                </h3>
                <p className="km-row-detail km-one-line" title={`${connectors[s.kind]?.displayName ?? s.kind} · ${s.name}`}>
                  {connectors[s.kind]?.displayName ?? s.kind}
                  {s.displayName !== s.name && <span className="mono"> · {s.name}</span>}
                </p>
              </div>
            </div>
            <p className={`km-state km-text-${h.tone}`}>
              <span className="km-chip-dot" aria-hidden="true" />
              {h.word}
            </p>
            <p className="km-row-detail">
              {formatNumber(s.assets)} {s.assets === 1 ? "asset" : "assets"}
              {when && s.lastRun?.status !== "running" && ` · scanned ${day.format(new Date(when))}`}
              {Object.keys(s.formats).length > 0 &&
                ` · ${Object.entries(s.formats)
                  .map(([f, n]) => `${f} ${formatNumber(n)}`)
                  .join(", ")}`}
            </p>
            {s.lastRun?.detail && s.lastRun.status !== "succeeded" && <p className="hint">{s.lastRun.detail}</p>}
            {canScan && (
              <div className="km-gallery-actions">
                <ScanAction
                  source={s}
                  onQueued={(m) => {
                    setNotice(m);
                    load();
                  }}
                  onError={setError}
                />
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );

  const shown = (sources ?? []).filter((s) => category === "all" || s.category === category);

  return (
    <>
      <PageHeader
        title="Sources"
        meta={
          sources
            ? `${sources.length} connected${sources.length ? ` · ${healthy} healthy` : ""}`
            : "Databases, folders and buckets"
        }
        actions={
          canAdd && (
            <>
              <Button icon={<Icon name="import" size={16} />} onClick={() => open("import")} aria-expanded={importing}>
                Import from a file
              </Button>
              <Button variant="primary" icon={<Icon name="plus" size={16} />} onClick={() => open("add")} aria-expanded={adding}>
                Add a connection
              </Button>
            </>
          )
        }
        lead={`Databases, folders and buckets ${product} reads. A scan records every table, view and file, and profiles each column. Open a source to edit, test or delete it.`}
      />
      <p role="status" className="notice">
        {notice}
      </p>
      {error && <p role="alert" className="error">{error}</p>}

      <section aria-labelledby="list-h" className="km-section-plain">
        <h2 id="list-h" className="visually-hidden">
          Connected sources
        </h2>
        {sources === null && !error && <p role="status" className="muted">Loading…</p>}
        {sources?.length === 0 && (
          <Card>
            <EmptyState
              icon={<Icon name="sources" size={22} />}
              title="No sources yet"
              action={canAdd ? <Button variant="primary" onClick={() => open("add")}>Add a connection</Button> : undefined}
            >
              {canAdd ? "Add a database, folder or bucket, then scan it to fill the catalogue." : "Only admins can add sources."}
            </EmptyState>
          </Card>
        )}
        {sources && sources.length > 0 && (
          <>
            <div className="km-toolbar">
              {categories.length > 1 && (
                <FilterChips
                  label="Show sources by category"
                  selected={category}
                  onSelect={setCategory}
                  options={[
                    { id: "all", label: "All", count: sources.length },
                    ...categories.map((c) => ({
                      id: c,
                      label: CATEGORY[c] ?? c,
                      count: sources.filter((s) => s.category === c).length,
                    })),
                  ]}
                />
              )}
              {categories.length > 1 && (
                <div className="km-toolbar-switch">
                  <Switch label="Group by category" checked={grouped} onChange={setGrouped} />
                </div>
              )}
            </div>
            {grouped
              ? categories
                  .filter((c) => category === "all" || c === category)
                  .map((c) => (
                    <section key={c} aria-labelledby={`cat-${c}`}>
                      <h3 id={`cat-${c}`} className="km-group-title">
                        {CATEGORY[c] ?? c}
                      </h3>
                      {gallery(sources.filter((s) => s.category === c), CATEGORY[c] ?? c)}
                    </section>
                  ))
              : gallery(shown, "Sources")}
          </>
        )}
      </section>

      {!canScan && (
        <p className="hint">Your role ({role}) can read sources. Ask an admin for a steward token to start scans.</p>
      )}
      {canAdd ? (
        <>
          {adding && (
            <AddConnection
              headingRef={addHeading}
              onClose={() => setAdding(false)}
              onSaved={(s, queued) => {
                setNotice(
                  queued
                    ? `Source ${s.displayName} added, and its scan queued.`
                    : `Source ${s.displayName} added. Scan it to fill the catalogue.`,
                );
                load();
              }}
            />
          )}
          {importing && <ImportSources onImported={load} headingRef={importHeading} onClose={() => setImporting(false)} />}
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
function ImportSources({
  onImported,
  headingRef,
  onClose,
}: {
  onImported: () => void;
  headingRef: RefObject<HTMLHeadingElement | null>;
  onClose: () => void;
}) {
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
    <Switch
      id={`import-${key}`}
      label={label}
      help={hint}
      checked={options[key]}
      onChange={(on) => setOptions({ ...options, [key]: on })}
    />
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
    <section aria-labelledby="import-h" className="km-card km-section">
      <div className="km-card-head">
        <h2 id="import-h" className="km-card-title" tabIndex={-1} ref={headingRef}>
          Import sources from a file
        </h2>
        <div className="km-card-actions">
          <button type="button" className="km-icon-button" onClick={onClose} aria-label="Close the import">
            <Icon name="close" size={20} />
          </button>
        </div>
      </div>
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
        <fieldset className="group km-options-fieldset">
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
        <DataTable label="Import results by line">
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
        </DataTable>
      )}
    </section>
  );
}
