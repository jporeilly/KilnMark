import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import {
  formatShare,
  qualityApi,
  type ColumnPatterns,
  type Field,
  type QualityRule,
  type RuleDraft,
  type RulePreview,
  type RuleType,
} from "../api";
import { AssetPicker, useAsset } from "./AssetPicker";
import { QualityStatusBadge } from "./QualityPanel";

const TEMPORAL = new Set(["date", "timestamp", "timestamp_tz"]);
const NUMERIC = new Set(["int16", "int32", "int64", "float32", "float64", "decimal"]);
const MAX_VALUES = 1000;

/**
 * Each check: whether it takes a column, which columns suit it, whether it passes or fails as a
 * whole, and what fails, in plain words. The parameters each one sends are in paramsFrom; the
 * server (RuleDraft.java) checks them again and has the last word.
 */
export const CHECKS: {
  type: RuleType;
  label: string;
  column: "required" | "optional" | "none";
  suits?: (kind: string) => boolean;
  whole: boolean;
  what: string;
}[] = [
  { type: "not_null", label: "Not null", column: "required", whole: false, what: "Fails rows where the column is empty." },
  {
    type: "unique",
    label: "Unique",
    column: "required",
    whole: false,
    what: "Fails rows whose value appears more than once, in one column or in a key of several columns.",
  },
  {
    type: "allowed_values",
    label: "Allowed values",
    column: "required",
    whole: false,
    what: "Fails rows whose value is not in your list.",
  },
  {
    type: "matches_pattern",
    label: "Matches pattern",
    column: "required",
    whole: false,
    what: "Fails rows whose value does not match a regular expression.",
  },
  {
    type: "in_range",
    label: "In range",
    column: "required",
    suits: (k) => NUMERIC.has(k) || TEMPORAL.has(k),
    whole: false,
    what: "Fails rows whose value is below the minimum or above the maximum.",
  },
  {
    type: "freshness",
    label: "Freshness",
    column: "required",
    suits: (k) => TEMPORAL.has(k),
    whole: true,
    what: "Fails when the newest date or time in the column is older than you allow.",
  },
  {
    type: "row_count",
    label: "Row count",
    column: "none",
    whole: true,
    what: "Fails when the asset has fewer or more rows than you allow.",
  },
  {
    type: "row_count_change",
    label: "Row count change",
    column: "none",
    whole: true,
    what: "Fails when the number of rows has changed by more than you allow since the last scan.",
  },
  {
    type: "references",
    label: "References (foreign key)",
    column: "required",
    whole: false,
    what: "Fails rows whose value does not appear in a column of another asset in the same source. Needs a database source.",
  },
  {
    type: "custom_sql",
    label: "Custom SQL (admins)",
    column: "optional",
    whole: false,
    what: "Fails the rows your query returns. PostgreSQL sources only.",
  },
];

const KIND_WORDS: Record<string, string> = {
  boolean: "true or false",
  int16: "whole number",
  int32: "whole number",
  int64: "whole number",
  float32: "number",
  float64: "number",
  decimal: "decimal number",
  text: "text",
  binary: "binary",
  date: "date",
  time: "time of day",
  timestamp: "date and time",
  timestamp_tz: "date and time with zone",
};
const kindWord = (kind: string) => KIND_WORDS[kind] ?? kind;

/** What the plain fields hold, for every check at once, so changing the check loses nothing. */
type Fields = {
  blankIsMissing: boolean;
  several: boolean;
  keyFields: string[];
  values: string;
  regex: string;
  min: string;
  max: string;
  maxAgeHours: string;
  rowMin: string;
  rowMax: string;
  changePercent: string;
  refUrn: string;
  refField: string;
  sql: string;
};

const DEFAULTS: Fields = {
  blankIsMissing: true,
  several: false,
  keyFields: [],
  values: "",
  regex: "",
  min: "",
  max: "",
  maxAgeHours: "24",
  rowMin: "1",
  rowMax: "",
  changePercent: "20",
  refUrn: "",
  refField: "",
  sql: "",
};

/** One value per line: lines trimmed, empty lines dropped, repeats dropped. */
const valueLines = (text: string) => [...new Set(text.split(/\r?\n/).map((v) => v.trim()).filter((v) => v !== ""))];

/** A number when the text is one, else the text itself (so the server can say what is wrong). */
function num(text: string): number | string | null {
  const t = text.trim();
  if (!t) return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : t;
}

/** A range limit: a number for numeric columns when it reads back the same, else the text (dates). */
function bound(text: string, kind: string | null): number | string | null {
  const t = text.trim();
  if (!t) return null;
  return kind && NUMERIC.has(kind) && String(Number(t)) === t ? Number(t) : t;
}

/** A share as a percentage for typing: 0.2 is "20". */
const percent = (share: number) => String(Number((share * 100).toPrecision(12)));

const present = (o: Record<string, unknown>) =>
  Object.fromEntries(Object.entries(o).filter(([, v]) => v !== null && v !== undefined));

