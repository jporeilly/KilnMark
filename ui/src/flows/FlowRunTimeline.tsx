import { Fragment, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { formatNumber, pipelinesApi, type FlowRunDetail, type FlowTry, type PipelineRunDetail } from "../api";
import { Icon } from "../components/icons";
import { href } from "../router";
import { formatDuration } from "../pipelines/RunStatus";
import { offsetWords, pct, ticks } from "../pipelines/timeline";
import { FlowStatus, flowStatusWords } from "./RunFlow";
import { openAtFirst, runTree, runsWanted, treeRowWords, type TreeRow } from "./runTree";
import "../pipelines/pipelines.css";
import "./flows.css";

const took = (ms: number) => formatDuration(Math.round(ms));
const clock = (iso: string | null | undefined) => (iso ? new Date(iso).toLocaleTimeString() : "–");

/** The icon a row shows: the flow, a node by its type, an item, or a pipeline's node. */
function rowIcon(r: TreeRow): string | null {
  if (r.kind === "flow") return "flows";
  if (r.kind === "step" || r.kind === "item") return null;
  switch (r.type) {
    case "run-pipeline":
    case "for-each":
      return "pipelines";
    case "sql":
    case "check-source":
      return "database";
    case "wait":
    case "wait-for":
      return "clock";
    case "notify":
      return "bell";
    default:
      return "check";
  }
}

/** The run in one sentence: what started when, how long it took and how it ended. */
export function runSentence(detail: FlowRunDetail, now: number): string {
  const r = detail.run;
  if (!r.startedAt) return `${r.flow} has not started yet.`;
  const end = r.finishedAt ? Date.parse(r.finishedAt) : now;
  const length = took(Math.max(0, end - Date.parse(r.startedAt)));
  const going = r.status === "running" || r.status === "queued";
  return going
    ? `${r.flow} started ${clock(r.startedAt)} and has been running for ${length}.`
    : `${r.flow} started ${clock(r.startedAt)}, took ${length} and ${flowStatusWords(r.status).toLowerCase()}.`;
}

/**
 * The run timeline: the flow, each node, a for-each node's items and the nodes of each pipeline run
 * they started, nested as they ran, each bar on one time axis with how long it took at its end.
 * A row opens and closes with its arrow; choosing a row shows everything recorded about it below.
 * Pipeline runs are read as their rows open, and again while they run.
 */
export function FlowRunTimeline({ detail, now }: { detail: FlowRunDetail; now: number }) {
  const [open, setOpen] = useState<Set<string>>(() => openAtFirst(detail));
  const [runs, setRuns] = useState<Map<number, PipelineRunDetail>>(new Map());
  const [chosen, setChosen] = useState<string>("flow");
  const [failed, setFailed] = useState<Set<number>>(new Set());
  const [said, setSaid] = useState("");
  const asked = useRef(new Set<number>());
  /** Requests on their way, by run: a run is asked for again only once the last answer is in. */
  const inFlight = useRef(new Set<number>());

  // Rows that open later (nodes that start after the page loaded) open as they appear.
  const known = useRef(new Set<string>(openAtFirst(detail)));
  useEffect(() => {
    const fresh = [...openAtFirst(detail)].filter((id) => !known.current.has(id));
    if (fresh.length === 0) return;
    fresh.forEach((id) => known.current.add(id));
    setOpen((o) => new Set([...o, ...fresh]));
  }, [detail]);

  // Read the pipeline runs open rows show: once when finished, again on each refresh while they run.
  // An answer is kept whenever it comes while the page is shown; one that fails may be asked again.
  const shown = useRef(true);
  useEffect(() => {
    shown.current = true;
    return () => {
      shown.current = false;
    };
  }, []);
  const ask = (id: number) => {
    if (inFlight.current.has(id)) return;
    inFlight.current.add(id);
    asked.current.add(id);
    pipelinesApi
      .runDetail(id)
      .then((d) => {
        if (!shown.current) return;
        setFailed((f) => (f.has(id) ? new Set([...f].filter((x) => x !== id)) : f));
        // A finished run is never replaced by an older answer that still said it was running.
        setRuns((m) => {
          const had = m.get(id);
          const hadEnded = had && had.run.status !== "queued" && had.run.status !== "running";
          return hadEnded ? m : new Map(m).set(id, d);
        });
      })
      .catch(() => shown.current && setFailed((f) => new Set(f).add(id)))
      .finally(() => inFlight.current.delete(id));
  };
  useEffect(() => {
    for (const id of runsWanted(detail, open)) {
      const have = runs.get(id);
      const going = have && (have.run.status === "queued" || have.run.status === "running");
      if (have && !going) continue;
      if (!have && asked.current.has(id)) continue;
      ask(id);
    }
    // runs is read, not watched: a run read once is not asked for again unless it is still going.
  }, [detail, open]);
  const choose = (r: TreeRow) => {
    setChosen(r.id);
    setSaid(`Details of ${r.kind === "flow" ? "the flow" : r.label} are below the chart.`);
  };

  const tree = useMemo(() => runTree(detail, runs, open, now), [detail, runs, open, now]);
  if (!tree) return <p className="muted">The timeline starts when the run does.</p>;
  const { span, rows } = tree;
  const marks = ticks(span);
  const selected = rows.find((r) => r.id === chosen) ?? rows[0]!;
  const toggle = (id: string) =>
    setOpen((o) => {
      const next = new Set(o);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  return (
    <>
      <p className="run-sentence">{runSentence(detail, now)} Choose a row for everything recorded about it.</p>
      <div className="ft-chart">
        <div className="ft-head" aria-hidden="true">
          <span />
          <div className="ft-lane ft-axis small">
            {marks.map((t) => (
              <span key={t} className="ft-tick" style={{ left: pct(t, span) }}>
                {offsetWords(t)}
              </span>
            ))}
          </div>
        </div>
        <ul className="ft-rows" aria-label="The run, node by node">
          {rows.map((r) => {
            const words = treeRowWords(r, took);
            const icon = rowIcon(r);
            const isOpen = open.has(r.id);
            // What an open row with no rows under it says, on a line of its own under it.
            const runFailed = r.loading && r.pipelineRunId != null && failed.has(r.pipelineRunId);
            const note = !isOpen
              ? null
              : runFailed
                ? "failed"
                : r.loading
                  ? "Loading its pipeline run…"
                  : r.waiting
                    ? "Its pipeline run is waiting to start."
                    : r.untimed
                      ? "Its pipeline run's nodes were not timed."
                      : null;
            return (
              <Fragment key={r.id}>
              <li className={`ft-row${r.id === selected.id ? " ft-chosen" : ""}`}>
                <div className="ft-label" style={{ paddingLeft: `${r.depth * 18}px` }}>
                  {r.expandable ? (
                    <button
                      type="button"
                      className="ft-toggle"
                      aria-expanded={isOpen}
                      aria-label={`${isOpen ? "Close" : "Open"} ${r.label}`}
                      onClick={() => toggle(r.id)}
                    >
                      <span aria-hidden="true">{isOpen ? "▾" : "▸"}</span>
                    </button>
                  ) : (
                    <span className="ft-toggle-space" aria-hidden="true" />
                  )}
                  {icon ? (
                    <Icon name={icon} size={14} className={`ft-icon ft-icon--${r.kind}`} />
                  ) : (
                    <span className={`ft-dot ft-dot--${r.kind}`} aria-hidden="true" />
                  )}
                  <button
                    type="button"
                    className="ft-name mono"
                    aria-current={r.id === selected.id ? "true" : undefined}
                    aria-label={words}
                    onClick={() => choose(r)}
                  >
                    {r.label}
                  </button>
                  {r.badge && <span className="ft-badge">{r.badge}</span>}
                </div>
                <div className="ft-lane" onClick={() => choose(r)} title={words} aria-hidden="true">
                  {marks.map((t) => (
                    <span key={t} className="ft-grid" style={{ left: pct(t, span) }} />
                  ))}
                  {r.start != null && (
                    <>
                      <span
                        className={`ft-bar ft-${r.status} ft-bar--${r.kind}`}
                        style={{ left: pct(r.start, span), width: pct((r.end ?? r.start) - r.start, span) }}
                      />
                      {r.end != null && (
                        <span className="run-took" style={{ left: pct(r.end, span) }}>
                          {took(r.end - r.start)}
                        </span>
                      )}
                    </>
                  )}
                  {r.status === "skipped" && <span className="run-took">skipped</span>}
                </div>
              </li>
              {note && (
                <li className="ft-row ft-note">
                  <span className="small muted" style={{ paddingLeft: `${(r.depth + 1) * 18 + 26}px` }}>
                    {note === "failed" ? (
                      <>
                        Could not read its pipeline run.{" "}
                        <button type="button" className="link-button" onClick={() => ask(r.pipelineRunId!)}>
                          Try again
                        </button>
                      </>
                    ) : (
                      note
                    )}
                  </span>
                </li>
              )}
              </Fragment>
            );
          })}
        </ul>
      </div>
      <p className="visually-hidden" aria-live="polite">
        {said}
      </p>
      <RowDetails row={selected} detail={detail} />
    </>
  );
}

/** Each try of a node or item that was tried again (ADR 0041): its number, how it ended and its run. */
function Tries({ tries }: { tries: FlowTry[] }) {
  return (
    <ol className="run-tries">
      {tries.map((t) => (
        <li key={t.pipelineRunId}>
          Try {t.number}: <FlowStatus status={t.status === "queued" ? "running" : t.status} />{" "}
          <a href={href.pipelineRun(t.pipelineRunId)}>Run {t.pipelineRunId}</a>
        </li>
      ))}
    </ol>
  );
}

/** Everything recorded about the chosen row, in words. */
function RowDetails({ row: r, detail }: { row: TreeRow; detail: FlowRunDetail }) {
  const facts: [string, ReactNode][] = [];
  const span = r.start != null && r.end != null ? took(r.end - r.start) : r.live ? "still running" : "–";
  if (r.kind === "flow") {
    const run = detail.run;
    facts.push(["Status", <FlowStatus key="s" status={run.status} stopping={run.cancelRequested} />]);
    facts.push(["Started", clock(run.startedAt)], ["Ended", clock(run.finishedAt)], ["Took", span]);
    if (run.pipelineRuns != null) facts.push(["Pipeline runs", formatNumber(run.pipelineRuns)]);
    if (run.rowsWritten != null) facts.push(["Rows written", formatNumber(run.rowsWritten)]);
    if (run.qualityScore != null) facts.push(["Lowest quality score", `${Math.round(run.qualityScore * 100)}%`]);
    if (run.error) facts.push(["Why it ended", run.error]);
  } else if (r.kind === "node" && r.flowNode) {
    const n = r.flowNode;
    facts.push(["Type", n.type], ["Status", <FlowStatus key="s" status={n.status} />]);
    facts.push(["Started", clock(n.startedAt)], ["Ended", clock(n.finishedAt)], ["Took", span]);
    if (n.pipeline) facts.push(["Pipeline", <a key="p" href={href.pipeline(n.pipeline)}>{n.pipeline}</a>]);
    if (n.pipelineRunId != null)
      facts.push(["Pipeline run", <a key="r" href={href.pipelineRun(n.pipelineRunId)}>Run {n.pipelineRunId}</a>]);
    const t = n.pipelineRunTimes;
    if (t?.requestedAt && t.startedAt)
      facts.push(["Waited for a worker", took(Math.max(0, Date.parse(t.startedAt) - Date.parse(t.requestedAt)))]);
    if (n.message) facts.push(["Message", n.message]);
    const tries = (n.tries ?? []).filter((t) => t.item == null);
    if (tries.length > 1) facts.push(["Tries", <Tries key="t" tries={tries} />]);
    for (const [k, v] of Object.entries(n.reported ?? {})) {
      if (v != null && typeof v !== "object") facts.push([`Reported ${k}`, String(v)]);
    }
  } else if (r.kind === "item" && r.item) {
    const i = r.item;
    facts.push(["Value", i.value == null ? "withheld" : String(i.value)], ["Status", <FlowStatus key="s" status={i.status === "queued" ? "running" : i.status} />]);
    facts.push(["Started", clock(i.startedAt)], ["Ended", clock(i.finishedAt)], ["Took", span]);
    if (i.pipelineRunId != null)
      facts.push(["Pipeline run", <a key="r" href={href.pipelineRun(i.pipelineRunId)}>Run {i.pipelineRunId}</a>]);
    const node = detail.nodes.find((x) => x.items?.includes(i));
    const tries = (node?.tries ?? []).filter((t) => t.item === i.item);
    if (tries.length > 1) facts.push(["Tries", <Tries key="t" tries={tries} />]);
  } else if (r.kind === "step" && r.step) {
    const s = r.step;
    facts.push(["Type", s.type], ["Status", <FlowStatus key="s" status={r.status === "queued" ? "running" : r.status} />]);
    facts.push(["Took", span]);
    if (s.rowsIn != null) facts.push(["Rows in", formatNumber(s.rowsIn)]);
    if (s.rowsOut != null) facts.push(["Rows out", formatNumber(s.rowsOut)]);
    const q = r.pipelineRun?.quality?.find((x) => x.node === s.node);
    if (q?.measured) facts.push(["Quality score", `${Math.round(q.measured.overall * 100)}%`]);
    if (s.error) facts.push(["Error", s.error]);
    if (r.pipelineRunId != null)
      facts.push(["In pipeline run", <a key="r" href={href.pipelineRun(r.pipelineRunId)}>Run {r.pipelineRunId}</a>]);
  }
  const title = r.kind === "flow" ? `Flow ${r.label}` : r.kind === "step" ? `Pipeline node ${r.label}` : r.kind === "item" ? r.label : `Node ${r.label}`;
  return (
    <div className="run-details">
      <h3>{title}</h3>
      <dl>
        {facts.map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd>{v}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}
