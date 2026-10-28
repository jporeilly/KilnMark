import { useEffect, useState, type KeyboardEvent, type MouseEvent } from "react";
import { pipelinesApi, type PipelineRunDetail, type PipelineRunEvent } from "../api";
import { rateWords } from "./live";
import { FULL, nearest, ratePath, rates, type RateLine } from "./rates";
import { offsetWords, pct, runOrigin, shareWords, ticks, type Clock } from "./timeline";

const W = 1000;
const H = 36;

/** A point in words: "At 2.4 s: 8,351 rows/s; output queue 75% full". */
function pointWords(line: RateLine, i: number): string {
  const p = line.points[i];
  return `At ${offsetWords(p.t)}: ${rateWords(p.rate)}${line.hasOutput ? `; output queue ${shareWords(p.fill)} full` : ""}`;
}

/**
 * One step's rows a second over the run, with a band under it for how full its output queue was
 * (the next step holding it up). Hover, or focus it and use the arrow keys, to read a point.
 */
function Spark({ line, span }: { line: RateLine; span: number }) {
  const [at, setAt] = useState<number | null>(null);
  const move = (e: MouseEvent<HTMLDivElement>) => {
    const box = e.currentTarget.getBoundingClientRect();
    setAt(nearest(line.points, ((e.clientX - box.left) / Math.max(1, box.width)) * span));
  };
  const key = (e: KeyboardEvent<HTMLDivElement>) => {
    const last = line.points.length - 1;
    const now = at ?? 0;
    const next =
      e.key === "ArrowRight"
        ? Math.min(last, now + 1)
        : e.key === "ArrowLeft"
          ? Math.max(0, now - 1)
          : e.key === "Home"
            ? 0
            : e.key === "End"
              ? last
              : null;
    if (next != null) {
      e.preventDefault();
      setAt(next);
    }
  };
  const x = (t: number) => (t / span) * W;
  const p = at == null ? null : line.points[at];
  const id = `rate-${line.step}`;
  return (
    <div
      className="pl-rt-chart"
      tabIndex={0}
      role="group"
      aria-label={`Rows a second through ${line.step} over the run; use the arrow keys to read each point`}
      aria-describedby={`${id}-read`}
      onMouseMove={move}
      onMouseLeave={() => setAt(null)}
      onFocus={() => setAt((a) => a ?? line.points.length - 1)}
      onBlur={() => setAt(null)}
      onKeyDown={key}
    >
      <svg className="pl-rt-line" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" aria-hidden="true">
        <path d={ratePath(line.points, span, line.peak, W, H)} vectorEffect="non-scaling-stroke" />
        {p && <line className="pl-rt-cursor" x1={x(p.t)} x2={x(p.t)} y1={0} y2={H} vectorEffect="non-scaling-stroke" />}
      </svg>
      {line.hasOutput && (
        <svg className="pl-rt-band" viewBox={`0 0 ${W} 10`} preserveAspectRatio="none" aria-hidden="true">
          {line.points.map((q, i) => {
            const next = line.points[i + 1]?.t ?? span;
            return q.fill > 0 ? (
              <rect
                key={i}
                x={x(q.t)}
                width={Math.max(1, x(next) - x(q.t))}
                y={10 - Math.max(2, q.fill * 10)}
                height={Math.max(2, q.fill * 10)}
                className={q.fill >= FULL ? "is-full" : undefined}
              />
            ) : null;
          })}
        </svg>
      )}
      <span id={`${id}-read`} className="pl-rt-read small" aria-live="polite">
        {p ? pointWords(line, at!) : ""}
      </span>
    </div>
  );
}

/**
 * Rows a second through the run (docs/guide/pipelines.md, "Reading a run"): a line per step from the
 * progress snapshots, with a band under it for how full its output queue was (Held up: the next step
 * not keeping up), its peak and average at the right, and the run's warnings, errors and gates setting
 * rows aside marked on the time axis. A table says the same.
 */
export function RunRates({ detail, clock, tick }: { detail: PipelineRunDetail; clock?: Clock; tick: number }) {
  const r = rates(detail, clock);
  const origin = runOrigin(detail);
  const [marks, setMarks] = useState<PipelineRunEvent[]>([]);
  const id = detail.run.id;
  useEffect(() => {
    let live = true;
    pipelinesApi
      .events(id, { level: "warn", limit: 200 })
      .then((page) => live && setMarks(page.events))
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [id, tick]);
  if (!r || origin == null) {
    const unfinished = detail.run.status === "queued" || detail.run.status === "running";
    return (
      <p className="muted">
        {unfinished ? "Rows a second appear after the run's first few seconds." : "This run's rates were not kept."}
      </p>
    );
  }
  const axis = ticks(r.span);
  const shown = marks
    .map((e) => ({ e, t: Date.parse(e.at) - origin }))
    .filter((m) => m.t >= 0 && m.t <= r.span)
    .slice(0, 30);
  return (
    <div className="pl-rt">
      <div className="pl-rt-axis">
        <span className="pl-rt-name" />
        <span className="pl-tl-track pl-rt-ticks" aria-hidden="true">
          {axis.map((x) => (
            <span key={x} className="pl-tl-axis-tick" style={{ left: pct(x, r.span) }}>
              {offsetWords(x)}
            </span>
          ))}
          {shown.map((m, i) => (
            <span
              key={m.e.seq}
              className={`pl-rt-mark is-${m.e.level}`}
              style={{ left: pct(m.t, r.span) }}
              title={`${offsetWords(m.t)}: ${m.e.text}`}
            >
              {i + 1}
            </span>
          ))}
        </span>
        <span className="pl-rt-value" />
      </div>
      <ul className="pl-rt-rows" aria-label="Rows a second through each step">
        {r.lines.map((line) => (
          <li key={line.step} className="pl-rt-row">
            <span className="pl-rt-name mono pl-tl-step">{line.step}</span>
            <Spark line={line} span={r.span} />
            <span className="pl-rt-value small">
              <strong>peak {rateWords(line.peak)}</strong>
              {line.hasOutput && line.fullShare > 0 && (
                <span className="pl-rt-held">output queue full {shareWords(line.fullShare)} of the time</span>
              )}
            </span>
          </li>
        ))}
      </ul>
      <p className="small muted pl-tl-key" aria-hidden="true">
        <span className="pl-rt-key-line" /> Rows a second <span className="pl-rt-key-band" /> Held up: how full its output queue
        was <span className="pl-rt-key-mark">1</span> Marked on the time axis
      </p>
      {shown.length > 0 && (
        <ol className="small pl-rt-marks" aria-label="Marked on the time axis">
          {shown.map((m) => (
            <li key={m.e.seq}>
              <span className="mono">+{offsetWords(m.t)}</span> {m.e.level === "error" ? "Error" : "Warning"}: {m.e.text}
            </li>
          ))}
        </ol>
      )}
      <details className="pl-tl-table">
        <summary>Show rows a second as a table</summary>
        <div className="table-scroll" role="region" aria-label="Rows a second through each step, as a table" tabIndex={0}>
          <table className="small">
            <thead>
              <tr>
                <th scope="col">Step</th>
                <th scope="col">Peak</th>
                <th scope="col">Average while moving rows</th>
                <th scope="col">Output queue full</th>
              </tr>
            </thead>
            <tbody>
              {r.lines.map((line) => (
                <tr key={line.step}>
                  <th scope="row" className="mono">
                    {line.step}
                  </th>
                  <td>{rateWords(line.peak)}</td>
                  <td>{rateWords(line.average)}</td>
                  <td>{line.hasOutput ? `${shareWords(line.fullShare)} of the time` : "No output"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  );
}
