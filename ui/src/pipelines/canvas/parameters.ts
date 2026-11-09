/**
 * A pipeline's parameters in the canvas: the checks the Parameters panel makes before it writes one
 * (the same the server makes when it reads `parameters:`, kiln-core Parameters.java), what a
 * `${name}` chip says about the value it stands for, and which names a setting puts in.
 */
import { PARAMETER_KINDS, parameterKind, type DeclaredParameter, type ParameterKind, type ParameterSpec, type Value } from "./model";

/** A parameter name: a small letter, then up to 63 small letters, digits and _. */
export const PARAMETER_NAME = /^[a-z][a-z0-9_]{0,63}$/;
/** Words a condition reads as part of its language, so never parameter names. */
export const TAKEN_WORDS = ["and", "or", "not", "true", "false", "good", "fair", "poor"];
/** Longest text value, and most values in a list (the server's limits). */
export const MAX_TEXT = 1000;
export const MAX_LIST = 200;
/** A server variable as a whole default: `${vars.region}`. */
const VARIABLE = /^\$\{\s*vars\.([^}]*?)\s*\}$/;
const NUMBER = /^-?(0|[1-9]\d*)(\.\d+)?$/;
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;

/** What the Parameters panel's form holds: each field as typed. */
export type ParameterDraft = { name: string; kind: string; defaultText: string; description: string };
export type DraftField = keyof ParameterDraft;

/** A declared parameter as the form starts with it. */
export const draftOf = (p: DeclaredParameter): ParameterDraft => ({
  name: p.name,
  kind: p.kind ?? p.typeText ?? "",
  defaultText: p.defaultText,
  description: p.description ?? "",
});

export const emptyDraft = (): ParameterDraft => ({ name: "", kind: "text", defaultText: "", description: "" });

/** Why one value does not fit `kind` (not a list), or null when it does. */
function valueProblem(kind: "text" | "number" | "boolean", v: string): string | null {
  if (kind === "text") {
    if (v.length > MAX_TEXT) return `text of at most ${MAX_TEXT.toLocaleString("en-GB")} characters`;
    if (CONTROL.test(v)) return "text without control characters";
    return null;
  }
  if (kind === "number") {
    if (!NUMBER.test(v)) return "a number, such as 0, 12 or 0.02";
    const [whole, part = ""] = v.replace("-", "").split(".");
    if (whole.length > 15 || whole.length + part.length > 30) return "a number of at most 15 digits before the point and 30 in all";
    return null;
  }
  return v === "true" || v === "false" ? null : "true or false";
}

/** A list default as typed: values separated by commas, each trimmed, empty ones left out. */
const listItems = (text: string) =>
  text
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s !== "");

/**
 * What is wrong with a parameter as typed in the form, by field; empty when nothing is. `others`
 * are the names of the pipeline's other parameters. As the server reads `parameters:`: a name of a
 * small letter then small letters, digits and _ (at most 64), not a word conditions use and not
 * taken; a kind it knows; a default (a pipeline also runs on its own) that fits the kind, or a
 * server variable written as `${vars.name}`.
 */
export function parameterProblems(d: ParameterDraft, others: string[]): Partial<Record<DraftField, string>> {
  const out: Partial<Record<DraftField, string>> = {};
  const name = d.name.trim();
  if (name === "") out.name = "Give the parameter a name, such as region.";
  else if (TAKEN_WORDS.includes(name))
    out.name = `${name} cannot be a parameter's name: conditions read it as a word of their own (${TAKEN_WORDS.join(", ")}).`;
  else if (!PARAMETER_NAME.test(name)) out.name = "A name starts with a small letter, then small letters, digits and _ (at most 64).";
  else if (others.includes(name)) out.name = `There is already a parameter called ${name}.`;
  const kind = parameterKind(d.kind);
  if (!kind) out.kind = `Choose its type: ${PARAMETER_KINDS.join(", ")}.`;
  const def = d.defaultText.trim();
  const variable = VARIABLE.exec(def);
  if (def === "") out.defaultText = "Give a default, so the pipeline also runs on its own.";
  else if (variable) {
    if (!PARAMETER_NAME.test(variable[1])) out.defaultText = `${variable[1] || "That"} is not a server variable's name: small letters, digits and _.`;
  } else if (kind) {
    const list = kind.endsWith(" list");
    const one = kind.replace(" list", "") as "text" | "number" | "boolean";
    if (list) {
      const items = listItems(def);
      const wrong = items.map((v) => valueProblem(one, v)).find((p) => p != null);
      if (items.length === 0 || items.length > MAX_LIST) out.defaultText = `A ${kind} holds 1 to ${MAX_LIST} values, separated by commas.`;
      else if (wrong) out.defaultText = `Each value is ${wrong}.`;
    } else {
      const wrong = valueProblem(one, def);
      if (wrong) out.defaultText = `The default is ${wrong}.`;
    }
  }
  if (CONTROL.test(d.description)) out.description = "The description is one line of text.";
  return out;
}

