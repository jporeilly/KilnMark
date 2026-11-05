/**
 * A flow run as one nested timeline: the flow, its nodes, a for-each node's items, and the nodes of
 * each pipeline run they started, every bar on the flow run's time axis. Pure functions, unit tested.
 */
import type { FlowItemRun, FlowNodeRun, FlowRunDetail, PipelineNodeRun, PipelineRunDetail } from "../api";
import { timeline } from "../pipelines/timeline";

export type TreeKind = "flow" | "node" | "item" | "step";
/** A status in the words FlowStatus shows. */
export type TreeStatus = "pending" | "queued" | "running" | "succeeded" | "failed" | "cancelled" | "skipped";

export type TreeRow = {
  /** Unique and stable across refreshes: "flow", "n:<node>", "n:<node>/i:<item>", "…/s:<step>". */
  id: string;
  depth: number;
  kind: TreeKind;
  label: string;
  /** Beside the label: the pipeline a node runs, or the value an item ran with. */
  badge: string | null;
  /** The node's type (a flow node's or a pipeline node's). */
  type: string | null;
  status: TreeStatus;
  /** Milliseconds from the flow run's start; null when it did not happen (yet). */
  start: number | null;
  end: number | null;
  live: boolean;
  pipelineRunId: number | null;
  /** Rows under it can be shown: an item list, or a pipeline run to open. */
  expandable: boolean;
  /** Open, but its pipeline run is not read yet. */
  loading: boolean;
  /** Open, its pipeline run read, but its nodes were not timed (stopped before they started, or older). */
  untimed?: boolean;
  flowNode?: FlowNodeRun;
  item?: FlowItemRun;
  step?: PipelineNodeRun;
  pipelineRun?: PipelineRunDetail;
};

export type RunTree = { span: number; rows: TreeRow[] };

const ms = (iso: string | null | undefined) => (iso ? Date.parse(iso) : null);

/** How a pipeline's node ended, in the flow's status words. */
export function stepStatus(outcome: string | null, ended: boolean, runStatus: string): TreeStatus {
  if (outcome === "failed") return "failed";
  if (outcome === "stopped") return "cancelled";
  if (outcome === "finished" || outcome === "ended_early" || outcome === "ended") return "succeeded";
  if (!ended && (runStatus === "running" || runStatus === "queued")) return "running";
  if (runStatus === "failed") return "failed";
  if (runStatus === "cancelled") return "cancelled";
  return ended ? "succeeded" : "pending";
}

/** Every row that can open: what the timeline shows open at first is a node's pipeline run and items. */
export function openAtFirst(detail: FlowRunDetail): Set<string> {
  return new Set(
    detail.nodes.filter((n) => n.pipelineRunId != null || (n.items?.length ?? 0) > 0).map((n) => `n:${n.node}`),
  );
}

/** The pipeline runs the open rows need, by id: a node's or an item's. */
export function runsWanted(detail: FlowRunDetail, open: Set<string>): number[] {
  const ids: number[] = [];
  for (const n of detail.nodes) {
    if (!open.has(`n:${n.node}`)) continue;
    if (n.pipelineRunId != null && !(n.items?.length ?? 0)) ids.push(n.pipelineRunId);
    for (const i of n.items ?? []) {
      if (i.pipelineRunId != null && open.has(`n:${n.node}/i:${i.item}`)) ids.push(i.pipelineRunId);
    }
  }
  return ids;
}

/**
 * The tree: the flow's row, then each node in the version's order and, under an open one, its items
 * or its pipeline run's nodes; under an open item, its pipeline run's nodes. A pipeline run's nodes
 * are placed from when that run started, so their bars sit inside its parent's. `runs` holds the
 * pipeline runs read so far; `now` draws running bars.
 */
