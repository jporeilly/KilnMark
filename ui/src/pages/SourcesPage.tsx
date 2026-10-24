import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { api, formatNumber, formatTime, type ImportReport, type Source } from "../api";
import { Status } from "../components/Status";
import { useTitle } from "../components/useTitle";

const busy = (s: Source) => s.lastRun?.status === "queued" || s.lastRun?.status === "running";

export function SourcesPage() {
  useTitle("Sources");
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
      setNotice(`Scan of ${name} started.`);
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
                  <th scope="col">Database</th>
                  <th scope="col">Last scan</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {sources.map((s) => (
                  <tr key={s.name}>
                    <th scope="row">{s.name}</th>
                    <td className="mono small">
                      {s.url}
                      {s.schemas.length > 0 && <div className="muted">schemas: {s.schemas.join(", ")}</div>}
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
                    <td>
                      <button type="button" onClick={() => scan(s.name)} disabled={busy(s)}
                        aria-label={`Scan ${s.name} now`}>
                        {busy(s) ? "Scanning…" : "Scan now"}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

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
  );
}

function AddSource({ onAdded }: { onAdded: (s: Source) => void }) {
  const [name, setName] = useState("");
  const [url, setUrl] = useState("jdbc:postgresql://");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [schemas, setSchemas] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const errorRef = useRef<HTMLParagraphElement>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const s = await api.addSource({
        name: name.trim(),
        url: url.trim(),
        username: username.trim(),
        password,
        schemas: schemas.split(",").map((x) => x.trim()).filter(Boolean),
      });
      setName("");
      setUrl("jdbc:postgresql://");
      setUsername("");
      setPassword("");
      setSchemas("");
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
      <h2 id="add-h">Add a PostgreSQL source</h2>
      {error && (
        <p role="alert" className="error" tabIndex={-1} ref={errorRef}>
          {error}
        </p>
      )}
      <form onSubmit={submit} className="form">
        <div className="field">
          <label htmlFor="src-name">Name</label>
          <input id="src-name" value={name} onChange={(e) => setName(e.target.value)} required
            pattern="[a-z0-9][a-z0-9\-]{0,62}" aria-describedby="src-name-hint" autoComplete="off" />
          <p id="src-name-hint" className="hint">Lower-case letters, digits and hyphens, for example finance-db.</p>
        </div>
        <div className="field">
          <label htmlFor="src-url">JDBC URL</label>
          <input id="src-url" value={url} onChange={(e) => setUrl(e.target.value)} required
            aria-describedby="src-url-hint" autoComplete="off" spellCheck={false} />
          <p id="src-url-hint" className="hint">For example jdbc:postgresql://db.example.com:5432/sales</p>
        </div>
        <div className="field-row">
          <div className="field">
            <label htmlFor="src-user">User name</label>
            <input id="src-user" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" />
          </div>
          <div className="field">
            <label htmlFor="src-pass">Password</label>
            <input id="src-pass" type="password" value={password} onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password" aria-describedby="src-pass-hint" />
            <p id="src-pass-hint" className="hint">Stored encrypted. Use a read-only database account.</p>
          </div>
        </div>
        <div className="field">
          <label htmlFor="src-schemas">Schemas (optional)</label>
          <input id="src-schemas" value={schemas} onChange={(e) => setSchemas(e.target.value)}
            aria-describedby="src-schemas-hint" autoComplete="off" />
          <p id="src-schemas-hint" className="hint">Separate with commas. Leave empty to scan every schema.</p>
        </div>
        <button type="submit" className="primary" disabled={saving}>
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
        A CSV with a header row: <span className="mono">name, url, username, password, password_ref, schemas</span>{" "}
        (schemas separated by semicolons), or a JSON array with the same keys. Use{" "}
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
