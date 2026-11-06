import { parse } from "yaml";
import type { FlowParameter, Schedule } from "../api";
import type { Tone } from "../components/ui";

/**
 * The schedule form's presets: each fills the cron expression, so the common times need no cron.
 * Anything else is "custom", typed as cron. Times are "HH:MM", in the schedule's zone.
 */
export type Preset =
  | { kind: "daily"; time: string }
  | { kind: "weekdays"; time: string }
  | { kind: "hourly"; minute: number }
  | { kind: "minutes"; every: number }
  | { kind: "monthly"; day: number; time: string }
  | { kind: "custom" };

export type PresetKind = Preset["kind"];

/** The choices for "every N minutes": those that divide the hour, so the gaps stay even. */
export const MINUTE_STEPS = [1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30];

const two = (n: number) => String(n).padStart(2, "0");

/** "02:30" as hour and minute, or null when it is not a time of day. */
export function readTime(time: string): { hour: number; minute: number } | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(time.trim());
  if (!m) return null;
  const hour = Number(m[1]);
  const minute = Number(m[2]);
  return hour <= 23 && minute <= 59 ? { hour, minute } : null;
}

/** The cron expression a preset stands for, or null when a part of it is not right yet. */
export function presetCron(p: Preset): string | null {
  switch (p.kind) {
    case "daily":
    case "weekdays":
    case "monthly": {
      const t = readTime(p.time);
      if (!t) return null;
      if (p.kind === "daily") return `${t.minute} ${t.hour} * * *`;
      if (p.kind === "weekdays") return `${t.minute} ${t.hour} * * MON-FRI`;
      return Number.isInteger(p.day) && p.day >= 1 && p.day <= 31 ? `${t.minute} ${t.hour} ${p.day} * *` : null;
    }
    case "hourly":
      return Number.isInteger(p.minute) && p.minute >= 0 && p.minute <= 59 ? `${p.minute} * * * *` : null;
    case "minutes":
      return p.every === 1 ? "* * * * *" : MINUTE_STEPS.includes(p.every) ? `*/${p.every} * * * *` : null;
    case "custom":
      return null;
  }
}

const num = (s: string, max: number, min = 0) => {
  if (!/^\d{1,2}$/.test(s)) return null;
  const n = Number(s);
  return n >= min && n <= max ? n : null;
};

/** The preset a cron expression is, so editing a schedule opens on it; custom for anything else. */
export function readPreset(cron: string): Preset {
  const f = cron.trim().split(/\s+/);
  if (f.length !== 5) return { kind: "custom" };
  const [mi, h, dom, mon, dow] = f;
  const minute = num(mi, 59);
  const hour = num(h, 23);
  if (mon !== "*") return { kind: "custom" };
  if (minute != null && hour != null) {
    const time = `${two(hour)}:${two(minute)}`;
    if (dom === "*" && dow === "*") return { kind: "daily", time };
    if (dom === "*" && /^(MON-FRI|1-5)$/i.test(dow)) return { kind: "weekdays", time };
    const day = num(dom, 31, 1);
    if (day != null && dow === "*") return { kind: "monthly", day, time };
  }
  if (h === "*" && dom === "*" && dow === "*") {
    if (minute != null) return { kind: "hourly", minute };
    if (mi === "*") return { kind: "minutes", every: 1 };
    const step = /^\*\/(\d{1,2})$/.exec(mi);
    if (step && MINUTE_STEPS.includes(Number(step[1]))) return { kind: "minutes", every: Number(step[1]) };
  }
  return { kind: "custom" };
}

/** 1st, 2nd, 3rd, 4th … 21st, 22nd, 31st. */
export function ordinal(n: number): string {
  const tens = n % 100;
  if (tens >= 11 && tens <= 13) return `${n}th`;
  return `${n}${({ 1: "st", 2: "nd", 3: "rd" } as Record<number, string>)[n % 10] ?? "th"}`;
}

