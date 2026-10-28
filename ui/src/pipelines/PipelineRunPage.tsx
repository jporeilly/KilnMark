import { useCallback, useEffect, useRef, useState } from "react";
import {
  allows,
  formatNumber,
  formatTime,
  pipelinesApi,
  type AccessRole,
  rating,
  type PipelineRunDetail,
  type PipelineStepQuality,
  type PipelineStepRun,
} from "../api";
import { Meter, StatusChip, type FillTone, type Tone } from "../components/ui";
import {
  belowThreshold,
  DECISION_WORDS,
  dimensionWord,
  gateWords,
  ON_FAIL_WORDS,
  percent,
  RULE_STATUS_WORDS,
} from "./quality";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { formatBytes, formatDuration, RunStatus, runTime } from "./RunStatus";
import "./pipelines.css";

const OUTCOME: Record<NonNullable<PipelineStepRun["outcome"]>, string> = {
  finished: "Finished",
  ended_early: "Ended early",
  stopped: "Stopped by the engine",
  failed: "Failed",
};

/** Rows per port, in words: "main 10, lookup 4". */
function portRows(ports: Record<string, number | null> | undefined): string {
  if (!ports || Object.keys(ports).length === 0) return "";
  return Object.entries(ports)
    .map(([port, n]) => `${port} ${n == null ? "not counted" : formatNumber(n)}`)
    .join(", ");
}

const TONE: Record<string, Tone> = { good: "good", fair: "warn", poor: "bad" };
const FILL: Record<string, FillTone> = { good: "good", fair: "warn", poor: "bad" };
const DECISION_TONE: Record<string, Tone> = { passed: "good", warned: "warn", quarantined: "orange", stopped: "bad" };
const RULE_TONE: Record<string, Tone> = { pass: "good", warn: "warn", fail: "bad", error: "neutral" };

/** A score as a chip: the percentage and its word, never colour alone. */
function ScoreChip({ score }: { score: number }) {
  const r = rating(score);
  return (
    <StatusChip tone={TONE[r.tone]}>
      {percent(score)} · {r.word}
    </StatusChip>
  );
}