/** The parameters the plain fields make, exactly as RuleDraft takes them. */
function paramsFrom(type: RuleType, f: Fields, kind: string | null): Record<string, unknown> {
  switch (type) {
    case "not_null":
      return { blankIsMissing: f.blankIsMissing };
    case "unique":
      return f.several ? { fields: f.keyFields } : {};
    case "allowed_values":
      return { values: valueLines(f.values) };
    case "matches_pattern":
      return { regex: f.regex };
    case "in_range":
      return present({ min: bound(f.min, kind), max: bound(f.max, kind) });
    case "freshness":
      return present({ maxAgeHours: num(f.maxAgeHours) });
    case "row_count":
      return present({ min: num(f.rowMin), max: num(f.rowMax) });
    case "row_count_change": {
      const n = num(f.changePercent);
      return present({ maxChange: typeof n === "number" ? n / 100 : n });
    }
    case "references":
      return { asset: f.refUrn.trim(), field: f.refField };
    case "custom_sql":
      return { sql: f.sql };
  }
}

const text = (v: unknown) => (v == null ? "" : String(v));

/** The plain fields for some parameters (from a saved rule or the JSON view), on top of `base`. */
function fieldsFrom(type: RuleType, p: Record<string, unknown>, base: Fields): Fields {
  const list = (v: unknown) => (Array.isArray(v) ? v.map(text) : []);
  switch (type) {
    case "not_null":
      return { ...base, blankIsMissing: p.blankIsMissing === true };
    case "unique":
      return { ...base, several: "fields" in p, keyFields: list(p.fields) };
    case "allowed_values":
      return { ...base, values: list(p.values).join("\n") };
    case "matches_pattern":
      return { ...base, regex: text(p.regex) };
    case "in_range":
      return { ...base, min: text(p.min), max: text(p.max) };
    case "freshness":
      return { ...base, maxAgeHours: text(p.maxAgeHours) };
    case "row_count":
      return { ...base, rowMin: text(p.min), rowMax: text(p.max) };
    case "row_count_change":
      return { ...base, changePercent: typeof p.maxChange === "number" ? percent(p.maxChange) : text(p.maxChange) };
    case "references":
      return { ...base, refUrn: text(p.asset), refField: text(p.field) };
    case "custom_sql":
      return { ...base, sql: text(p.sql) };
  }
}

/** Parameters in a form that compares equal however numbers were written and keys ordered. */
function canonical(v: unknown): string {
  const walk = (x: unknown): unknown => {
    if (typeof x === "number") return String(x);
    if (Array.isArray(x)) return x.map(walk);
    if (x && typeof x === "object") {
      return Object.fromEntries(
        Object.entries(x)
          .filter(([, y]) => y !== null && y !== undefined)
          .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
          .map(([k, y]) => [k, walk(y)]),
      );
    }
    return x;
  };
  return JSON.stringify(walk(v));
}

/** True when the plain fields can show these parameters without losing or changing anything. */
function fitsFields(type: RuleType, p: unknown): p is Record<string, unknown> {
  if (!p || typeof p !== "object" || Array.isArray(p)) return false;
  const params = p as Record<string, unknown>;
  const f = fieldsFrom(type, params, DEFAULTS);
  if (type === "in_range") {
    // The inputs take a number or a date (2024-01-31); anything else stays in the JSON view.
    for (const v of [f.min, f.max]) {
      if (v && !/^\d{4}-\d{2}-\d{2}$/.test(v) && !Number.isFinite(Number(v))) return false;
    }
  }
  return canonical(paramsFrom(type, f, null)) === canonical(params) || canonical(paramsFrom(type, f, "int64")) === canonical(params);
}

/** A problem with what was typed, and the id of the control to mark. */
class Fault extends Error {
  constructor(
    message: string,
    readonly at?: string,
  ) {
    super(message);
  }
}

