import type { FlowItemRun, FlowNodeRun, FlowNodeStatus, FlowRunDetail } from "../api";
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
 * When a node ran: its own times, or for a for-each node whose items have started, from its first
 * item's start to its last item's end (still open while any item runs or the node does).
 */
function span(n: FlowNodeRun): { started: number | null; finished: number | null } {
  const runs = (n.items ?? []).filter((i) => i.startedAt);
  if (runs.length === 0) return { started: ms(n.startedAt), finished: ms(n.finishedAt) };
  const started = Math.min(...runs.map((i) => ms(i.startedAt)!));
  const open = n.status === "running" || runs.some((i) => !i.finishedAt);
  const finished = open ? null : Math.max(...runs.map((i) => ms(i.finishedAt)!));
  return { started, finished };
}

/**
 * A flow run as a chart: a row per node, in the version's order, each bar from its start to its
 * end (or to `now` while it runs), measured from when the run started; a for-each node's bar spans
 * its items' runs. Null before the run starts. `now` is the time to draw running bars to, normally
 * the page's clock.
 */
export function flowChart(detail: FlowRunDetail, now: number = Date.now()): FlowChart | null {
  const origin = ms(detail.run.startedAt) ?? Math.min(...detail.nodes.map((n) => ms(n.startedAt) ?? Infinity));
  if (!Number.isFinite(origin)) return null;
  const rows: ChartRow[] = [...detail.nodes]
    .sort((a, b) => a.position - b.position)
    .map((n) => {
      const { started, finished } = span(n);
      const live = n.status === "running" && started != null && finished == null;
      return {
        node: n.node,
        type: n.type,
        status: n.status,
        start: started == null ? null : Math.max(0, started - origin),
        // The browser's clock can be behind the server's: a running bar never ends before it starts.
        end: finished != null ? Math.max(0, finished - origin) : live ? Math.max(0, started - origin, now - origin) : null,
        live,
        pipelineRunId: n.pipelineRunId,
      };
    });
  const runEnd = ms(detail.run.finishedAt);
  const last = Math.max(0, ...rows.map((r) => r.end ?? r.start ?? 0), runEnd != null ? runEnd - origin : 0);
  return { span: Math.max(1, last), rows };
}

/** How far a for-each node's items have got, as its row says it: "3 of 5 items done, 1 failed". */
export function itemsProgress(items: FlowItemRun[]): string {
  const done = items.filter((i) => i.status === "succeeded" || i.status === "failed" || i.status === "cancelled");
  const failed = items.filter((i) => i.status === "failed").length;
  const cancelled = items.filter((i) => i.status === "cancelled").length;
  const notRun = items.filter((i) => i.status === "skipped").length;
  return [
    `${done.length} of ${items.length} ${items.length === 1 ? "item" : "items"} done`,
    ...(failed ? [`${failed} failed`] : []),
    ...(cancelled ? [`${cancelled} cancelled`] : []),
    ...(notRun ? [`${notRun} not run`] : []),
  ].join(", ");
}

/** A row in words, for its bar's label and screen readers. */
export function chartRowWords(r: ChartRow): string {
  if (r.status === "skipped") return `${r.node}: skipped`;
  if (r.start == null) return `${r.node}: not started`;
  const took = r.end != null ? offsetWords(r.end - r.start) : null;
  if (r.live) return `${r.node}: started at ${offsetWords(r.start)}, running for ${took}`;
  return `${r.node}: started at ${offsetWords(r.start)}, ${r.status}${took != null ? ` after ${took}` : ""}`;
}