const SHORTHAND: Record<string, string> = {
  "@hourly": "Every hour, on the hour",
  "@daily": "Every day at 00:00",
  "@midnight": "Every day at 00:00",
  "@weekly": "Every Sunday at 00:00",
  "@monthly": "On the 1st of each month at 00:00",
  "@yearly": "On 1 January at 00:00",
  "@annually": "On 1 January at 00:00",
};

/**
 * A cron expression in words, for the common shapes (the presets and the @ shorthands); null for
 * anything else, which is then shown as the expression alone.
 */
export function cronWords(cron: string): string | null {
  const short = SHORTHAND[cron.trim().toLowerCase()];
  if (short) return short;
  const p = readPreset(cron);
  switch (p.kind) {
    case "daily":
      return `Every day at ${p.time}`;
    case "weekdays":
      return `Weekdays (Monday to Friday) at ${p.time}`;
    case "monthly":
      return `On the ${ordinal(p.day)} of each month at ${p.time}`;
    case "hourly":
      return p.minute === 0 ? "Every hour, on the hour" : `Every hour at ${p.minute} ${p.minute === 1 ? "minute" : "minutes"} past`;
    case "minutes":
      return p.every === 1 ? "Every minute" : `Every ${p.every} minutes`;
    case "custom":
      return null;
  }
}

/** The browser's own time zone (an IANA name), or UTC when it cannot say. */
export function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}

/** The IANA zones the browser knows, UTC among them, in order; with `also` added if it is missing. */
export function timeZones(also?: string): string[] {
  let known: string[] = [];
  try {
    const intl = Intl as unknown as { supportedValuesOf?: (key: string) => string[] };
    known = intl.supportedValuesOf?.("timeZone") ?? [];
  } catch {
    known = [];
  }
  const all = new Set([...known, "UTC", browserZone(), ...(also ? [also] : [])]);
  return [...all].sort((a, b) => a.localeCompare(b));
}

const localFormat = new Intl.DateTimeFormat("en-GB", {
  weekday: "short",
  day: "numeric",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  timeZoneName: "short",
});

/** A time in the viewer's own zone, with the zone named: "Mon 10 Nov 2026, 02:00 GMT". */
export const localTime = (iso: string) => localFormat.format(new Date(iso));

/** A time as it reads in a zone, "02:00 on Mon 10 Nov"; null when the zone is unknown here. */
export function zoneTime(iso: string, zone: string): string | null {
  try {
    const f = new Intl.DateTimeFormat("en-GB", {
      timeZone: zone,
      weekday: "short",
      day: "numeric",
      month: "short",
      hour: "2-digit",
      minute: "2-digit",
    });
    const parts = Object.fromEntries(f.formatToParts(new Date(iso)).map((p) => [p.type, p.value]));
    return `${parts.hour}:${parts.minute} on ${parts.weekday} ${parts.day} ${parts.month}`;
  } catch {
    return null;
  }
}

/**
 * When a time is, for the viewer: in their own zone, and in the schedule's zone as well when that
 * is another zone, so both read the same instant.
 */
export function whenText(iso: string, zone: string): string {
  const local = localTime(iso);
  if (zone === browserZone()) return local;
  const there = zoneTime(iso, zone);
  return there ? `${local} (${there} in ${zone})` : local;
}

/** A last outcome as a word, a tone and the reason after it ("failed: …" gives Failed and the reason). */
export function outcomeOf(text: string | null): { word: string; tone: Tone; reason: string | null } {
  if (!text) return { word: "Not run yet", tone: "neutral", reason: null };
  const colon = text.indexOf(":");
  const head = (colon < 0 ? text : text.slice(0, colon)).trim().toLowerCase();
  const reason = colon < 0 ? null : text.slice(colon + 1).trim() || null;
  switch (head) {
    case "started":
      return { word: "Started", tone: "good", reason };
    case "starting":
      return { word: "Starting", tone: "info", reason };
    case "skipped":
      return { word: "Skipped", tone: "warn", reason };
    case "missed":
      return { word: "Missed", tone: "warn", reason };
    case "failed":
      return { word: "Failed", tone: "bad", reason };
    // Paused by Kilnmark, not by a person: whoever set it lost the role, or has not signed in for
    // a while (viewers get just "paused"). Said apart from the grey Paused state, in amber.
    case "paused":
      return { ...pausedWords(reason), tone: "warn" };
    default:
      return { word: text, tone: "neutral", reason: null };
  }
}

