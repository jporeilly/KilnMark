import { useState } from "react";
import type { PipelineColumn, PipelineNodeOutline } from "../../api";
import { plural } from "../../api";

/** A port with more columns than this starts folded, so its name and count still show at a glance. */
export const FOLD_ABOVE = 10;

/**
 * The columns leaving a node, one list per output port (out, rejected, errors, a route's ports),
 * each column with its type. A port with many columns starts folded; one whose columns are known
 * only when it runs says so.
 */
export function PortColumns({ outline, label }: { outline: PipelineNodeOutline; label: string }) {
  const ports = Object.entries(outline.columns);
  if (ports.length === 0) return <p className="small muted">This node has no outputs.</p>;
  return (
    <ul className="cv-port-list" aria-label={label}>
      {ports.map(([port, cols]) => (
        <li key={port} className="cv-port-cols">
          {cols == null ? (
            <p className="small">
              <span className="cv-port-name mono">{port}</span> <span className="muted">columns known when it runs</span>
            </p>
          ) : cols.length === 0 ? (
            <p className="small">
              <span className="cv-port-name mono">{port}</span> <span className="muted">no columns</span>
            </p>
          ) : (
            <PortDetails port={port} cols={cols} />
          )}
        </li>
      ))}
    </ul>
  );
}

/**
 * One port's columns, folded when there are many. Whether it starts open is decided once, so a check
 * that changes the count never opens or folds what the reader chose.
 */
function PortDetails({ port, cols }: { port: string; cols: PipelineColumn[] }) {
  const [open] = useState(cols.length <= FOLD_ABOVE);
  return (
    <details open={open || undefined}>
      <summary className="small">
        <span className="cv-port-name mono">{port}</span> <span className="muted">{plural(cols.length, "column")}</span>
      </summary>
      <ul className="cv-col-list small">
        {cols.map((c) => (
          <li key={c.name}>
            <span className="mono cv-col-name">{c.name}</span> <span className="muted">{c.type}</span>
          </li>
        ))}
      </ul>
    </details>
  );
}