/** Helpful checks on the plain fields before anything is sent; the server checks everything again. */
function problem(type: RuleType, f: Fields, kind: string | null): Fault | null {
  const isNumber = (t: string) => t.trim() !== "" && Number.isFinite(Number(t));
  const isWhole = (t: string) => /^\d+$/.test(t.trim());
  const isDate = (t: string) => /^\d{4}-\d{2}-\d{2}(?:[T ][0-9:.]+(?:Z|[+-][0-9:]+)?)?$/.test(t.trim());
  switch (type) {
    case "unique":
      if (f.several && f.keyFields.length < 2) {
        return new Fault("Key columns: tick two or more columns, or check a single column instead.", "rule-key");
      }
      return null;
    case "allowed_values": {
      const values = valueLines(f.values);
      if (values.length === 0) return new Fault("Allowed values: type at least one value, one per line.", "rule-values");
      if (values.length > MAX_VALUES) {
        return new Fault(`Allowed values: at most 1,000 values. There are ${values.length.toLocaleString("en-GB")}.`, "rule-values");
      }
      if (values.some((v) => v.length > 500)) {
        return new Fault("Allowed values: each value can be at most 500 characters.", "rule-values");
      }
      return null;
    }
    case "matches_pattern":
      if (!f.regex.trim()) return new Fault("Pattern: type the regular expression every value must match.", "rule-regex");
      if (f.regex.length > 1000) return new Fault("Pattern: at most 1,000 characters.", "rule-regex");
      return null;
    case "in_range": {
      const [min, max] = [f.min.trim(), f.max.trim()];
      if (!min && !max) return new Fault("In range: give a minimum, a maximum or both.", "rule-min");
      const numeric = kind != null && NUMERIC.has(kind);
      const temporal = kind != null && TEMPORAL.has(kind);
      for (const [v, label, at] of [[min, "Minimum", "rule-min"], [max, "Maximum", "rule-max"]] as const) {
        if (v && numeric && !isNumber(v)) return new Fault(`${label}: a number, such as 0 or 12.5.`, at);
        if (v && temporal && !isDate(v)) return new Fault(`${label}: a date, such as 2024-01-31.`, at);
      }
      if (min && max && ((numeric && Number(min) > Number(max)) || (temporal && min > max))) {
        return new Fault("Minimum: must not be above the maximum.", "rule-min");
      }
      return null;
    }
    case "freshness":
      if (!isNumber(f.maxAgeHours) || Number(f.maxAgeHours) <= 0) {
        return new Fault("Hours: how old the newest value may be, a number above 0, such as 24.", "rule-hours");
      }
      return null;
    case "row_count": {
      const [min, max] = [f.rowMin.trim(), f.rowMax.trim()];
      if (!min && !max) return new Fault("Row count: give a minimum, a maximum or both.", "rule-rows-min");
      if (min && !isWhole(min)) return new Fault("Minimum rows: a whole number, 0 or more.", "rule-rows-min");
      if (max && !isWhole(max)) return new Fault("Maximum rows: a whole number, 0 or more.", "rule-rows-max");
      if (min && max && Number(min) > Number(max)) {
        return new Fault("Minimum rows: must not be above the maximum.", "rule-rows-min");
      }
      return null;
    }
    case "row_count_change":
      if (!isNumber(f.changePercent) || Number(f.changePercent) <= 0) {
        return new Fault("Largest change: a percentage above 0, such as 20.", "rule-change");
      }
      return null;
    case "references":
      if (!f.refUrn.trim()) return new Fault("Asset referred to: choose the asset the values must appear in.", "rule-ref-search");
      if (!f.refField) return new Fault("Column referred to: choose the column the values must appear in.", "rule-ref-field");
      return null;
    case "custom_sql":
      if (!f.sql.trim()) return new Fault("SQL: type the query that lists the failing rows.", "rule-sql");
      return null;
    default:
      return null;
  }
}

const json = (p: unknown) => JSON.stringify(p, null, 2);

/**
 * The form for adding a rule, or editing one (`rule`). A steward picks the asset by name and the
 * column from a list, and fills in plain fields for the check; "Advanced: edit as JSON" shows the
 * parameters those fields make, and can take over from them.
 */
