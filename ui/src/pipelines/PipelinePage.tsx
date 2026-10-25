import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import {
  allows,
  ApiError,
  formatNumber,
  formatTime,
  pipelinesApi,
  type AccessRole,
  type PipelineDetail,
  type PipelineProblem,
  type PipelineRun,
  type PipelineValidation,
  type PipelineVersion,
  type PipelineVersionSummary,
} from "../api";
import { go, href } from "../router";
import { useTitle } from "../components/useTitle";
import { docHref } from "../docs/links";
import { RunStatus, runTime } from "./RunStatus";
import { columnsText } from "./outline";
import { renamed, starterYaml } from "./template";
import { YamlEditor, type YamlEditorHandle } from "./YamlEditor";
import "./pipelines.css";

// The graph library is large, so the graph loads when the page first shows one.
const PipelineDiagram = lazy(() => import("./PipelineDiagram"));

const NAME = /^[a-z][a-z0-9-]{0,62}$/;
const CHECK_DELAY_MS = 600;

/**
 * One pipeline: its YAML in an editor that checks it as you type and lists problems by line
 * (choosing one moves the caret there), Save with a message, a read-only graph of its steps, its
 * versions and its runs. With name null, a new pipeline from the starter template. With a version,
 * that saved version, read-only.
 */