/** A reason Kilnmark paused a schedule for, in a few words; any other reason is shown as it is. */
function pausedWords(reason: string | null): { word: string; reason: string | null } {
  if (reason == null) return { word: "Paused by Kilnmark", reason: null };
  if (/no longer has the role/i.test(reason)) return { word: "Paused: setter lost the role", reason: null };
  const days = /has not signed in for (\d+) days?/i.exec(reason);
  if (days) return { word: `Paused: setter not signed in for ${days[1]} ${days[1] === "1" ? "day" : "days"}`, reason: null };
  return { word: "Paused by Kilnmark", reason };
}

/** Whether the last time a schedule was due it failed to start a run. */
export const lastFailed = (s: Schedule) => outcomeOf(s.lastOutcome).word === "Failed";

/**
 * Whether Kilnmark paused it, and it is still off: whoever set it lost the role, or has not signed
 * in within kilnmark.schedules.signed-in-within. An admin resumes it (becoming its setter) or
 * deletes it. Resuming leaves the outcome until its next fire.
 */
export const pausedByKilnmark = (s: Schedule) => !s.enabled && /^paused(:|$)/i.test(s.lastOutcome ?? "");

/** The values a schedule's runs start with, in a line: "region = EMEA; statuses = shipped, delivered". */
export function valuesText(values: Record<string, unknown> | null): string {
  if (values == null) return "";
  const entries = Object.entries(values);
  if (entries.length === 0) return "The defaults";
  return entries.map(([k, v]) => `${k} = ${Array.isArray(v) ? v.map(String).join(", ") : String(v)}`).join("; ");
}

/** A stored value as it is typed in a parameter field: lists separated by commas. */
export const valueText = (v: unknown) => (Array.isArray(v) ? v.map(String).join(", ") : v == null ? "" : String(v));

const KINDS = new Set(["text", "number", "boolean", "text list", "number list"]);
const VAR = /^\$\{\s*vars\.([a-z][a-z0-9_]*)\s*}$/;

/**
 * The parameters a pipeline's YAML declares, shaped as a flow's are, for the schedule form. A
 * pipeline's parameters each have a default, so none is needed. Anything that cannot be read is
 * left out: the server checks the values when the schedule is saved.
 */
export function pipelineParameters(yaml: string): FlowParameter[] {
  let doc: unknown;
  try {
    doc = parse(yaml);
  } catch {
    return [];
  }
  const declared = (doc as { parameters?: unknown } | null)?.parameters;
  if (declared == null || typeof declared !== "object" || Array.isArray(declared)) return [];
  const out: FlowParameter[] = [];
  for (const [name, spec] of Object.entries(declared as Record<string, unknown>)) {
    if (spec == null || typeof spec !== "object") continue;
    const s = spec as { type?: unknown; default?: unknown; description?: unknown };
    const type = typeof s.type === "string" ? s.type.trim().toLowerCase().replace(/\s+/g, " ") : "";
    if (!KINDS.has(type)) continue;
    const variable = typeof s.default === "string" ? VAR.exec(s.default.trim())?.[1] ?? null : null;
    out.push({
      name,
      type: type as FlowParameter["type"],
      defaultValue: variable ? null : (s.default ?? null),
      defaultVariable: variable,
      required: false,
      description: typeof s.description === "string" ? s.description : null,
    });
  }
  return out;
}