export function RuleForm({
  urn,
  admin,
  rule,
  onSaved,
  onCancel,
}: {
  urn: string;
  admin: boolean;
  rule?: QualityRule;
  onSaved: (name: string) => void;
  onCancel?: () => void;
}) {
  const editing = rule != null;
  const startJson = rule != null && !fitsFields(rule.type, rule.params);
  const [assetUrn, setAssetUrn] = useState(rule?.assetUrn ?? urn);
  const [type, setType] = useState<RuleType>(rule?.type ?? "not_null");
  const [column, setColumn] = useState(rule?.field ?? "");
  const [name, setName] = useState(rule?.name ?? "");
  const [f, setF] = useState<Fields>(() => (rule && !startJson ? fieldsFrom(rule.type, rule.params, DEFAULTS) : DEFAULTS));
  const [jsonMode, setJsonMode] = useState(startJson);
  const [jsonOpen, setJsonOpen] = useState(startJson);
  const [jsonText, setJsonText] = useState(startJson && rule ? json(rule.params) : "");
  const [unit, setUnit] = useState<"share" | "count">(rule?.thresholdUnit ?? "share");
  const scaleOf = (u: string) => (u === "share" ? 100 : 1);
  const shown = (v: number | null | undefined) =>
    v == null ? "" : rule?.thresholdUnit === "share" ? percent(v) : String(v);
  const [warn, setWarn] = useState(rule ? shown(rule.warnAbove) : "");
  const [fail, setFail] = useState(rule ? shown(rule.failAbove) : "0");
  const [webhook, setWebhook] = useState(rule?.actions.webhook ?? "");
  const [task, setTask] = useState(rule?.actions.task ?? false);
  const [error, setError] = useState<string | null>(null);
  const [errorAt, setErrorAt] = useState<string | null>(null);
  const [preview, setPreview] = useState<RulePreview | null>(null);
  const [saving, setSaving] = useState(false);
  const [patterns, setPatterns] = useState<{ urn: string; list: ColumnPatterns[] } | null>(null);
  const errorRef = useRef<HTMLParagraphElement>(null);

  const spec = CHECKS.find((c) => c.type === type)!;
  const { asset, loading, error: assetError } = useAsset(assetUrn);
  const ref = useAsset(type === "references" ? f.refUrn : "");
  const fields: Field[] = asset?.fields ?? [];
  const options = fields.filter((c) => !spec.suits || spec.suits(c.kind));
  const kind = fields.find((c) => c.name === column)?.kind ?? null;
  const set = <K extends keyof Fields>(key: K, value: Fields[K]) => setF((old) => ({ ...old, [key]: value }));

  // Arriving from "Manage rules for this asset" fills in the asset.
  useEffect(() => {
    if (!editing) setAssetUrn(urn);
  }, [urn, editing]);

  // The profile's suggested expression, offered for pattern checks.
  const wantPatterns = type === "matches_pattern" && assetUrn.trim() !== "";
  useEffect(() => {
    if (!wantPatterns) return;
    let live = true;
    const u = assetUrn.trim();
    qualityApi
      .asset(u)
      .then((q) => live && setPatterns({ urn: u, list: q.patterns }))
      .catch(() => live && setPatterns({ urn: u, list: [] }));
    return () => {
      live = false;
    };
  }, [wantPatterns, assetUrn]);
  const suggestion =
    patterns?.urn === assetUrn.trim() ? patterns.list.find((p) => p.field === column)?.suggestion : undefined;

  const usingKey = type === "unique" && (jsonMode ? false : f.several);
  const generated = paramsFrom(type, f, kind);
  const invalid = (id: string) => (errorAt === id ? true : undefined);
  const describedBy = (id: string, ...hints: string[]) =>
    [...hints, errorAt === id ? "rule-error" : ""].filter(Boolean).join(" ") || undefined;

  const draft = (): RuleDraft => {
    if (!assetUrn.trim()) throw new Fault("Asset: choose the asset to check.", "rule-asset-search");
    let params: Record<string, unknown>;
    if (jsonMode) {
      let parsed: unknown;
      try {
        parsed = jsonText.trim() ? JSON.parse(jsonText) : {};
      } catch {
        throw new Fault(`Parameters (JSON): not valid JSON. For example ${JSON.stringify(generated)}`, "rule-json");
      }
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Fault("Parameters (JSON): an object in curly brackets, such as {\"regex\": \"^[0-9]+$\"}.", "rule-json");
      }
      params = parsed as Record<string, unknown>;
    } else {
      const p = problem(type, f, kind);
      if (p) throw p;
      params = generated;
    }
    const keyed = type === "unique" && "fields" in params;
    if (spec.column === "required" && !keyed && !column) {
      throw new Fault("Column: choose the column to check.", "rule-field");
    }
    const toNumber = (v: string, label: string, at: string) => {
      if (!v.trim()) return null;
      const n = Number(v);
      if (!Number.isFinite(n)) throw new Fault(`${label}: a number, such as 5.`, at);
      // Shares are typed as percentages and sent as 0 to 1.
      return n / scaleOf(unit);
    };
    return {
      assetUrn: assetUrn.trim(),
      field: spec.column === "none" || keyed ? null : column || null,
      name: name.trim() || undefined,
      type,
      // A rule keeps a dimension set through the API while its check stays the same.
      dimension: rule && rule.type === type ? rule.dimension : undefined,
      params,
      thresholdUnit: spec.whole ? "count" : unit,
      warnAbove: spec.whole ? null : toNumber(warn, "Warn above", "rule-warn"),
      failAbove: spec.whole ? 0 : toNumber(fail, "Fail above", "rule-fail") ?? 0,
      actions: { webhook: webhook.trim() || null, task },
      enabled: rule?.enabled,
    };
  };

  const fault = (e: unknown) => {
    setError((e as Error).message);
    setErrorAt(e instanceof Fault ? e.at ?? null : null);
    window.setTimeout(() => errorRef.current?.focus(), 0);
  };

  const doPreview = async () => {
    setError(null);
    setErrorAt(null);
    setPreview(null);
    try {
      setPreview(await qualityApi.preview(draft()));
    } catch (e) {
      fault(e);
    }
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setErrorAt(null);
    let d: RuleDraft;
    try {
      d = draft();
    } catch (err) {
      fault(err);
      return;
    }
    setSaving(true);
    try {
      const saved = rule ? await qualityApi.updateRule(rule.id, d) : await qualityApi.addRule(d);
      setPreview(null);
      if (!rule) setName("");
      onSaved(saved.name);
    } catch (err) {
      fault(err);
    } finally {
      setSaving(false);
    }
  };

  const changeType = (t: RuleType) => {
    const next = CHECKS.find((c) => c.type === t)!;
    setType(t);
    setPreview(null);
    // A column that does not suit the new check is cleared; JSON written for the old check is set aside.
    const k = fields.find((c) => c.name === column)?.kind;
    if (k && next.suits && !next.suits(k)) setColumn("");
    if (jsonMode) {
      setJsonMode(false);
      setJsonText("");
    }
  };

  const changeAsset = (to: string) => {
    setAssetUrn(to);
    setColumn("");
    setPreview(null);
    setF((old) => ({ ...old, keyFields: [], refUrn: "", refField: "" }));
  };

  const editJson = () => {
    setJsonText(json(generated));
    setJsonMode(true);
  };

  const useFieldsAgain = () => {
    let parsed: unknown;
    try {
      parsed = jsonText.trim() ? JSON.parse(jsonText) : {};
    } catch {
      fault(new Fault("Parameters (JSON): not valid JSON, so the fields cannot show it. Correct it first.", "rule-json"));
      return;
    }
    if (
      !fitsFields(type, parsed) &&
      !window.confirm("The fields cannot show all of this JSON. Use the fields anyway, and drop what they cannot show?")
    ) {
      return;
    }
    const p = parsed && typeof parsed === "object" && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : {};
    setF((old) => fieldsFrom(type, p, old));
    setJsonMode(false);
    setJsonText("");
  };

  const columnHint = (): ReactNode => {
    if (!assetUrn.trim()) return "Choose an asset first.";
    if (loading) return "Loading the asset's columns…";
    if (assetError) return "The columns could not be loaded. Check the asset above.";
    if (fields.length === 0) return "This asset has no columns.";
    if (options.length === 0) {
      return type === "freshness"
        ? "None of this asset's columns holds dates or times, so it cannot have a freshness check. A date stored as text needs a Matches pattern check instead."
        : "None of this asset's columns holds numbers, dates or times. For text, use Allowed values or Matches pattern instead.";
    }
    if (type === "freshness") return "Only columns holding dates or times are listed.";
    if (type === "in_range") return "Only columns holding numbers, dates or times are listed.";
    if (type === "custom_sql") return "Fills in ${column} in your query. Leave it empty if the query does not use it.";
    return null;
  };

  const rangeInput = kind && NUMERIC.has(kind) ? "number" : kind && TEMPORAL.has(kind) ? "date" : "text";

  return (
    <section aria-labelledby="rule-form-h" className="panel">
      <h2 id="rule-form-h" tabIndex={-1}>
        {editing ? "Edit rule" : "Add a rule"}
      </h2>
      {rule && <p className="hint">Changing “{rule.name}”. Its results so far are kept.</p>}
      {error && (
        <p id="rule-error" role="alert" className="error" tabIndex={-1} ref={errorRef}>
          {error}
        </p>
      )}
      <form onSubmit={submit} className="form" noValidate>
        <AssetPicker
          id="rule-asset"
          legend="Asset"
          urn={assetUrn}
          onChange={changeAsset}
          fixed={editing}
          fixedHint="A rule stays on its asset. To check another asset, add a new rule there."
        />
        <div className="field-row">
          <div className="field">
            <label htmlFor="rule-type">Check</label>
            <select
              id="rule-type"
              value={type}
              onChange={(e) => changeType(e.target.value as RuleType)}
              aria-describedby="rule-type-hint"
            >
              {CHECKS.filter((c) => admin || c.type !== "custom_sql" || c.type === rule?.type).map((c) => (
                <option key={c.type} value={c.type}>
                  {c.label}
                </option>
              ))}
            </select>
            <p id="rule-type-hint" className="hint">
              {spec.what}
            </p>
          </div>
          {spec.column !== "none" && !usingKey && (
            <div className="field">
              <label htmlFor="rule-field">Column{spec.column === "optional" ? " (optional)" : ""}</label>
              <select
                id="rule-field"
                value={column}
                onChange={(e) => {
                  setColumn(e.target.value);
                  setPreview(null);
                }}
                aria-invalid={invalid("rule-field")}
                aria-describedby={describedBy("rule-field", "rule-field-hint")}
              >
                <option value="">{spec.column === "optional" ? "No column" : "Choose a column"}</option>
                {options.map((c) => (
                  <option key={c.name} value={c.name}>
                    {c.name} ({kindWord(c.kind)})
                  </option>
                ))}
                {column && !options.some((c) => c.name === column) && (
                  <option value={column}>
                    {column} ({fields.some((c) => c.name === column) ? "does not suit this check" : "not in the asset now"})
                  </option>
                )}
              </select>
              <p id="rule-field-hint" className="hint">
                {columnHint()}
              </p>
            </div>
          )}
        </div>
        <div className="field">
          <label htmlFor="rule-name">Name (optional)</label>
          <input
            id="rule-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            autoComplete="off"
            aria-describedby="rule-name-hint"
          />
          <p id="rule-name-hint" className="hint">
            How the rule appears in lists and alerts. Left empty, it is named after the column and check, such as
            “email not null”.
          </p>
        </div>

        <fieldset className="form" disabled={jsonMode}>
          <legend>Settings for this check</legend>
          {jsonMode && (
            <p className="hint">Set by the JSON under “Advanced: edit as JSON” below, which the fields have handed over to.</p>
          )}
          {type === "not_null" && (
            <label className="check">
              <input type="checkbox" checked={f.blankIsMissing} onChange={(e) => set("blankIsMissing", e.target.checked)} />{" "}
              Also count blank text as missing, such as "" or "   "
            </label>
          )}

          {type === "unique" && (
            <>
              <fieldset>
                <legend>What must be unique</legend>
                <label className="check">
                  <input type="radio" name="rule-unique" checked={!f.several} onChange={() => set("several", false)} /> The
                  column chosen above
                </label>
                <label className="check">
                  <input type="radio" name="rule-unique" checked={f.several} onChange={() => set("several", true)} /> A key
                  of several columns together
                </label>
              </fieldset>
              {f.several && (
                <fieldset
                  id="rule-key"
                  tabIndex={-1}
                  aria-invalid={invalid("rule-key")}
                  aria-describedby={describedBy("rule-key", "rule-key-hint")}
                >
                  <legend>Key columns</legend>
                  <p id="rule-key-hint" className="hint">
                    Tick the columns that together must not repeat, such as an order number and a line number.
                    {fields.length === 0 ? " Choose an asset first." : ""}
                  </p>
                  {fields.length > 0 && (
                    <ul className="checklist">
                      {fields.map((c) => (
                        <li key={c.name}>
                          <label className="check">
                            <input
                              type="checkbox"
                              checked={f.keyFields.includes(c.name)}
                              onChange={(e) =>
                                set(
                                  "keyFields",
                                  e.target.checked ? [...f.keyFields, c.name] : f.keyFields.filter((k) => k !== c.name),
                                )
                              }
                            />{" "}
                            <span className="mono">{c.name}</span> <span className="small muted">({kindWord(c.kind)})</span>
                          </label>
                        </li>
                      ))}
                    </ul>
                  )}
                </fieldset>
              )}
            </>
          )}

          {type === "allowed_values" && (
            <div className="field">
              <label htmlFor="rule-values">Allowed values, one per line</label>
              <textarea
                id="rule-values"
                value={f.values}
                onChange={(e) => set("values", e.target.value)}
                rows={6}
                spellCheck={false}
                aria-invalid={invalid("rule-values")}
                aria-describedby={describedBy("rule-values", "rule-values-hint")}
              />
              <p id="rule-values-hint" className="hint">
                Up to 1,000 values. Matching is exact, capital letters included: “Retail” does not allow “retail”.
                Spaces at the ends of a line, empty lines and repeats are dropped.
              </p>
            </div>
          )}

          {type === "matches_pattern" && (
            <div className="field">
              <label htmlFor="rule-regex">Pattern (regular expression)</label>
              <input
                id="rule-regex"
                value={f.regex}
                onChange={(e) => set("regex", e.target.value)}
                className="mono"
                autoComplete="off"
                spellCheck={false}
                aria-invalid={invalid("rule-regex")}
                aria-describedby={describedBy("rule-regex", "rule-regex-hint")}
              />
              <p id="rule-regex-hint" className="hint">
                Every value must match it, for example <code className="mono">^[A-Z]{"{2}"}[0-9]{"{4}"}$</code>. Type it
                as it is: a backslash is typed once, as in <code className="mono">\s</code>.
              </p>
              {suggestion && suggestion.regex !== f.regex && (
                <p className="small">
                  Suggested from the profile of {column}, covering {formatShare(suggestion.coverage)} of its values:{" "}
                  <code className="mono">{suggestion.regex}</code>{" "}
                  <button type="button" onClick={() => set("regex", suggestion.regex)}>
                    Use the suggested expression
                  </button>
                </p>
              )}
            </div>
          )}

          {type === "in_range" && (
            <>
              <div className="field-row">
                <div className="field">
                  <label htmlFor="rule-min">Minimum</label>
                  <input
                    id="rule-min"
                    type={rangeInput}
                    step={rangeInput === "number" ? "any" : undefined}
                    value={f.min}
                    onChange={(e) => set("min", e.target.value)}
                    aria-invalid={invalid("rule-min")}
                    aria-describedby={describedBy("rule-min", "rule-range-hint")}
                  />
                </div>
                <div className="field">
                  <label htmlFor="rule-max">Maximum</label>
                  <input
                    id="rule-max"
                    type={rangeInput}
                    step={rangeInput === "number" ? "any" : undefined}
                    value={f.max}
                    onChange={(e) => set("max", e.target.value)}
                    aria-invalid={invalid("rule-max")}
                    aria-describedby={describedBy("rule-max", "rule-range-hint")}
                  />
                </div>
              </div>
              <p id="rule-range-hint" className="hint">
                Give one or both. Both limits count as inside.
                {!kind ? " Choose the column first: the boxes then take numbers or dates to suit it." : ""}
              </p>
            </>
          )}

          {type === "freshness" && (
            <Inline id="rule-hours" label="Newest value at most" after="hours old">
              <input
                id="rule-hours"
                type="number"
                min="0"
                step="any"
                value={f.maxAgeHours}
                onChange={(e) => set("maxAgeHours", e.target.value)}
                aria-invalid={invalid("rule-hours")}
                aria-describedby={describedBy("rule-hours", "rule-hours-after", "rule-hours-hint")}
              />
              <p id="rule-hours-hint" className="hint">
                For a nightly load, 26 allows two hours' grace.
              </p>
            </Inline>
          )}

          {type === "row_count" && (
            <div className="field-row">
              <div className="field">
                <label htmlFor="rule-rows-min">Minimum rows</label>
                <input
                  id="rule-rows-min"
                  type="number"
                  min="0"
                  step="1"
                  value={f.rowMin}
                  onChange={(e) => set("rowMin", e.target.value)}
                  aria-invalid={invalid("rule-rows-min")}
                  aria-describedby={describedBy("rule-rows-min", "rule-rows-hint")}
                />
              </div>
              <div className="field">
                <label htmlFor="rule-rows-max">Maximum rows</label>
                <input
                  id="rule-rows-max"
                  type="number"
                  min="0"
                  step="1"
                  value={f.rowMax}
                  onChange={(e) => set("rowMax", e.target.value)}
                  aria-invalid={invalid("rule-rows-max")}
                  aria-describedby={describedBy("rule-rows-max", "rule-rows-hint")}
                />
              </div>
              <p id="rule-rows-hint" className="hint">
                Whole numbers. Give one or both: a minimum of 1 means the asset must never be empty.
              </p>
            </div>
          )}

          {type === "row_count_change" && (
            <Inline id="rule-change" label="Largest change since the last scan" after="%">
              <input
                id="rule-change"
                type="number"
                min="0"
                step="any"
                value={f.changePercent}
                onChange={(e) => set("changePercent", e.target.value)}
                aria-invalid={invalid("rule-change")}
                aria-describedby={describedBy("rule-change", "rule-change-after", "rule-change-hint")}
              />
              <p id="rule-change-hint" className="hint">
                Up or down: 20 fails if the rows grow or shrink by more than a fifth.
              </p>
            </Inline>
          )}

          {type === "references" &&
            (asset ? (
              <>
                <AssetPicker
                  id="rule-ref"
                  legend="Asset referred to"
                  urn={f.refUrn}
                  onChange={(to) => setF((old) => ({ ...old, refUrn: to, refField: "" }))}
                  source={asset.source}
                />
                <div className="field">
                  <label htmlFor="rule-ref-field">Column referred to</label>
                  <select
                    id="rule-ref-field"
                    value={f.refField}
                    onChange={(e) => set("refField", e.target.value)}
                    aria-invalid={invalid("rule-ref-field")}
                    aria-describedby={describedBy("rule-ref-field", "rule-ref-field-hint")}
                  >
                    <option value="">Choose a column</option>
                    {(ref.asset?.fields ?? []).map((c) => (
                      <option key={c.name} value={c.name}>
                        {c.name} ({kindWord(c.kind)})
                      </option>
                    ))}
                    {f.refField && !(ref.asset?.fields ?? []).some((c) => c.name === f.refField) && (
                      <option value={f.refField}>{f.refField}</option>
                    )}
                  </select>
                  <p id="rule-ref-field-hint" className="hint">
                    {!f.refUrn.trim()
                      ? "Choose the asset referred to first."
                      : ref.loading
                        ? "Loading its columns…"
                        : "Every value of the column above must appear in this column, usually its key."}
                  </p>
                </div>
              </>
            ) : (
              <p className="hint">Choose the rule's asset first: the asset referred to must be in the same source.</p>
            ))}

          {type === "custom_sql" && (
            <div className="field">
              <label htmlFor="rule-sql">SQL</label>
              <textarea
                id="rule-sql"
                value={f.sql}
                onChange={(e) => set("sql", e.target.value)}
                rows={5}
                className="mono"
                spellCheck={false}
                aria-invalid={invalid("rule-sql")}
                aria-describedby={describedBy("rule-sql", "rule-sql-hint")}
              />
              <p id="rule-sql-hint" className="hint">
                One SELECT listing the failing rows, such as{" "}
                <code className="mono">{"SELECT * FROM ${table} WHERE amount < 0"}</code>. {"${table}"} and{" "}
                {"${column}"} are filled in. PostgreSQL sources only, when the administrator has turned custom SQL on.
                It runs in a read-only transaction with a time limit, but can read anything the source's login can
                see, so give sources a read-only login.
              </p>
            </div>
          )}
        </fieldset>

        <details open={jsonOpen} onToggle={(e) => setJsonOpen((e.target as HTMLDetailsElement).open)}>
          <summary>Advanced: edit as JSON</summary>
          {jsonMode ? (
            <div className="field">
              <label htmlFor="rule-json">Parameters (JSON)</label>
              <textarea
                id="rule-json"
                value={jsonText}
                onChange={(e) => setJsonText(e.target.value)}
                rows={6}
                className="mono"
                spellCheck={false}
                aria-invalid={invalid("rule-json")}
                aria-describedby={describedBy("rule-json", "rule-json-hint")}
              />
              <p id="rule-json-hint" className="hint">
                {startJson && jsonText === json(rule?.params)
                  ? "This rule's settings cannot all be shown in the fields, so they are shown here. "
                  : ""}
                This JSON is used instead of the fields above. In JSON, a backslash is doubled: \s is written \\s.
              </p>
              <div className="buttons">
                <button type="button" onClick={useFieldsAgain}>
                  Use the fields instead
                </button>
              </div>
            </div>
          ) : (
            <div className="field">
              <p className="hint" id="rule-json-hint">
                The parameters the fields above make, as the API takes them. To type them yourself, choose Edit as
                JSON: the fields are then set aside.
              </p>
              <code className="mono query">
                {json(generated)}
              </code>
              <div className="buttons">
                <button type="button" onClick={editJson}>
                  Edit as JSON
                </button>
              </div>
            </div>
          )}
        </details>

        {spec.whole ? (
          <p className="hint">
            <strong>Passes or fails as a whole.</strong> This check gives one result for the asset, so there are no
            thresholds on failing rows.
          </p>
        ) : (
          <fieldset className="field-row thresholds">
            <legend>Thresholds on failing rows</legend>
            <div className="field">
              <label htmlFor="rule-unit">Measured as</label>
              <select id="rule-unit" value={unit} onChange={(e) => setUnit(e.target.value as "share" | "count")}>
                <option value="share">Percentage of rows</option>
                <option value="count">Number of rows</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="rule-warn">Warn above</label>
              <input
                id="rule-warn"
                value={warn}
                onChange={(e) => setWarn(e.target.value)}
                inputMode="decimal"
                aria-invalid={invalid("rule-warn")}
                aria-describedby={describedBy("rule-warn", "rule-th-hint")}
              />
            </div>
            <div className="field">
              <label htmlFor="rule-fail">Fail above</label>
              <input
                id="rule-fail"
                value={fail}
                onChange={(e) => setFail(e.target.value)}
                inputMode="decimal"
                aria-invalid={invalid("rule-fail")}
                aria-describedby={describedBy("rule-fail", "rule-th-hint")}
              />
            </div>
            <p id="rule-th-hint" className="hint">
              {unit === "share" ? "In percent: 5 means 5% of rows." : "In rows."} Leave Warn above empty for no warning.
            </p>
          </fieldset>
        )}
        <fieldset className="form">
          <legend>When it warns or fails</legend>
          <label className="check">
            <input type="checkbox" checked={task} onChange={(e) => setTask(e.target.checked)} /> Open a task in the
            stewards' issue list
          </label>
          <div className="field">
            <label htmlFor="rule-hook">Webhook URL (optional)</label>
            <input
              id="rule-hook"
              value={webhook}
              onChange={(e) => setWebhook(e.target.value)}
              type="url"
              aria-describedby="rule-hook-hint"
              autoComplete="off"
            />
            <p id="rule-hook-hint" className="hint">
              Only addresses on the administrator's allow-list are accepted.
            </p>
          </div>
        </fieldset>
        <div className="buttons">
          <button type="button" onClick={doPreview}>
            Preview on the sample
          </button>
          <button type="submit" className="primary" disabled={saving}>
            {saving ? "Saving…" : editing ? "Save changes" : "Save rule"}
          </button>
          {onCancel && (
            <button type="button" onClick={onCancel}>
              Cancel
            </button>
          )}
        </div>
        <div aria-live="polite">
          {preview && (
            <div className="preview">
              <p>
                <QualityStatusBadge status={preview.status} /> {preview.message}
              </p>
              {preview.failingRows && preview.failingRows.rows.length > 0 && (
                <div className="table-scroll" role="region" aria-label="Failing sample rows (scrolls sideways)" tabIndex={0}>
                  <table className="sample">
                    <caption>Some failing rows from the sample</caption>
                    <thead>
                      <tr>
                        {preview.failingRows.columns.map((c) => (
                          <th scope="col" key={c} className="mono">
                            {c}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {preview.failingRows.rows.map((r, i) => (
                        <tr key={i}>
                          {r.map((v, j) => (
                            <td key={j} className="mono small">
                              {v ?? <span className="muted">null</span>}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}
        </div>
      </form>
    </section>
  );
}

/** A label, a short input and the unit after it on one line: "Newest value at most [24] hours old". */
function Inline({ id, label, after, children }: { id: string; label: string; after: string; children: ReactNode }) {
  const [input, ...rest] = Array.isArray(children) ? children : [children];
  return (
    <div className="field">
      <div className="buttons" style={{ alignItems: "center" }}>
        <label htmlFor={id}>{label}</label>
        <span style={{ maxWidth: "10ch" }}>{input}</span>
        <span id={`${id}-after`}>{after}</span>
      </div>
      {rest}
    </div>
  );
}