/** One write's or gate's measured quality: its score, each dimension and, for a gate, each rule. */
function StepQuality({ q }: { q: PipelineStepQuality }) {
  const g = q.gate;
  const h = `q-${q.step}`;
  return (
    <li className="pl-quality-step" aria-labelledby={h}>
      <h3 id={h} className="pl-quality-head">
        <span className="mono">{q.step}</span>
        <span className="small muted">{q.role === "gate" ? "Quality gate" : "Output"}</span>
        {q.measured ? <ScoreChip score={q.measured.overall} /> : <StatusChip tone="neutral">Not measured</StatusChip>}
      </h3>
      {q.asset && (
        <p className="small">
          {q.role === "gate" ? "Rules of " : "Wrote "}
          <a href={href.asset(q.asset)} className="mono">
            {q.asset}
          </a>
        </p>
      )}
      {q.note && <p className="small muted">{q.note}</p>}
      {g && (
        <p className="small">
          <StatusChip tone={DECISION_TONE[g.decision] ?? "neutral"}>{DECISION_WORDS[g.decision] ?? g.decision}</StatusChip>{" "}
          {gateWords(g)} of {formatNumber(g.rowsIn)} checked. When rows fail: {ON_FAIL_WORDS[g.onFail]}
          {g.minScore != null ? `; lowest score ${percent(g.minScore)}` : ""}
          {g.maxFailingShare != null ? `; at most ${percent(g.maxFailingShare)} of rows failing` : ""}.
        </p>
      )}
      {g?.stopReason && <p className="small pl-bad">{g.stopReason}</p>}
      {q.measured && Object.keys(q.measured.dimensions).length > 0 && (
        <ul className="pl-dims">
          {Object.entries(q.measured.dimensions).map(([d, v]) => (
            <li key={d}>
              <span className="pl-dim-name">{dimensionWord(d)}</span>
              <Meter value={v} tone={FILL[rating(v).tone]} size="sm" />
              <span className="pl-dim-value">{percent(v)}</span>
            </li>
          ))}
        </ul>
      )}
      {q.measured && q.measured.reasons.length > 0 && (
        <ul className="small muted pl-reasons">
          {q.measured.reasons.map((r, i) => (
            <li key={i}>{r}</li>
          ))}
        </ul>
      )}
      {g && g.rules.length > 0 && (
        <div className="table-scroll" role="region" aria-label={`Rules checked by ${q.step}`} tabIndex={0}>
          <table className="small">
            <thead>
              <tr>
                <th scope="col">Rule</th>
                <th scope="col">From</th>
                <th scope="col">Result</th>
                <th scope="col">Rows failing</th>
              </tr>
            </thead>
            <tbody>
              {g.rules.map((r) => (
                <tr key={r.name}>
                  <th scope="row">
                    {r.name}
                    <div className="muted">
                      {r.type.replace(/_/g, " ")}
                      {r.field ? ` on ${r.field}` : ""} · {dimensionWord(r.dimension)}
                    </div>
                  </th>
                  <td>{r.origin === "asset" ? "The asset's rules" : "Written on the gate"}</td>
                  <td>
                    <StatusChip tone={RULE_TONE[r.status] ?? "neutral"}>{RULE_STATUS_WORDS[r.status] ?? r.status}</StatusChip>
                    {r.message && <div className="muted">{r.message}</div>}
                  </td>
                  <td>
                    {r.status === "error" || ["freshness", "row_count", "row_count_change"].includes(r.type)
                      ? "–"
                      : `${formatNumber(r.failing)} of ${formatNumber(r.total)}`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </li>
  );
}

/**
 * One pipeline run: its status and timings, each step's rows in and out per port, memory, time and
 * error, what its writes wrote (linked to the catalogue) and the lineage recorded. Looks again every
 * two seconds until the run has finished, and says when its status changes.
 */
export function PipelineRunPage({ id, role }: { id: number; role: AccessRole }) {
  useTitle(`Pipeline run ${id}`);
  const steward = allows(role, "steward");
  const [detail, setDetail] = useState<PipelineRunDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const last = useRef<string | null>(null);

  const load = useCallback(() => {
    pipelinesApi
      .runDetail(id)
      .then((d) => {
        if (last.current != null && last.current !== d.run.status) {
          setNotice(`Run ${id} is now ${d.run.status}.`);
        }
        last.current = d.run.status;
        setDetail(d);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [id]);

  useEffect(load, [load]);
  const unfinished = detail != null && (detail.run.status === "queued" || detail.run.status === "running");
  useEffect(() => {
    if (!unfinished) return;
    const t = window.setInterval(load, 2000);
    return () => window.clearInterval(t);
  }, [unfinished, load]);

  const cancel = async () => {
    try {
      await pipelinesApi.cancel(id);
      setNotice(`Asked run ${id} to stop.`);
      load();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  if (!detail) {
    return (
      <>
        <h1 tabIndex={-1}>Pipeline run {id}</h1>
        {error ? (
          <p role="alert" className="error">
            {error}
          </p>
        ) : (
          <p role="status">Loading…</p>
        )}
      </>
    );
  }
  const r = detail.run;
  // The engine does not time steps yet: the column appears once it does.
  const timed = detail.steps.some((s) => s.durationMs != null);
  return (
    <>
      <p className="small">
        <a href={href.pipelines()}>Pipelines</a> › <a href={href.pipeline(r.pipeline)}>{r.pipeline}</a>
      </p>
      <div className="pl-head">
        <h1 tabIndex={-1}>
          Run {r.id} of {r.pipeline}
        </h1>
        {steward && unfinished && !r.cancelRequested && (
          <button type="button" onClick={cancel}>
            Cancel run
          </button>
        )}
      </div>
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <section aria-labelledby="run-h" className="panel">
        <h2 id="run-h">Summary</h2>
        <dl className="pl-facts">
          <dt>Status</dt>
          <dd>
            <RunStatus run={r} />
          </dd>
          <dt>Version</dt>
          <dd>
            <a href={href.pipeline(r.pipeline, r.version)}>Version {r.version}</a>
          </dd>
          <dt>Asked for</dt>
          <dd>
            {formatTime(r.requestedAt)} by {r.requestedBy}
          </dd>
          <dt>Started</dt>
          <dd>{formatTime(r.startedAt)}</dd>
          <dt>Finished</dt>
          <dd>{formatTime(r.finishedAt)}</dd>
          <dt>Took</dt>
          <dd>{runTime(r)}</dd>
          <dt>Rows written</dt>
          <dd>{formatNumber(r.rowsWritten)}</dd>
          {r.qualityScore != null && (
            <>
              <dt>Quality</dt>
              <dd>
                <ScoreChip score={r.qualityScore} /> <span className="small muted">lowest of what it wrote</span>
                {r.qualityThreshold != null && (
                  <div className={`small${belowThreshold(r.qualityScore, r.qualityThreshold) ? " pl-bad" : ""}`}>
                    {belowThreshold(r.qualityScore, r.qualityThreshold)
                      ? `Below the pipeline's threshold of ${percent(r.qualityThreshold)}`
                      : `At or above the pipeline's threshold of ${percent(r.qualityThreshold)}`}
                  </div>
                )}
              </dd>
            </>
          )}
        </dl>
        {r.error && (
          <p className={r.status === "failed" ? "pl-bad" : "muted"}>
            <strong>{r.status === "failed" ? "Why it failed: " : "Note: "}</strong>
            {r.error}
          </p>
        )}
      </section>

      <section aria-labelledby="steps-h" className="panel">
        <h2 id="steps-h">Steps</h2>
        {detail.steps.length === 0 ? (
          <p className="muted">{unfinished ? "The steps are recorded when the run ends." : "No steps were recorded."}</p>
        ) : (
          <div className="table-scroll" role="region" aria-label="Steps of the run" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Step</th>
                  <th scope="col">How it ended</th>
                  <th scope="col">Rows in</th>
                  <th scope="col">Rows out</th>
                  <th scope="col">Peak memory</th>
                  {timed && <th scope="col">Time</th>}
                  <th scope="col">Error</th>
                </tr>
              </thead>
              <tbody>
                {detail.steps.map((s) => (
                  <tr key={s.step}>
                    <th scope="row">
                      <span className="mono">{s.step}</span>
                      <div className="small muted">{s.type}</div>
                    </th>
                    <td>
                      {s.outcome ? (
                        <span
                          className={`badge ${
                            s.outcome === "failed"
                              ? "badge-failed"
                              : s.outcome === "stopped"
                                ? "badge-running"
                                : s.outcome === "ended_early"
                                  ? "badge-none"
                                  : "badge-succeeded"
                          }`}
                        >
                          {OUTCOME[s.outcome]}
                        </span>
                      ) : (
                        "–"
                      )}
                    </td>
                    <td>
                      {formatNumber(s.rowsIn)}
                      {Object.keys(s.ports?.in ?? {}).length > 1 && (
                        <div className="small muted">{portRows(s.ports?.in)}</div>
                      )}
                    </td>
                    <td>
                      {formatNumber(s.rowsOut)}
                      {Object.keys(s.ports?.out ?? {}).length > 0 && (
                        <div className="small muted">{portRows(s.ports?.out)}</div>
                      )}
                    </td>
                    <td>{formatBytes(s.peakMemory)}</td>
                    {timed && <td>{formatDuration(s.durationMs)}</td>}
                    <td className={s.error ? "pl-bad" : undefined}>{s.error ?? "–"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {(detail.quality?.length ?? 0) > 0 && (
        <section aria-labelledby="quality-h" className="panel">
          <h2 id="quality-h">Quality</h2>
          <p className="small muted">
            Measured in the run itself: each gate from its rules, each output from the profile made as it was written.
          </p>
          <ul className="pl-quality">
            {detail.quality!.map((q) => (
              <StepQuality key={q.step} q={q} />
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="written-h" className="panel">
        <h2 id="written-h">What it wrote</h2>
        {detail.written.length === 0 ? (
          <p className="muted">{unfinished ? "Known when the run ends." : "Nothing was written."}</p>
        ) : (
          <ul className="pl-written">
            {detail.written.map((w) => (
              <li key={w.step}>
                {w.registered ? (
                  <a href={href.asset(w.urn)} className="mono">
                    {w.target}
                  </a>
                ) : (
                  <span className="mono">{w.target}</span>
                )}{" "}
                in {w.source} ({w.mode}), by step <span className="mono">{w.step}</span>: {formatNumber(w.rows)} rows.
                {!w.registered && <span className="muted"> Not in the catalogue.</span>}
              </li>
            ))}
          </ul>
        )}
        {detail.lineage.length > 0 && (
          <>
            <h3>Lineage</h3>
            <ul className="small">
              {detail.lineage.map((l, i) => (
                <li key={i}>
                  <a href={href.asset(l.output)} className="mono">
                    {l.output}
                  </a>{" "}
                  came from{" "}
                  <a href={href.asset(l.input)} className="mono">
                    {l.input}
                  </a>
                </li>
              ))}
            </ul>
          </>
        )}
        {detail.notes.length > 0 && (
          <>
            <h3>Notes</h3>
            <ul className="small">
              {detail.notes.map((n, i) => (
                <li key={i}>{n}</li>
              ))}
            </ul>
          </>
        )}
      </section>
    </>
  );
}