/** The parameter as written to the YAML, from a draft with no problems. */
export function specOf(d: ParameterDraft): ParameterSpec {
  const kind = parameterKind(d.kind) as ParameterKind;
  const def = d.defaultText.trim();
  const one = kind.replace(" list", "");
  const typed = (v: string): Value => (one === "number" ? Number(v) : one === "boolean" ? v === "true" : v);
  const defaultValue: Value = VARIABLE.test(def) ? def : kind.endsWith(" list") ? listItems(def).map(typed) : typed(def);
  const description = d.description.trim();
  return { name: d.name.trim(), kind, defaultValue, description: description === "" ? null : description };
}

/** A sentence ending in a full stop. */
const sentence = (text: string) => (/[.!?]$/.test(text.trim()) ? text.trim() : `${text.trim()}.`);

/** A declared parameter's default in words: its value, or the server variable it takes. */
export function defaultWords(p: DeclaredParameter): string {
  if (p.defaultVariable) return `the server variable ${p.defaultVariable}`;
  return p.defaultText.trim() === "" ? "none" : p.defaultText;
}

/**
 * What a `${name}` chip says about a parameter: its type, its default and its description, such as
 * "statuses: text list, default delivered. The order statuses to keep."
 */
export function parameterSummary(p: DeclaredParameter): string {
  const kind = p.kind ?? (p.typeText ? `type ${p.typeText}` : "no type");
  return [`${p.name}: ${kind}, default ${defaultWords(p)}.`, p.description ? sentence(p.description) : null].filter(Boolean).join(" ");
}

/** What a chip says about a server variable: what it is, and its value when the viewer may see it. */
export function variableSummary(
  name: string,
  value: { state: "hidden" } | { state: "loading" } | { state: "unset" } | { state: "failed" } | { state: "known"; value: unknown },
): string {
  const what = `vars.${name}: a server variable, the same for every pipeline and flow on this server.`;
  switch (value.state) {
    case "hidden":
      return `${what} Stewards and above see its value.`;
    case "loading":
      return `${what} Reading its value…`;
    case "unset":
      return `${what} It is not set on this server, so a run that needs it is refused.`;
    case "failed":
      return `${what} Its value could not be read.`;
    case "known":
      return `${what} Its value now: ${String(value.value)}.`;
  }
}

/** The names `text` puts in with `${name}`, in the order written, each once; `$${` writes a plain `${`. */
export function namesIn(text: string): string[] {
  const out: string[] = [];
  for (const m of text.matchAll(/\$\$\{|\$\{([^}]*)\}/g)) {
    const n = m[1]?.trim();
    if (n && !out.includes(n)) out.push(n);
  }
  return out;
}

/** The names a setting's value puts in, wherever in it they are (a list, a mapping). */
export function namesInValue(v: Value | undefined): string[] {
  if (v == null) return [];
  if (typeof v === "string") return namesIn(v);
  if (typeof v !== "object") return [];
  const out: string[] = [];
  for (const x of Array.isArray(v) ? v : Object.values(v)) for (const n of namesInValue(x)) if (!out.includes(n)) out.push(n);
  return out;
}
