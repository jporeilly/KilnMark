import type { FlowRunDetail } from "../api";
import { href } from "../router";
import { offsetWords, pct, ticks } from "../pipelines/timeline";
import { chartRowWords, flowChart } from "./chart";
import "./flows.css";

/**
 * The run chart: a bar per node on one time axis from the run's start, coloured by status (and
 * said in words, in each bar's label and the list for screen readers), a skipped node hatched
 * across its row. A bar whose node started a pipeline run links to it. `now` draws running bars.
 */
export function FlowRunChart({ detail, now }: { detail: FlowRunDetail; now: number }) {
  const chart = flowChart(detail, now);
  if (!chart) return <p className="muted">The chart starts when the run does.</p>;
  const { span, rows } = chart;
  return (
    <div className="fc-chart" role="list" aria-label="When each node ran">
      {rows.map((r) => {
        const words = chartRowWords(r);
        const bar =
          r.start == null ? null : (
            <span
              className={`fc-bar fc-${r.status}`}
              style={{ left: pct(r.start, span), width: `calc(${pct((r.end ?? r.start) - r.start, span)} + 3px)` }}
            />
          );
        return (
          <div key={r.node} role="listitem" style={{ display: "contents" }}>
            <span className="fc-name mono small">{r.node}</span>
            <div className={`fc-track${r.status === "skipped" ? " fc-skipped" : ""}`} title={words}>
              {r.pipelineRunId != null && bar ? (
                <a href={href.pipelineRun(r.pipelineRunId)} aria-label={`${words}. Open pipeline run ${r.pipelineRunId}`}>
                  {bar}
                </a>
              ) : (
                <span className="visually-hidden">{words}</span>
              )}
              {r.pipelineRunId == null && bar}
              {r.start == null && (
                <span className="fc-track-words small" aria-hidden="true">
                  {r.status === "skipped" ? "Skipped" : "Not started"}
                </span>
              )}
            </div>
          </div>
        );
      })}
      <span aria-hidden="true" />
      <div className="fc-axis small" aria-hidden="true">
        {ticks(span).map((t) => (
          <span key={t} className="fc-tick" style={{ left: pct(t, span) }}>
            {offsetWords(t)}
          </span>
        ))}
      </div>
    </div>
  );
}
