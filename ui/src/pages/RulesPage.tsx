import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import {
  allows,
  formatNumber,
  formatShare,
  formatTime,
  qualityApi,
  type AccessRole,
  type Preview,
  type RuleDraft,
  type RuleType,
  type RuleView,
} from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { QualityStatusBadge } from "../components/QualityPanel";

/** Each rule type, what it checks, and the parameters it takes (as JSON), with an example. */
const TYPES: { type: RuleType; label: string; field: boolean; params: string; hint: string }[] = [
  { type: "not_null", label: "Not null", field: true, params: '{"blankIsMissing": true}', hint: "blankIsMissing: also count blank text" },
  { type: "unique", label: "Unique", field: true, params: "{}", hint: 'Optional fields: ["a", "b"] for a key of several columns' },
  { type: "allowed_values", label: "Allowed values", field: true, params: '{"values": ["Retail", "Trade"]}', hint: "values: the values allowed" },
  { type: "matches_pattern", label: "Matches pattern", field: true, params: '{"regex": "^[A-Z]{2}[0-9]{4}$"}', hint: "regex: a regular expression every value must match" },
  { type: "in_range", label: "In range", field: true, params: '{"min": 0, "max": 100}', hint: "min, max or both, inclusive; dates as 2024-01-31" },
  { type: "freshness", label: "Freshness", field: true, params: '{"maxAgeHours": 24}', hint: "maxAgeHours: how old the latest date or timestamp may be" },
  { type: "row_count", label: "Row count", field: false, params: '{"min": 1}', hint: "min, max or both" },
  { type: "row_count_change", label: "Row count change", field: false, params: '{"maxChange": 0.2}', hint: "maxChange: largest change since the last scan, as a share (0.2 is 20%)" },
  { type: "references", label: "References (foreign key)", field: true, params: '{"asset": "urn:kilnmark:source:schema.table", "field": "id"}', hint: "asset and field the values must appear in; same source" },
  { type: "custom_sql", label: "Custom SQL", field: false, params: '{"sql": "SELECT * FROM ${table} WHERE amount < 0"}', hint: "sql: a SELECT listing the failing rows; ${table} and ${column} are filled in. Runs read-only, with a time limit" },
];

