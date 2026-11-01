import { useEffect, useId, useRef, useState } from "react";
import { IconPlayerPlay, IconPlayerStop } from "@tabler/icons-react";
import { ApiError, allows, formatNumber, pipelinesApi, type AccessRole, type NodePreview } from "../../api";
import { PreviewTable } from "../../components/PreviewTable";

/** The last preview shown in the Preview tab, kept by the canvas while another tab is shown. */
export type PreviewResult = { node: string; port: string; yaml: string; view: NodePreview };

/** Asks the panel to run a preview now (the node's context menu): `n` changes on each ask. */
export type PreviewAsk = { node: string; n: number };

const ROW_CHOICES = [10, 100, 500, 1000];

/** "0.4 seconds", "12 seconds". */
export function secondsWords(ms: number): string {
  const s = ms / 1000;
  const text = s < 10 ? s.toFixed(1) : Math.round(s).toString();
  return text === "1.0" ? "1 second" : `${text} seconds`;
}

/** What the preview found, in one sentence. */
export function previewSummary(v: NodePreview): string {
  const rows = `${formatNumber(v.rowCount)} ${v.rowCount === 1 ? "row" : "rows"}`;
  const where = `${v.node}${v.port === "out" ? "" : ` (${v.port})`}`;
  if (v.rowCount === 0) return `No rows left ${where}. It took ${secondsWords(v.millis)}.`;
  if (v.complete) return `All ${rows} leaving ${where}, in ${secondsWords(v.millis)}.`;
  if (v.truncated) return `The first ${rows} leaving ${where}, in ${secondsWords(v.millis)}: the preview stopped at its size limit.`;
  return `The first ${rows} leaving ${where}, in ${secondsWords(v.millis)}.`;
}

/**
 * The Preview tab of the panel under the canvas: runs the pipeline as it stands up to the selected node, without
 * writing anything, and shows the first rows leaving one of its outputs, with their columns and
 * kinds, how long it took and what was masked. Designers and admins only; only designers see sensitive values.
 */
export function NodePreviewPanel({
  yaml,
  node,
  outputs,
  kind,
  role,
  ask,
  last,
  onResult,
  onAsked,
}: {
  yaml: string;
  node: string;
  outputs: string[];
  kind: string | null;
  role: AccessRole;
  ask: PreviewAsk | null;
  last: PreviewResult | null;
  onResult: (r: PreviewResult | null) => void;
  /** Called once the ask has been acted on, so the canvas clears it and switching back does not run it again. */
  onAsked?: () => void;
}) {
  const id = useId();
  const mine = last && last.node === node ? last : null;
  const [port, setPort] = useState(mine?.port ?? (outputs.includes("out") ? "out" : (outputs[0] ?? "out")));
  const [rows, setRows] = useState(100);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ctl = useRef<AbortController | null>(null);
  const allowed = allows(role, "designer");
  const writes = kind === "sink" || outputs.length === 0;

  const stop = () => {
    ctl.current?.abort();
    ctl.current = null;
    setBusy(false);
  };

  const run = (which = port) => {
    if (!allowed || writes) return;
    ctl.current?.abort();
    const c = new AbortController();
    ctl.current = c;
    setBusy(true);
    setError(null);
    const asked = yaml;
    pipelinesApi
      .preview({ yaml: asked, node, port: which, rows }, c.signal)
      .then((view) => {
        if (c.signal.aborted) return;
        onResult({ node, port: which, yaml: asked, view });
      })
      .catch((e: unknown) => {
        if (c.signal.aborted) return;
        setError(e instanceof ApiError || e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (ctl.current === c) {
          ctl.current = null;
          setBusy(false);
        }
      });
  };

  // The context menu's Preview: run now, once per ask.
  const asked = useRef<number | null>(null);
  useEffect(() => {
    if (ask && ask.node === node && asked.current !== ask.n) {
      asked.current = ask.n;
      onAsked?.();
      run();
    }
  });
  // Leaving the node or the Preview tab stops a preview still running.
  useEffect(() => () => ctl.current?.abort(), []);

  if (writes) {
    return (
      <div className="cv-preview">
        <p className="hint">This node writes rows and passes none on, so there is nothing to preview here. Preview the node that feeds it.</p>
      </div>
    );
  }
  if (!allowed) {
    return (
      <div className="cv-preview">
        <p className="hint">Previews show rows from the sources while a pipeline is built, so they are for designers and admins.</p>
      </div>
    );
  }
  const view = mine?.view ?? null;
  const stale = mine != null && mine.yaml !== yaml;
  return (
    <div className="cv-preview" aria-busy={busy}>
      <p className="hint">
        Runs {node} and the nodes before it on the pipeline as it is now, saved or not, and shows the rows leaving it. Nothing is
        written.
      </p>
      <div className="cv-preview-controls">
        {outputs.length > 1 && (
          <label className="cv-preview-field">
            <span>Output</span>
            <select
              value={port}
              onChange={(e) => {
                setPort(e.target.value);
              }}
              disabled={busy}
            >
              {outputs.map((o) => (
                <option key={o} value={o}>
                  {o}
                </option>
              ))}
            </select>
          </label>
        )}
        <label className="cv-preview-field">
          <span>Rows</span>
          <select value={rows} onChange={(e) => setRows(Number(e.target.value))} disabled={busy}>
            {ROW_CHOICES.map((n) => (
              <option key={n} value={n}>
                {formatNumber(n)}
              </option>
            ))}
          </select>
        </label>
        {busy ? (
          <button type="button" className="cv-preview-go" onClick={stop}>
            <IconPlayerStop size={16} stroke={2} aria-hidden="true" />
            Stop
          </button>
        ) : (
          <button type="button" className="primary cv-preview-go" onClick={() => run()}>
            <IconPlayerPlay size={16} stroke={2} aria-hidden="true" />
            {view ? "Preview again" : "Preview rows"}
          </button>
        )}
      </div>
      <div aria-live="polite" className="cv-preview-status">
        {busy && <p className="small">Running {node} and the nodes before it… This stops after 30 seconds.</p>}
        {!busy && view && <p className="small cv-preview-summary">{previewSummary(view)}</p>}
      </div>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {view && (
        <>
          {stale && <p className="cv-preview-note small">The pipeline has changed since this preview. Preview again to see its rows now.</p>}
          {view.masked.length > 0 && (
            <p className="cv-preview-note small" id={`${id}-masked`}>
              Values of {view.masked.join(", ")} are hidden (••••): they come from columns marked sensitive, which only designers see.
            </p>
          )}
          {view.waits.length > 0 && (
            <ul className="small cv-preview-waits" aria-label="Why it took this long">
              {view.waits.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          )}
          <PreviewTable preview={view} hint="Read live when you asked, and not kept." />
        </>
      )}
    </div>
  );
}
