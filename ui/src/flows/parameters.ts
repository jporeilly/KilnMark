import type { FlowParameter } from "../api";

/** A parameter's default as it is typed in the run dialog: lists comma-separated, nothing for none. */
export function defaultText(p: FlowParameter): string {
  const d = p.defaultValue;
  if (d == null) return "";
  if (Array.isArray(d)) return d.map(String).join(", ");
  return String(d);
}

/**
 * The values to send for a run, from what was typed in the run dialog: a field left as its
 * default is not sent, so the server applies the default; one with no default must be filled.
 * Numbers must be numbers; lists are comma-separated. Returns the values, or the problems, each
 * naming its parameter.
 */
export function runValues(
  params: FlowParameter[],
  typed: Record<string, string>,
): { values: Record<string, unknown>; problems: Record<string, string> } {
  const values: Record<string, unknown> = {};
  const problems: Record<string, string> = {};
  for (const p of params) {
    const raw = (typed[p.name] ?? defaultText(p)).trim();
    if (raw === "") {
      if (p.required) problems[p.name] = `Give a value for ${p.name}: it has no default.`;
      continue;
    }
    if (!p.required && raw === defaultText(p).trim()) continue;
    if (p.type === "number") {
      const n = Number(raw);
      if (!Number.isFinite(n)) problems[p.name] = `${p.name} takes a number, such as 0.02.`;
      else values[p.name] = n;
    } else if (p.type === "boolean") {
      if (raw !== "true" && raw !== "false") problems[p.name] = `${p.name} is true or false.`;
      else values[p.name] = raw === "true";
    } else if (p.type === "text list" || p.type === "number list") {
      const items = raw
        .split(",")
        .map((s) => s.trim())
        .filter((s) => s !== "");
      if (p.type === "number list") {
        const nums = items.map(Number);
        if (nums.some((n) => !Number.isFinite(n))) problems[p.name] = `${p.name} takes numbers, separated by commas.`;
        else values[p.name] = nums;
      } else {
        values[p.name] = items;
      }
    } else {
      values[p.name] = raw;
    }
  }
  return { values, problems };
}
