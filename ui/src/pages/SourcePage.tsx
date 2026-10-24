import { useCallback, useEffect, useRef, useState } from "react";
import {
  allows,
  api,
  formatDuration,
  formatNumber,
  formatTime,
  type AccessRole,
  type Connector,
  type HistoryRun,
  type Source,
  type TestResult,
} from "../api";
import { Status } from "../components/Status";
import { ScanAction } from "../components/ScanAction";
import { SourceForm } from "../components/SourceForm";
import { splitList } from "../components/ContainerPicker";
import { useTitle } from "../components/useTitle";
import { go, href } from "../router";

const COMMON = new Set(["description", "ai_egress", "include", "exclude"]);
const AI = { local_only: "Local models only", allowed: "Any configured model", none: "No AI models" } as Record<
  string,
  string
>;

/** One source: its settings (never its password), what it scans, its runs, and what a steward can do with it. */
export function SourcePage({ name, role }: { name: string; role: AccessRole }) {
  const [source, setSource] = useState<Source | null>(null);
  const [connector, setConnector] = useState<Connector | null>(null);
  const [history, setHistory] = useState<HistoryRun[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [missing, setMissing] = useState(false);
  const [notice, setNotice] = useState("");
  const [editing, setEditing] = useState(false);
  const [testing, setTesting] = useState(false);
  const [test, setTest] = useState<TestResult | null>(null);
  const canChange = allows(role, "steward");
  useTitle(source?.displayName ?? name);

  const load = useCallback(() => {
    api
      .source(name)
      .then((s) => {
        setSource(s);
        setError(null);
        return api.connectors().then((cs) => setConnector(cs.find((c) => c.kind === s.kind) ?? null));
      })
      .catch((e) => {
        if (e.status === 404) setMissing(true);
        else setError((e as Error).message);
      });
    api
      .history(name)
      .then(setHistory)
      .catch(() => setHistory([]));
  }, [name]);

  useEffect(load, [load]);

  const running = source?.lastRun?.status === "queued" || source?.lastRun?.status === "running";
  useEffect(() => {
    if (!running) return;
    const t = window.setInterval(load, 2000);
    return () => window.clearInterval(t);
  }, [running, load]);

  const runTest = async () => {
    setTesting(true);
    setTest(null);
    try {
      setTest(await api.testSource(name));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setTesting(false);
    }
  };

  if (missing) {
    return (
      <>
        <h1 tabIndex={-1}>Source not found</h1>
        <p>
          There is no source with the ID <span className="mono">{name}</span>. It may have been deleted.{" "}
          <a href={href.sources()}>Back to sources</a>.
        </p>
      </>
    );
  }
  if (!source) {
    return (
      <>
        <h1 tabIndex={-1}>{name}</h1>
        {error ? <p role="alert" className="error">{error}</p> : <p role="status">Loading…</p>}
      </>
    );
  }

  const containerKey = connector?.containerSetting ?? "schemas";
  const chosen = splitList(source.settings[containerKey]);
  const own = Object.entries(source.settings).filter(([k]) => !COMMON.has(k) && k !== containerKey);
  const label = (key: string) => connector?.settings.find((s) => s.key === key)?.label ?? key;

  return (
    <>
      <nav aria-label="Breadcrumb" className="crumbs">
        <ol>
          <li>
            <a href={href.sources()}>Sources</a>
          </li>
          <li aria-current="page">{source.displayName}</li>
        </ol>
      </nav>
      <h1 tabIndex={-1}>{source.displayName}</h1>
      <p className="lead">
        {connector?.displayName ?? source.kind} · Source ID <span className="mono">{source.name}</span>
      </p>
      {source.settings.description && <p>{source.settings.description}</p>}
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      {canChange && !editing && (
        <div className="buttons actions">
          <button type="button" onClick={() => setEditing(true)}>
            Edit
          </button>
          <button type="button" onClick={runTest} disabled={testing}>
            {testing ? "Testing…" : "Test connection"}
          </button>
          <ScanAction
            source={source}
            onQueued={(m) => {
              setNotice(m);
              load();
            }}
            onError={setError}
          />
          <DeleteSource source={source} runs={history.length} />
        </div>
      )}
      <div role="status" aria-live="polite">
        {test &&
          (test.ok ? (
            <p className="notice">Connected in {(test.millis / 1000).toFixed(1)} s.</p>
          ) : (
            <p className="warning">
              <strong>Could not connect.</strong> {test.problem}
            </p>
          ))}
      </div>

      {editing ? (
        <section aria-labelledby="edit-h" className="panel">
          <h2 id="edit-h">Edit {source.displayName}</h2>
          <SourceForm
            existing={source}
            onCancel={() => setEditing(false)}
            onSaved={(s) => {
              setSource(s);
              setEditing(false);
              setNotice("Changes saved. Scan again to apply new choices to the catalogue.");
            }}
          />
        </section>
      ) : (
        <section aria-labelledby="settings-h" className="panel">
          <h2 id="settings-h">Settings</h2>
          <dl className="facts">
            {own.map(([k, v]) => (
              <div key={k}>
                <dt>{label(k)}</dt>
                <dd className="mono">{v}</dd>
              </div>
            ))}
            <div>
              <dt>Password or secret</dt>
              <dd>{source.hasPassword ? "Stored, encrypted (never shown)" : "None"}</dd>
            </div>
            <div>
              <dt>{connector?.containerLabel ?? "Containers"} scanned</dt>
              <dd>{chosen.length === 0 ? "All" : <span className="mono">{chosen.join(", ")}</span>}</dd>
            </div>
            <div>
              <dt>Include</dt>
              <dd className="mono">{source.settings.include || "Everything"}</dd>
            </div>
            <div>
              <dt>Exclude</dt>
              <dd className="mono">{source.settings.exclude || "Nothing"}</dd>
            </div>
            <div>
              <dt>AI models</dt>
              <dd>{AI[source.settings.ai_egress ?? "local_only"] ?? source.settings.ai_egress}</dd>
            </div>
            <div>
              <dt>Assets recorded</dt>
              <dd>{formatNumber(source.assets)}</dd>
            </div>
            <div>
              <dt>Added</dt>
              <dd>{formatTime(source.createdAt)}</dd>
            </div>
          </dl>
        </section>
      )}

      <section aria-labelledby="runs-h" className="panel">
        <h2 id="runs-h">Scans</h2>
        <p>
          Last scan: <Status run={source.lastRun} />
          {source.lastRun?.detail && source.lastRun.status !== "succeeded" && (
            <span className="small"> {source.lastRun.detail}</span>
          )}
        </p>
        {history.length === 0 ? (
          <p>No scans yet.</p>
        ) : (
          <div className="table-scroll" role="region" aria-label="Scan history (scrolls sideways on small screens)" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Status</th>
                  <th scope="col">Started</th>
                  <th scope="col">Took</th>
                  <th scope="col">Assets</th>
                  <th scope="col">Not profiled</th>
                  <th scope="col">Rows read</th>
                  <th scope="col">Tries</th>
                  <th scope="col">Details</th>
                </tr>
              </thead>
              <tbody>
                {history.map((h) => (
                  <tr key={h.run.id}>
                    <td>
                      <Status run={h.run} />
                    </td>
                    <td>{formatTime(h.run.startedAt ?? h.run.requestedAt)}</td>
                    <td>{formatDuration(h.run)}</td>
                    <td>{formatNumber(h.run.assetsSeen)}</td>
                    <td>{formatNumber(h.run.assetsFailed)}</td>
                    <td>{formatNumber(h.run.rowsRead)}</td>
                    <td>{h.attempts ?? "–"}</td>
                    <td className="small">
                      {h.run.detail}
                      {h.jobError && h.jobError !== h.run.detail && <div className="muted">{h.jobError}</div>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {!canChange && (
        <p className="hint">Your role ({role}) can read sources. Ask an admin for a steward token to change them.</p>
      )}
    </>
  );
}

/** Delete, after a confirmation that says what goes with the source. */
function DeleteSource({ source, runs }: { source: Source; runs: number }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  const confirm = async () => {
    setDeleting(true);
    setError(null);
    try {
      await api.deleteSource(source.name);
      dialog.current?.close();
      go(href.sources());
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setDeleting(false);
    }
  };

  return (
    <>
      <button
        type="button"
        className="danger"
        onClick={() => {
          setError(null);
          dialog.current?.showModal();
          cancel.current?.focus(); // the safe choice first
        }}
      >
        Delete…
      </button>
      <dialog ref={dialog} aria-labelledby="del-h" aria-describedby="del-what" className="dialog">
        <h2 id="del-h">Delete {source.displayName}?</h2>
        <div id="del-what">
          <p>This removes, for good:</p>
          <ul>
            <li>
              the source <span className="mono">{source.name}</span> and its stored password;
            </li>
            <li>
              {formatNumber(source.assets)} {source.assets === 1 ? "asset" : "assets"}, with their fields and profiles;
            </li>
            <li>
              its scan history ({formatNumber(runs)}
              {runs >= 20 ? " or more" : ""} {runs === 1 ? "run" : "runs"}).
            </li>
          </ul>
          <p>Links and references to its assets stop working. The data in the source itself is not touched.</p>
        </div>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <div className="buttons">
          <button type="button" className="danger" onClick={confirm} disabled={deleting}>
            {deleting ? "Deleting…" : `Delete ${source.displayName}`}
          </button>
          <button type="button" ref={cancel} onClick={() => dialog.current?.close()}>
            Cancel
          </button>
        </div>
      </dialog>
    </>
  );
}
