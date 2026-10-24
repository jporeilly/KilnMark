import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { allows, api, formatNumber, formatTime, type AccessRole, type Connector, type ImportReport, type Source } from "../api";
import { Status } from "../components/Status";
import { useTitle } from "../components/useTitle";

const busy = (s: Source) => s.lastRun?.status === "queued" || s.lastRun?.status === "running";

/** Sources and their scans. Stewards can start scans; admins can also add and import sources. */
export function SourcesPage({ role }: { role: AccessRole }) {
  useTitle("Sources");
  const canScan = allows(role, "steward");
  const canAdd = allows(role, "admin");
  const [sources, setSources] = useState<Source[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");

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

  const scan = async (name: string) => {
    try {
      await api.scan(name);
      setNotice(`Scan of ${name} queued. A worker will start it shortly.`);
      load();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <>
      <h1 tabIndex={-1}>Sources</h1>
      <p className="lead">Databases Kilnmark reads. A scan records every table and view, and profiles each column.</p>
      <p role="status" className="notice">
        {notice}
      </p>
      {error && <p role="alert" className="error">{error}</p>}

      <section aria-labelledby="list-h" className="panel">
        <h2 id="list-h">Registered sources</h2>
        {sources === null && !error && <p>Loading…</p>}
        {sources?.length === 0 && <p>None yet. Add one below.</p>}
        {sources && sources.length > 0 && (
          <div className="table-scroll" role="region" aria-label="Sources table (scrolls sideways on small screens)" tabIndex={0}>
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
                {sources.map((s) => (
                  <tr key={s.name}>
                    <th scope="row">{s.name}</th>
                    <td className="small">
                      <div className="muted">{s.kind}</div>
                      <div className="mono">{s.settings.url ?? s.settings.path ?? s.settings.bucket ?? "–"}</div>
                      {s.settings.schemas && <div className="muted">schemas: {s.settings.schemas}</div>}
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
                        <button type="button" onClick={() => scan(s.name)} disabled={busy(s)}
                          aria-label={`Scan ${s.name} now`}>
                          {s.lastRun?.status === "queued" ? "Queued…" : busy(s) ? "Scanning…" : "Scan now"}
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {!canScan && (
        <p className="hint">Your role ({role}) can read sources. Ask an admin for a steward token to start scans.</p>
      )}
      {canAdd ? (
        <>
          <AddSource
            onAdded={(s) => {
              setNotice(`Source ${s.name} added. Scan it to fill the catalogue.`);
              load();
            }}
          />
          <ImportSources
            onImported={(n) => {
              setNotice(`${n} ${n === 1 ? "source" : "sources"} imported.`);
              load();
            }}
          />
        </>
      ) : (
        <p className="hint">Only admins can add sources. Ask an admin if a database is missing.</p>
      )}
    </>
  );
}

function AddSource({ onAdded }: { onAdded: (s: Source) => void }) {
  const [connectors, setConnectors] = useState<Connector[]>([]);
  const [kind, setKind] = useState("postgresql");
  const [name, setName] = useState("");
  const [settings, setSettings] = useState<Record<string, string>>({});
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const errorRef = useRef<HTMLParagraphElement>(null);

  useEffect(() => {
    api.connectors().then(setConnectors).catch((e: Error) => setError(e.message));
  }, []);

  const connector = connectors.find((c) => c.kind === kind);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const s = await api.addSource({ name: name.trim(), kind, settings, password });
      setName("");
      setSettings({});
      setPassword("");
      onAdded(s);
    } catch (err) {
      setError((err as Error).message);
      window.setTimeout(() => errorRef.current?.focus(), 0);
    } finally {
      setSaving(false);
    }
  };

  return (
    <section aria-labelledby="add-h" className="panel">
      <h2 id="add-h">Add a source</h2>
      {error && (
        <p role="alert" className="error" tabIndex={-1} ref={errorRef}>
          {error}
        </p>
      )}
      <form onSubmit={submit} className="form">
        <div className="field-row">
          <div className="field">
            <label htmlFor="src-kind">Kind</label>
            <select id="src-kind" value={kind} onChange={(e) => {
              setKind(e.target.value);
              setSettings({});
            }}>
              {connectors.map((c) => (
                <option key={c.kind} value={c.kind}>{c.displayName}</option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="src-name">Name</label>
            <input id="src-name" value={name} onChange={(e) => setName(e.target.value)} required
              pattern="[a-z0-9][a-z0-9\-]{0,62}" aria-describedby="src-name-hint" autoComplete="off" />
            <p id="src-name-hint" className="hint">Lower-case letters, digits and hyphens, for example finance-db.</p>
          </div>
        </div>
        {connector?.settings.map((s) => (
          <div className="field" key={s.key}>
            <label htmlFor={`src-${s.key}`}>
              {s.label}
              {!s.required && " (optional)"}
            </label>
            <input id={`src-${s.key}`} value={settings[s.key] ?? ""} required={s.required} autoComplete="off"
              spellCheck={false} aria-describedby={s.hint ? `src-${s.key}-hint` : undefined}
              onChange={(e) => setSettings({ ...settings, [s.key]: e.target.value })} />
            {s.hint && (
              <p id={`src-${s.key}-hint`} className="hint">
                {s.hint}
                {s.multiple && ". Separate several with commas."}
              </p>
            )}
          </div>
        ))}
        <div className="field">
          <label htmlFor="src-pass">Password or secret (optional)</label>
          <input id="src-pass" type="password" value={password} onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password" aria-describedby="src-pass-hint" />
          <p id="src-pass-hint" className="hint">Stored encrypted. Use a read-only account.</p>
        </div>
        <button type="submit" className="primary" disabled={saving || !connector}>
          {saving ? "Adding…" : "Add source"}
        </button>
      </form>
    </section>
  );
}

function ImportSources({ onImported }: { onImported: (count: number) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [report, setReport] = useState<ImportReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [working, setWorking] = useState(false);

  const send = async (dryRun: boolean) => {
    if (!file) return;
    setWorking(true);
    setError(null);
    try {
      const format = file.name.toLowerCase().endsWith(".json") ? "json" : "csv";
      const r = await api.importSources(await file.text(), format, dryRun);
      setReport(r);
      if (!dryRun && r.errors === 0) onImported(r.created);
    } catch (e) {
      setReport(null);
      setError((e as Error).message);
    } finally {
      setWorking(false);
    }
  };

  return (
    <section aria-labelledby="import-h" className="panel">
      <h2 id="import-h">Import sources from a file</h2>
      <p className="hint">
        A CSV with a header row: <span className="mono">name, kind, password</span> or{" "}
        <span className="mono">password_ref</span>, then one column per setting (for PostgreSQL{" "}
        <span className="mono">url, username, schemas</span>; lists separated by semicolons), or a JSON array with the
        same keys. Use{" "}
        <span className="mono">password_ref</span> as <span className="mono">env:VARIABLE</span> or{" "}
        <span className="mono">secret:NAME</span> to keep passwords out of the file. Nothing is created unless
        every row is right.
      </p>
      <div className="form">
        <div className="field">
          <label htmlFor="import-file">File</label>
          <input id="import-file" type="file" accept=".csv,.json,text/csv,application/json"
            onChange={(e) => {
              setFile(e.target.files?.[0] ?? null);
              setReport(null);
            }} />
        </div>
        <div className="buttons">
          <button type="button" onClick={() => send(true)} disabled={!file || working}>
            Check the file
          </button>
          <button type="button" className="primary" onClick={() => send(false)} disabled={!file || working}>
            Import
          </button>
        </div>
      </div>
      {error && <p role="alert" className="error">{error}</p>}
      <div role="status">
        {report &&
          (report.errors > 0
            ? `${report.errors} ${report.errors === 1 ? "row needs" : "rows need"} fixing. Nothing was imported.`
            : report.dryRun
              ? `All ${report.rows.length} rows are ready to import.`
              : `Imported ${report.created} ${report.created === 1 ? "source" : "sources"}.`)}
      </div>
      {report && (
        <div className="table-scroll" role="region" aria-label="Import results by line" tabIndex={0}>
          <table>
            <thead>
              <tr>
                <th scope="col">Line</th>
                <th scope="col">Name</th>
                <th scope="col">Result</th>
              </tr>
            </thead>
            <tbody>
              {report.rows.map((r) => (
                <tr key={r.line}>
                  <td>{r.line}</td>
                  <th scope="row" className="mono">{r.name ?? "–"}</th>
                  <td className={r.status === "error" ? "error" : undefined}>
                    {r.status === "error" ? r.error : r.status === "created" ? "Imported" : "Ready"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
