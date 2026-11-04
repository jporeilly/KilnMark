import type { FlowNodeStatus, FlowRunDetail } from "../api";
import { offsetWords } from "../pipelines/timeline";

/** One node of a flow run on the chart: when it started and ended, in ms from the run's start. */
export type ChartRow = {
  node: string;
  type: string;
  status: FlowNodeStatus;
  start: number | null;
  end: number | null;
  /** Still going: its bar runs to now. */
  live: boolean;
  pipelineRunId: number | null;
};
export type FlowChart = { span: number; rows: ChartRow[] };

const ms = (iso: string | null) => (iso ? new Date(iso).getTime() : null);

/**
 * A flow run as a chart: a row per node, in the version's order, each bar from its start to its
 * end (or to `now` while it runs), measured from when the run started. Null before the run starts.
 * `now` is the time to draw running bars to, normally the page's clock.
 */
export function flowChart(detail: FlowRunDetail, now: number = Date.now()): FlowChart | null {
  const origin = ms(detail.run.startedAt) ?? Math.min(...detail.nodes.map((n) => ms(n.startedAt) ?? Infinity));
  if (!Number.isFinite(origin)) return null;
  const rows: ChartRow[] = [...detail.nodes]
    .sort((a, b) => a.position - b.position)
    .map((n) => {
      const started = ms(n.startedAt);
      const finished = ms(n.finishedAt);
      const live = n.status === "running" && started != null && finished == null;
      return {
        node: n.node,
        type: n.type,
        status: n.status,
        start: started == null ? null : Math.max(0, started - origin),
        end: finished != null ? Math.max(0, finished - origin) : live ? Math.max(0, now - origin) : null,
        live,
        pipelineRunId: n.pipelineRunId,
      };
    });
  const runEnd = ms(detail.run.finishedAt);
  const last = Math.max(0, ...rows.map((r) => r.end ?? r.start ?? 0), runEnd != null ? runEnd - origin : 0);
  return { span: Math.max(1, last), rows };
}

/** A row in words, for its bar's label and screen readers. */
export function chartRowWords(r: ChartRow): string {
  if (r.status === "skipped") return `${r.node}: skipped`;
  if (r.start == null) return `${r.node}: not started`;
  const took = r.end != null ? offsetWords(r.end - r.start) : null;
  if (r.live) return `${r.node}: started at ${offsetWords(r.start)}, running for ${took}`;
  return `${r.node}: started at ${offsetWords(r.start)}, ${r.status}${took != null ? ` after ${took}` : ""}`;
}
