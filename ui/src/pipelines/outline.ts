import type { PipelineNodeOutline } from "../api";

/** The columns leaving a node, in words, for its label and the box shown on hover and focus. */
export function columnsText(node: PipelineNodeOutline): string {
  const ports = Object.entries(node.columns);
  if (ports.length === 0) return "No outputs";
  return ports
    .map(([port, cols]) =>
      cols == null
        ? `${port}: columns known when it runs`
        : `${port}: ${cols.length === 0 ? "no columns" : cols.map((c) => `${c.name} (${c.type})`).join(", ")}`,
    )
    .join("; ");
}