export function PipelinePage({ name, version, role }: { name: string | null; version: number | null; role: AccessRole }) {
  const steward = allows(role, "steward");
  const isNew = name == null;
  const viewing = version != null;
  const editable = steward && !viewing;
  useTitle(isNew ? "New pipeline" : viewing ? `${name}, version ${version}` : (name ?? "Pipeline"));

  const [detail, setDetail] = useState<PipelineDetail | null>(null);
  const [shown, setShown] = useState<PipelineVersion | null>(null);
  const [yaml, setYaml] = useState(isNew ? starterYaml("my-pipeline") : "");
  const [savedYaml, setSavedYaml] = useState<string | null>(null);
  const [newName, setNewName] = useState("my-pipeline");
  const [displayName, setDisplayName] = useState("");
  const [message, setMessage] = useState("");
  const [check, setCheck] = useState<PipelineValidation | null>(null);
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);
  const [versions, setVersions] = useState<PipelineVersionSummary[] | null>(null);
  const [runs, setRuns] = useState<PipelineRun[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const editor = useRef<YamlEditorHandle>(null);
  const errorRef = useRef<HTMLParagraphElement>(null);
  const sequence = useRef(0);

  const load = useCallback(async () => {
    if (isNew || name == null) return;
    try {
      const d = await pipelinesApi.get(name);
      setDetail(d);
      if (version != null) {
        setShown(await pipelinesApi.version(name, version));
      } else if (d.latest) {
        setYaml(d.latest.yaml);
        setSavedYaml(d.latest.yaml);
      }
      setLoadError(null);
    } catch (e) {
      setLoadError((e as Error).message);
    }
  }, [isNew, name, version]);

  const loadLists = useCallback(() => {
    if (isNew || name == null) return;
    pipelinesApi.versions(name).then(setVersions).catch((e: Error) => setLoadError(e.message));
    pipelinesApi.runs(name).then(setRuns).catch((e: Error) => setLoadError(e.message));
  }, [isNew, name]);

  useEffect(() => {
    void load();
    loadLists();
  }, [load, loadLists]);

  // Keep the runs table current while a run is queued or running.
  const active = runs?.some((r) => r.status === "queued" || r.status === "running");
  useEffect(() => {
    if (!active) return;
    const t = window.setInterval(loadLists, 4000);
    return () => window.clearInterval(t);
  }, [active, loadLists]);

  const text = viewing ? (shown?.yaml ?? "") : yaml;

  // Check the YAML a moment after typing stops. Only the latest answer counts.
  useEffect(() => {
    if (!text.trim()) return;
    const mine = ++sequence.current;
    setChecking(true);
    const t = window.setTimeout(() => {
      pipelinesApi
        .validate(text, isNew ? undefined : (name ?? undefined))
        .then((v) => {
          if (sequence.current !== mine) return;
          setCheck(v);
          setCheckError(null);
        })
        .catch((e: Error) => {
          if (sequence.current === mine) setCheckError(e.message);
        })
        .finally(() => {
          if (sequence.current === mine) setChecking(false);
        });
    }, CHECK_DELAY_MS);
    return () => window.clearTimeout(t);
  }, [text, isNew, name]);

  const problems = useMemo(() => check?.problems ?? [], [check]);
  const problemLines = useMemo(() => new Set(problems.filter((p) => p.line > 0).map((p) => p.line)), [problems]);
  const problemSteps = useMemo(() => new Set(problems.map((p) => p.step).filter((s): s is string => !!s)), [problems]);
  const dirty = !viewing && (isNew || (savedYaml != null && yaml !== savedYaml));
  const nameProblem = isNew && !NAME.test(newName)
    ? "Start with a small letter, then use small letters, digits and dashes (at most 63)."
    : null;

  const fail = (e: unknown) => {
    setError((e as Error).message);
    setConflict(e instanceof ApiError && e.status === 409 && !isNew);
    window.setTimeout(() => errorRef.current?.focus(), 0);
  };

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setConflict(false);
    setNotice("");
    if (isNew && nameProblem) {
      fail(new Error(`Name: ${nameProblem}`));
      return;
    }
    if (!isNew && !message.trim()) {
      fail(new Error("Say what you changed in the message, so the version list makes sense later."));
      return;
    }
    setSaving(true);
    try {
      if (isNew) {
        const d = await pipelinesApi.create({
          name: newName,
          displayName: displayName.trim() || undefined,
          yaml,
          message: message.trim() || undefined,
        });
        go(href.pipeline(d.name));
        return;
      }
      const d = await pipelinesApi.save(name!, { yaml, message: message.trim() }, detail?.latestVersion);
      setDetail(d);
      setSavedYaml(yaml);
      setMessage("");
      setNotice(
        `Saved as version ${d.latestVersion}${d.latest && !d.latest.valid ? ", as a draft: fix its problems before running it" : ""}.`,
      );
      loadLists();
    } catch (err) {
      fail(err);
    } finally {
      setSaving(false);
    }
  };

  const reloadLatest = async () => {
    setConflict(false);
    setError(null);
    await load();
    loadLists();
    setNotice("Loaded the latest version. Make your change again, then save.");
  };

  const runNow = async () => {
    if (!name) return;
    setError(null);
    try {
      const run = await pipelinesApi.run(name);
      setNotice(`Run ${run.id} of version ${run.version} is queued.`);
      loadLists();
    } catch (err) {
      fail(err);
    }
  };

  const remove = async () => {
    if (!name || !window.confirm(`Delete ${detail?.displayName ?? name} with all its versions and runs? What its runs wrote stays.`))
      return;
    try {
      await pipelinesApi.remove(name);
      go(href.pipelines());
    } catch (err) {
      fail(err);
    }
  };

  const goTo = (p: PipelineProblem) => {
    if (p.line > 0) editor.current?.goToLine(p.line, p.column);
  };

  if (loadError && !detail) {
    return (
      <>
        <h1 tabIndex={-1}>{name}</h1>
        <p role="alert" className="error">
          {loadError}
        </p>
        <p>
          <a href={href.pipelines()}>All pipelines</a>
        </p>
      </>
    );
  }

  const title = isNew ? "New pipeline" : (detail?.displayName ?? name);
  const summary = checking
    ? "Checking…"
    : checkError
      ? `Could not check: ${checkError}`
      : check == null
        ? ""
        : problems.length === 0
          ? `No problems. ${check.steps.length} ${check.steps.length === 1 ? "step" : "steps"}.`
          : `${problems.length} ${problems.length === 1 ? "problem" : "problems"}.`;

  return (
    <>
      <p className="small">
        <a href={href.pipelines()}>Pipelines</a>
        {viewing && name && (
          <>
            {" "}
            › <a href={href.pipeline(name)}>{detail?.displayName ?? name}</a>
          </>
        )}
      </p>
      <div className="pl-head">
        <h1 tabIndex={-1}>
          {title}
          {viewing && <span className="muted">, version {version}</span>}
        </h1>
        {!isNew && !viewing && steward && (
          <div className="buttons">
            <button type="button" className="primary" onClick={runNow} disabled={!detail?.latest?.valid}>
              Run now
            </button>
            <button type="button" onClick={remove}>
              Delete
            </button>
          </div>
        )}
      </div>
      {detail?.description && <p className="lead">{detail.description}</p>}
      {isNew && (
        <p className="lead">
          Start from the template, which reads from the lab database and writes to the lab file share. The YAML is
          checked as you type. <a href={docHref("pipelines")}>How pipelines are written</a>.
        </p>
      )}
      {!isNew && !viewing && detail?.latest && (
        <p className="small muted">
          Version {detail.latestVersion}, saved {formatTime(detail.updatedAt)} by {detail.updatedBy}.
          {!detail.latest.valid && " It has problems, so it cannot run."}
          {detail.lastRun && (
            <>
              {" "}
              Last run: <RunStatus run={detail.lastRun} />{" "}
              <a href={href.pipelineRun(detail.lastRun.id)}>run {detail.lastRun.id}</a>.
            </>
          )}
        </p>
      )}
      {viewing && shown && (
        <p className="small muted">
          Saved {formatTime(shown.createdAt)} by {shown.author}
          {shown.message && <>: “{shown.message}”</>}. Read-only.{" "}
          {name && <a href={href.pipeline(name)}>Back to the latest version</a>}
        </p>
      )}
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error" tabIndex={-1} ref={errorRef}>
          {error}
        </p>
      )}
      {conflict && (
        <p>
          <button type="button" onClick={reloadLatest}>
            Load the latest version
          </button>{" "}
          <span className="small muted">Your text here is replaced; copy it first if you want to keep it.</span>
        </p>
      )}

      <div className="pl-layout">
        <section aria-labelledby="yaml-h" className="panel pl-yaml">
          <h2 id="yaml-h">YAML</h2>
          {isNew && (
            <div className="field-row">
              <div className="field">
                <label htmlFor="pl-name">Name</label>
                <input
                  id="pl-name"
                  value={newName}
                  required
                  autoComplete="off"
                  spellCheck={false}
                  aria-invalid={nameProblem ? true : undefined}
                  aria-describedby="pl-name-hint"
                  onChange={(e) => {
                    const v = e.target.value.trim();
                    if (NAME.test(v)) setYaml((y) => renamed(y, v));
                    setNewName(v);
                  }}
                />
                <p id="pl-name-hint" className={nameProblem ? "hint pl-bad" : "hint"}>
                  {nameProblem ?? "Fixed once saved: small letters, digits and dashes, such as customer-billing."}
                </p>
              </div>
              <div className="field">
                <label htmlFor="pl-display">Display name (optional)</label>
                <input id="pl-display" value={displayName} maxLength={200} onChange={(e) => setDisplayName(e.target.value)} />
              </div>
            </div>
          )}
          <p id="yaml-hint" className="hint">
            {editable
              ? "Tab indents by two spaces; press Escape, then Tab, to move on. Problems are listed below with their lines."
              : viewing
                ? "A saved version, shown as it was saved."
                : "Your role can read this pipeline. Stewards edit it."}
          </p>
          <YamlEditor
            ref={editor}
            id="pl-yaml"
            label={`YAML of ${title}`}
            value={text}
            onChange={setYaml}
            readOnly={!editable}
            problemLines={problemLines}
            describedBy="yaml-hint pl-check-summary"
          />
          <div className="pl-problems">
            <p id="pl-check-summary" className={problems.length > 0 ? "pl-summary pl-bad" : "pl-summary"} aria-live="polite">
              {summary}
            </p>
            {problems.length > 0 && (
              <ul className="pl-problem-list" aria-label="Problems">
                {problems.map((p, i) => (
                  <li key={i}>
                    {p.line > 0 ? (
                      <button type="button" className="pl-problem" onClick={() => goTo(p)}>
                        <span className="pl-problem-at">Line {p.line}</span>
                        {p.step && <span className="mono"> ({p.step})</span>}: {p.message}
                      </button>
                    ) : (
                      <span className="pl-problem">
                        {p.step && <span className="mono">{p.step}: </span>}
                        {p.message}
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {check && check.notes.length > 0 && (
              <details className="small">
                <summary>
                  {check.notes.length} {check.notes.length === 1 ? "note" : "notes"} (do not stop a run)
                </summary>
                <ul>
                  {check.notes.map((n, i) => (
                    <li key={i}>
                      {n.line > 0 ? `Line ${n.line}: ` : ""}
                      {n.message}
                    </li>
                  ))}
                </ul>
              </details>
            )}
          </div>
          {(editable || (isNew && steward)) && (
            <form className="pl-save" onSubmit={save}>
              <div className="field">
                <label htmlFor="pl-message">{isNew ? "Message (optional)" : "What changed"}</label>
                <input
                  id="pl-message"
                  value={message}
                  maxLength={500}
                  placeholder={isNew ? "First version" : "For example: drop test customers"}
                  onChange={(e) => setMessage(e.target.value)}
                />
              </div>
              <div className="buttons">
                <button type="submit" className="primary" disabled={saving || (!dirty && !isNew)}>
                  {saving ? "Saving…" : isNew ? "Save pipeline" : "Save new version"}
                </button>
                {!isNew && dirty && savedYaml != null && (
                  <button type="button" onClick={() => setYaml(savedYaml)}>
                    Undo my changes
                  </button>
                )}
              </div>
              <p className="hint">
                {problems.length > 0
                  ? "A pipeline with problems can be saved as a draft, but it cannot run until they are fixed."
                  : dirty
                    ? "Unsaved changes."
                    : "No unsaved changes."}
              </p>
            </form>
          )}
        </section>

        <section aria-labelledby="graph-h" className="panel pl-graph">
          <h2 id="graph-h">Steps</h2>
          {check && check.steps.length > 0 ? (
            <>
              <Suspense fallback={<p role="status">Loading the graph…</p>}>
                <PipelineDiagram steps={check.steps} edges={check.edges} problemSteps={problemSteps} />
              </Suspense>
              <div className="table-scroll" role="region" aria-label="Steps table" tabIndex={0}>
                <table className="small">
                  <thead>
                    <tr>
                      <th scope="col">Step</th>
                      <th scope="col">Type</th>
                      <th scope="col">Reads from</th>
                      <th scope="col">Columns out</th>
                    </tr>
                  </thead>
                  <tbody>
                    {check.steps.map((s) => (
                      <tr key={s.id}>
                        <th scope="row">
                          <button
                            type="button"
                            className="pl-link mono"
                            onClick={() => editor.current?.goToLine(s.line)}
                            aria-label={`${s.id}: go to line ${s.line}`}
                          >
                            {s.id}
                          </button>
                          {problemSteps.has(s.id) && <div className="pl-bad">Has a problem</div>}
                        </th>
                        <td>{s.type ?? "–"}</td>
                        <td className="mono">
                          {check.edges
                            .filter((e) => e.toStep === s.id)
                            .map((e) => `${e.fromStep}${e.fromPort === "out" ? "" : `.${e.fromPort}`}${e.toPort === "in" ? "" : ` → ${e.toPort}`}`)
                            .join(", ") || "–"}
                        </td>
                        <td>{columnsText(s)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          ) : (
            <p className="muted">{checking ? "Checking…" : "The steps appear here once the YAML can be read."}</p>
          )}
        </section>
      </div>

      {!isNew && (
        <div className="pl-lists">
          <section aria-labelledby="runs-h" className="panel">
            <h2 id="runs-h">Runs</h2>
            {runs === null && <p role="status">Loading…</p>}
            {runs?.length === 0 && <p>Not run yet.</p>}
            {runs && runs.length > 0 && (
              <div className="table-scroll" role="region" aria-label="Runs table" tabIndex={0}>
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Run</th>
                      <th scope="col">Status</th>
                      <th scope="col">Version</th>
                      <th scope="col">Asked for</th>
                      <th scope="col">Took</th>
                      <th scope="col">Rows written</th>
                    </tr>
                  </thead>
                  <tbody>
                    {runs.map((r) => (
                      <tr key={r.id}>
                        <th scope="row">
                          <a href={href.pipelineRun(r.id)}>Run {r.id}</a>
                        </th>
                        <td>
                          <RunStatus run={r} />
                          {r.error && <div className="small muted">{r.error}</div>}
                        </td>
                        <td>{r.version}</td>
                        <td className="small">
                          {formatTime(r.requestedAt)} by {r.requestedBy}
                        </td>
                        <td>{runTime(r)}</td>
                        <td>{formatNumber(r.rowsWritten)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
          <section aria-labelledby="versions-h" className="panel">
            <h2 id="versions-h">Versions</h2>
            {versions === null && <p role="status">Loading…</p>}
            {versions && (
              <ol className="pl-versions" reversed>
                {versions.map((v) => (
                  <li key={v.n}>
                    <a href={href.pipeline(name!, v.n)} aria-current={version === v.n ? "page" : undefined}>
                      Version {v.n}
                    </a>
                    {v.n === detail?.latestVersion && " (latest)"}
                    {!v.valid && (
                      <span className="pl-bad">
                        {" "}
                        · {v.problems} {v.problems === 1 ? "problem" : "problems"}
                      </span>
                    )}
                    <div className="small muted">
                      {formatTime(v.createdAt)} by {v.author}
                      {v.message && <>: {v.message}</>}
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </section>
        </div>
      )}
    </>
  );
}