/** Rules: every rule with its latest result; stewards add, preview, run, switch off and delete them. */
export function RulesPage({ role, urn }: { role: AccessRole; urn: string }) {
  useTitle("Quality rules");
  const steward = allows(role, "steward");
  const [filter, setFilter] = useState(urn);
  const [rules, setRules] = useState<RuleView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");

  const load = useCallback(() => {
    qualityApi
      .rules(filter.trim() ? { urn: filter.trim() } : {})
      .then((r) => {
        setRules(r);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [filter]);

  useEffect(() => setFilter(urn), [urn]);
  useEffect(load, [load]);

  const act = async (what: () => Promise<unknown>, done: string) => {
    try {
      await what();
      setNotice(done);
      load();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <>
      <h1 tabIndex={-1}>Quality rules</h1>
      <p className="lead">
        Checks on assets, each tagged with a dimension. They run after every scan and on demand: as SQL in databases,
        and while streaming files.
      </p>
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      <section aria-labelledby="rules-h" className="panel">
        <h2 id="rules-h">Rules</h2>
        <div className="field inline-field">
          <label htmlFor="rules-filter">Asset URN</label>
          <input id="rules-filter" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="All assets"
            aria-describedby="rules-filter-hint" autoComplete="off" />
          <p id="rules-filter-hint" className="hint">Leave empty to list the rules of every asset.</p>
        </div>
        {rules === null && !error && <p>Loading…</p>}
        {rules?.length === 0 && <p>No rules yet.</p>}
        {rules && rules.length > 0 && (
          <div className="table-scroll" role="region" aria-label="Rules table (scrolls sideways on small screens)" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Rule</th>
                  <th scope="col">Asset</th>
                  <th scope="col">Check</th>
                  <th scope="col">Status</th>
                  <th scope="col">Failing rows</th>
                  {steward && (
                    <th scope="col">
                      <span className="visually-hidden">Actions</span>
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {rules.map(({ rule, latest }) => (
                  <tr key={rule.id}>
                    <th scope="row">
                      {rule.name}
                      {!rule.enabled && <span className="tag">off</span>}
                      <div className="small muted">{rule.dimension}</div>
                    </th>
                    <td className="small">
                      <a href={href.asset(rule.assetUrn)} className="mono">{rule.assetUrn}</a>
                      {rule.field && <div className="mono">{rule.field}</div>}
                    </td>
                    <td className="small">
                      {TYPES.find((t) => t.type === rule.type)?.label ?? rule.type}
                      <div className="muted">
                        fail above {rule.thresholdUnit === "share" ? formatShare(rule.failAbove) : formatNumber(rule.failAbove)}
                        {rule.warnAbove != null &&
                          `, warn above ${rule.thresholdUnit === "share" ? formatShare(rule.warnAbove) : formatNumber(rule.warnAbove)}`}
                      </div>
                    </td>
                    <td>
                      {latest ? <QualityStatusBadge status={latest.status} /> : <span className="badge badge-none">Not run</span>}
                      {latest && <div className="small muted">{formatTime(latest.observedAt)}</div>}
                    </td>
                    <td className="small">
                      {latest?.failing != null
                        ? `${formatNumber(latest.failing)} of ${formatNumber(latest.total)}`
                        : latest?.message ?? "–"}
                      {latest?.failingQuery && (
                        <details>
                          <summary>Query for the failing rows</summary>
                          <code className="mono query">{latest.failingQuery}</code>
                        </details>
                      )}
                    </td>
                    {steward && (
                      <td>
                        <div className="buttons">
                          <button type="button" onClick={() => act(() => qualityApi.runRule(rule.id),
                            `Rule "${rule.name}" queued to run.`)} aria-label={`Run rule ${rule.name} now`}>
                            Run now
                          </button>
                          <button type="button" onClick={() => act(() => qualityApi.updateRule(rule.id, {
                            ...rule, enabled: !rule.enabled, actions: { webhook: rule.actions.webhook, task: rule.actions.task },
                          }), `Rule "${rule.name}" switched ${rule.enabled ? "off" : "on"}.`)}
                            aria-label={`${rule.enabled ? "Switch off" : "Switch on"} rule ${rule.name}`}>
                            {rule.enabled ? "Switch off" : "Switch on"}
                          </button>
                          <button type="button" onClick={() => {
                            if (window.confirm(`Delete the rule "${rule.name}" and its results?`)) {
                              act(() => qualityApi.deleteRule(rule.id), `Rule "${rule.name}" deleted.`);
                            }
                          }} aria-label={`Delete rule ${rule.name}`}>
                            Delete
                          </button>
                        </div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {steward ? (
        <AddRule
          urn={filter}
          onAdded={(name) => {
            setNotice(`Rule "${name}" added. It runs after the next scan, or press Run now.`);
            load();
          }}
        />
      ) : (
        <p className="hint">Your role ({role}) can read rules. Ask an admin for a steward token to add or change them.</p>
      )}
    </>
  );
}

function AddRule({ urn, onAdded }: { urn: string; onAdded: (name: string) => void }) {
  const [assetUrn, setAssetUrn] = useState(urn);
  const [type, setType] = useState<RuleType>("not_null");
  const [field, setField] = useState("");
  const [name, setName] = useState("");
  const [params, setParams] = useState(TYPES[0].params);
  const [unit, setUnit] = useState<"share" | "count">("share");
  const [warn, setWarn] = useState("");
  const [fail, setFail] = useState("0");
  const [webhook, setWebhook] = useState("");
  const [task, setTask] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [saving, setSaving] = useState(false);
  const errorRef = useRef<HTMLParagraphElement>(null);
  const spec = TYPES.find((t) => t.type === type)!;

  useEffect(() => setAssetUrn(urn), [urn]);

  const draft = (): RuleDraft => {
    let p: Record<string, unknown>;
    try {
      p = params.trim() ? JSON.parse(params) : {};
    } catch {
      throw new Error("Parameters: not valid JSON. For example " + spec.params);
    }
    const toNumber = (v: string, label: string, scale: number) => {
      if (!v.trim()) return null;
      const n = Number(v);
      if (Number.isNaN(n)) throw new Error(`${label}: a number`);
      return n / scale;
    };
    // Shares are typed as percentages and sent as 0 to 1.
    const scale = unit === "share" ? 100 : 1;
    return {
      assetUrn: assetUrn.trim(),
      field: spec.field ? field.trim() || null : field.trim() || null,
      name: name.trim() || undefined,
      type,
      params: p,
      thresholdUnit: unit,
      warnAbove: toNumber(warn, "Warn above", scale),
      failAbove: toNumber(fail, "Fail above", scale) ?? 0,
      actions: { webhook: webhook.trim() || null, task },
    };
  };

  const fault = (e: unknown) => {
    setError((e as Error).message);
    window.setTimeout(() => errorRef.current?.focus(), 0);
  };

  const doPreview = async () => {
    setError(null);
    setPreview(null);
    try {
      setPreview(await qualityApi.preview(draft()));
    } catch (e) {
      fault(e);
    }
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const r = await qualityApi.addRule(draft());
      setPreview(null);
      setName("");
      onAdded(r.name);
    } catch (err) {
      fault(err);
    } finally {
      setSaving(false);
    }
  };

  return (
    <section aria-labelledby="add-rule-h" className="panel">
      <h2 id="add-rule-h">Add a rule</h2>
      {error && (
        <p role="alert" className="error" tabIndex={-1} ref={errorRef}>
          {error}
        </p>
      )}
      <form onSubmit={submit} className="form">
        <div className="field">
          <label htmlFor="rule-asset">Asset URN</label>
          <input id="rule-asset" value={assetUrn} onChange={(e) => setAssetUrn(e.target.value)} required
            aria-describedby="rule-asset-hint" autoComplete="off" className="mono" />
          <p id="rule-asset-hint" className="hint">As shown at the foot of the asset page, for example urn:kilnmark:lab:crm.customers.</p>
        </div>
        <div className="field-row">
          <div className="field">
            <label htmlFor="rule-type">Check</label>
            <select id="rule-type" value={type} onChange={(e) => {
              const t = e.target.value as RuleType;
              setType(t);
              setParams(TYPES.find((x) => x.type === t)!.params);
              setPreview(null);
            }}>
              {TYPES.map((t) => (
                <option key={t.type} value={t.type}>{t.label}</option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="rule-field">Column{spec.field ? "" : " (optional)"}</label>
            <input id="rule-field" value={field} onChange={(e) => setField(e.target.value)} required={spec.field && type !== "unique"}
              autoComplete="off" className="mono" />
          </div>
        </div>
        <div className="field">
          <label htmlFor="rule-name">Name (optional)</label>
          <input id="rule-name" value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" />
        </div>
        <div className="field">
          <label htmlFor="rule-params">Parameters (JSON)</label>
          <textarea id="rule-params" value={params} onChange={(e) => setParams(e.target.value)} rows={3}
            className="mono" aria-describedby="rule-params-hint" spellCheck={false} />
          <p id="rule-params-hint" className="hint">{spec.hint}.</p>
        </div>
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
            <input id="rule-warn" value={warn} onChange={(e) => setWarn(e.target.value)} inputMode="decimal"
              aria-describedby="rule-th-hint" />
          </div>
          <div className="field">
            <label htmlFor="rule-fail">Fail above</label>
            <input id="rule-fail" value={fail} onChange={(e) => setFail(e.target.value)} inputMode="decimal"
              aria-describedby="rule-th-hint" />
          </div>
          <p id="rule-th-hint" className="hint">
            {unit === "share" ? "In percent: 5 means 5% of rows." : "In rows."} Leave Warn above empty for no warning.
          </p>
        </fieldset>
        <fieldset className="form">
          <legend>When it warns or fails</legend>
          <label className="check">
            <input type="checkbox" checked={task} onChange={(e) => setTask(e.target.checked)} /> Open a task in the
            stewards' issue list
          </label>
          <div className="field">
            <label htmlFor="rule-hook">Webhook URL (optional)</label>
            <input id="rule-hook" value={webhook} onChange={(e) => setWebhook(e.target.value)} type="url"
              aria-describedby="rule-hook-hint" autoComplete="off" />
            <p id="rule-hook-hint" className="hint">Only addresses on the administrator's allow-list are accepted.</p>
          </div>
        </fieldset>
        <div className="buttons">
          <button type="button" onClick={doPreview}>Preview on the sample</button>
          <button type="submit" className="primary" disabled={saving}>
            {saving ? "Saving…" : "Save rule"}
          </button>
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
                          <th scope="col" key={c} className="mono">{c}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {preview.failingRows.rows.map((r, i) => (
                        <tr key={i}>
                          {r.map((v, j) => (
                            <td key={j} className="mono small">{v ?? <span className="muted">null</span>}</td>
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