export function runTree(
  detail: FlowRunDetail,
  runs: Map<number, PipelineRunDetail>,
  open: Set<string>,
  now: number = Date.now(),
): RunTree | null {
  const origin = ms(detail.run.startedAt) ?? Math.min(...detail.nodes.map((n) => ms(n.startedAt) ?? Infinity));
  if (!Number.isFinite(origin)) return null;
  const at = (iso: string | null | undefined) => {
    const t = ms(iso);
    return t == null ? null : Math.max(0, t - origin);
  };
  const toNow = Math.max(0, now - origin);
  const rows: TreeRow[] = [];
  const flowGoing = detail.run.status === "running" || detail.run.status === "queued";
  rows.push({
    id: "flow",
    depth: 0,
    kind: "flow",
    label: detail.run.flow,
    badge: null,
    type: null,
    status: detail.run.status as TreeStatus,
    start: 0,
    end: at(detail.run.finishedAt) ?? (flowGoing ? toNow : null),
    live: flowGoing,
    pipelineRunId: null,
    expandable: false,
    loading: false,
  });

  const steps = (parent: string, depth: number, runId: number) => {
    const d = runs.get(runId);
    if (!d) return;
    const t = timeline(d);
    if (!t || t.rows.length === 0) {
      const owner = rows.find((r) => r.id === parent);
      if (owner) owner.untimed = true;
      return;
    }
    const base = at(d.run.startedAt) ?? 0;
    const going = d.run.status === "running" || d.run.status === "queued";
    for (const r of t?.rows ?? []) {
      const step = d.nodes.find((s) => s.node === r.node);
      const end = r.end != null ? base + r.end : r.running || (going && r.start != null) ? Math.max(base, toNow) : null;
      rows.push({
        id: `${parent}/s:${r.node}`,
        depth,
        kind: "step",
        label: r.node,
        badge: null,
        type: r.type,
        status: stepStatus(r.outcome, r.end != null, d.run.status),
        start: r.start == null ? null : base + r.start,
        end,
        live: r.end == null && r.start != null && going,
        pipelineRunId: runId,
        expandable: false,
        loading: false,
        step,
        pipelineRun: d,
      });
    }
  };

  for (const n of [...detail.nodes].sort((a, b) => a.position - b.position)) {
    const id = `n:${n.node}`;
    const items = n.items ?? [];
    const started = items.some((i) => i.startedAt)
      ? Math.min(...items.filter((i) => i.startedAt).map((i) => ms(i.startedAt)!))
      : ms(n.startedAt);
    const running = n.status === "running";
    const isOpen = open.has(id);
    const single = n.pipelineRunId != null && items.length === 0;
    rows.push({
      id,
      depth: 1,
      kind: "node",
      label: n.node,
      badge: n.pipeline,
      type: n.type,
      status: n.status,
      start: started == null ? null : Math.max(0, started - origin),
      end: at(n.finishedAt) ?? (running && started != null ? toNow : null),
      live: running,
      pipelineRunId: n.pipelineRunId,
      expandable: single || items.length > 0,
      loading: isOpen && single && !runs.has(n.pipelineRunId!),
      flowNode: n,
    });
    if (!isOpen) continue;
    if (single) steps(id, 2, n.pipelineRunId!);
    for (const i of items) {
      const iid = `${id}/i:${i.item}`;
      const going = i.status === "running" || i.status === "queued";
      const iOpen = open.has(iid);
      rows.push({
        id: iid,
        depth: 2,
        kind: "item",
        label: `item ${i.item + 1}`,
        badge: i.value == null ? null : String(i.value),
        type: null,
        status: i.status,
        start: at(i.startedAt),
        end: at(i.finishedAt) ?? (going && i.startedAt ? toNow : null),
        live: going && i.startedAt != null,
        pipelineRunId: i.pipelineRunId,
        expandable: i.pipelineRunId != null,
        loading: iOpen && i.pipelineRunId != null && !runs.has(i.pipelineRunId),
        item: i,
      });
      if (iOpen && i.pipelineRunId != null) steps(iid, 3, i.pipelineRunId);
    }
  }
  const last = Math.max(0, ...rows.map((r) => r.end ?? r.start ?? 0));
  return { span: Math.max(1, last), rows };
}

/** A row in words, for screen readers and the bar's title. */
export function treeRowWords(r: TreeRow, took: (ms: number) => string): string {
  const what = r.kind === "flow" ? `Flow ${r.label}` : r.kind === "item" ? `${r.label}${r.badge ? ` (${r.badge})` : ""}` : r.label;
  if (r.status === "skipped") return `${what}: skipped`;
  if (r.start == null) return `${what}: not started`;
  const t = r.end != null ? took(r.end - r.start) : null;
  if (r.live) return `${what}: running for ${t}`;
  return `${what}: ${r.status}${t != null ? ` after ${t}` : ""}`;
}
