import { useCallback, useEffect, useRef, useState } from "react";
import { IconDownload } from "@tabler/icons-react";
import { formatTime, pipelinesApi, type PipelineRunEvent, type PipelineRunEventLevel } from "../api";
import { Button, StatusChip, type Tone } from "../components/ui";
import { offsetWords } from "./timeline";

const LEVEL: Record<PipelineRunEventLevel, { word: string; tone: Tone }> = {
  info: { word: "Info", tone: "neutral" },
  warn: { word: "Warning", tone: "warn" },
  error: { word: "Error", tone: "bad" },
};

const PAGE = 200;

/** Saves text as a file, through a link the browser follows at once. */
function save(text: string, name: string, type: string) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/**
 * A run's log: what happened, in order, filtered by node and by level, a page at a time, and saved
 * as text or NDJSON. While the run goes, `tick` changes with each look at the run and new events
 * are added. An event's detail may be held back (the engine's words can quote the data); it then
 * says why.
 */
export function RunLogPanel({
  runId,
  nodes,
  origin,
  tick,
  final,
}: {
  runId: number;
  nodes: string[];
  /** The run's origin on the worker's clock (ms), the same as the charts'; null to show clock times. */
  origin: number | null;
  tick: number;
  /** The run has ended: the log is read again from the start, so late writes are not missed. */
  final: boolean;
}) {
  const [node, setNode] = useState("");
  const [level, setLevel] = useState<PipelineRunEventLevel>("info");
  const [events, setEvents] = useState<PipelineRunEvent[] | null>(null);
  const [next, setNext] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const filters = useRef({ node, level });
  filters.current = { node, level };
  const lastSeq = useRef(0);
  // Each change of filter starts a new generation; answers for an older one are dropped.
  const generation = useRef(0);

  const filter = useCallback(
    () => ({
      node: filters.current.node || null,
      level: filters.current.level,
    }),
    [],
  );

  // The first page, again whenever a filter changes, and once more when the run ends.
  useEffect(() => {
    let live = true;
    generation.current++;
    lastSeq.current = 0;
    setEvents(null);
    pipelinesApi
      .events(runId, { ...filter(), limit: PAGE })
      .then((page) => {
        if (!live) return;
        setEvents(page.events);
        setNext(page.next);
        lastSeq.current = page.events.at(-1)?.seq ?? 0;
        setError(null);
      })
      .catch((e: Error) => live && setError(e.message));
    return () => {
      live = false;
    };
  }, [runId, node, level, filter, final]);

  // While the run goes: what has happened since, unless more pages are still to be shown.
  useEffect(() => {
    if (tick === 0 || final || events == null || next != null) return;
    let live = true;
    const mine = generation.current;
    const after = lastSeq.current;
    pipelinesApi
      .events(runId, { ...filter(), after, limit: PAGE })
      .then((page) => {
        if (!live || mine !== generation.current || page.events.length === 0) return;
        const fresh = page.events.filter((e) => e.seq > after);
        lastSeq.current = page.events.at(-1)!.seq;
        setEvents((old) => [...(old ?? []), ...fresh]);
        setNext(page.next);
      })
      .catch(() => {});
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tick]);

  const more = () => {
    if (next == null) return;
    setBusy(true);
    const mine = generation.current;
    pipelinesApi
      .events(runId, { ...filter(), after: next, limit: PAGE })
      .then((page) => {
        if (mine !== generation.current) return;
        lastSeq.current = page.events.at(-1)?.seq ?? lastSeq.current;
        setEvents((old) => [...(old ?? []), ...page.events]);
        setNext(page.next);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };

  const download = (format: "text" | "ndjson") => {
    setBusy(true);
    pipelinesApi
      .downloadLog(runId, format, filter())
      .then((text) =>
        save(
          text,
          `pipeline-run-${runId}-log.${format === "ndjson" ? "ndjson" : "txt"}`,
          format === "ndjson" ? "application/x-ndjson" : "text/plain",
        ),
      )
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };

  return (
    <div className="pl-log">
      <div className="pl-log-tools">
        <span className="pl-log-filter">
          <label htmlFor={`log-node-${runId}`}>Node</label>
          <select id={`log-node-${runId}`} value={node} onChange={(e) => setNode(e.target.value)}>
            <option value="">All nodes</option>
            {nodes.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </span>
        <span className="pl-log-filter">
          <label htmlFor={`log-level-${runId}`}>Level</label>
          <select id={`log-level-${runId}`} value={level} onChange={(e) => setLevel(e.target.value as PipelineRunEventLevel)}>
            <option value="info">Everything</option>
            <option value="warn">Warnings and errors</option>
            <option value="error">Errors only</option>
          </select>
        </span>
        <span className="pl-log-downloads">
          <Button size="sm" icon={<IconDownload size={16} aria-hidden="true" />} onClick={() => download("text")} disabled={busy}>
            Download as text
          </Button>
          <Button size="sm" variant="ghost" onClick={() => download("ndjson")} disabled={busy}>
            NDJSON
          </Button>
        </span>
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {events == null ? (
        !error && <p className="muted">Loading the log…</p>
      ) : events.length === 0 ? (
        <p className="muted">{node || level !== "info" ? "Nothing in the log matches." : "Nothing has been logged yet."}</p>
      ) : (
        <div className="pl-log-scroll" role="region" aria-label="Log entries" tabIndex={0}>
          <ol className="pl-log-list" aria-label="The run's log">
            {events.map((e) => (
              <li key={e.seq} className={`pl-log-event is-${e.level}`}>
                <span className="pl-log-time small mono" title={formatTime(e.at)}>
                  {origin != null ? `+${offsetWords(Math.max(0, Date.parse(e.at) - origin))}` : formatTime(e.at)}
                </span>
                <StatusChip tone={LEVEL[e.level]?.tone ?? "neutral"}>{LEVEL[e.level]?.word ?? e.level}</StatusChip>
                <span className="pl-log-text">
                  {e.text}
                  {e.detail && <span className="pl-log-detail small">{e.detail}</span>}
                  {e.withheld && <span className="pl-log-detail small muted">Detail held back. {e.withheld}</span>}
                </span>
              </li>
            ))}
          </ol>
        </div>
      )}
      {next != null && (
        <Button size="sm" onClick={more} disabled={busy}>
          Show more
        </Button>
      )}
    </div>
  );
}
