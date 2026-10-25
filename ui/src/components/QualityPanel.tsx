import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import {
  allows,
  formatNumber,
  formatShare,
  formatTime,
  qualityApi,
  rating,
  type Asset,
  type AssetQuality,
  type ColumnPatterns,
  type DimensionScore,
  type DriftEvent,
  type QualityStatus,
  type Recommendation,
  type RuleResult,
  type RuleView,
  type SampleRows,
} from "../api";
import { href } from "../router";
import { docHref } from "../docs/links";
import { formatAgo } from "../format";
import { useRole } from "./role";
import { CHECKS } from "./RuleForm";
import { Icon } from "./icons";
import {
  Button,
  ButtonLink,
  Card,
  DataTable,
  EmptyState,
  FilterChips,
  Meter,
  ScoreRing,
  SeverityChip,
  StatusChip,
  type FillTone,
  type FilterOption,
  type Tone,
} from "./ui";

const DIMENSIONS: Record<string, string> = {
  overall: "Overall",
  completeness: "Completeness",
  uniqueness: "Uniqueness",
  validity: "Validity",
  consistency: "Consistency",
  accuracy: "Accuracy (estimated)",
  timeliness: "Timeliness",
  volume: "Volume",
  integrity: "Referential integrity",
};

/** Each dimension keeps one colour on bars and filter chips; its name is always written too. */
const DIM_FILL: Record<string, FillTone> = {
  completeness: "sky",
  validity: "violet",
  uniqueness: "green",
  timeliness: "amber",
  consistency: "accent",
  accuracy: "accent",
  volume: "sky",
  integrity: "violet",
};
const DIM_CHIP: Record<string, FilterOption["tone"]> = {
  completeness: "sky",
  validity: "violet",
  uniqueness: "green",
  timeliness: "amber",
  accuracy: "orange",
  volume: "teal",
  consistency: "orange",
  integrity: "violet",
};

const STATUS: Record<QualityStatus, string> = {
  pass: "Pass",
  warn: "Warning",
  fail: "Fail",
  error: "Could not run",
};
const STATUS_TONE: Record<QualityStatus, Tone> = { pass: "good", warn: "warn", fail: "bad", error: "neutral" };

/** A score as a bar, a percentage and a word: never colour alone. */
export function ScoreText({ score }: { score: number }) {
  const r = rating(score);
  return (
    <span className="score">
      <span className="score-bar" aria-hidden="true">
        <span className={`tone-${r.tone}`} style={{ width: `${Math.round(score * 100)}%` }} />
      </span>
      <strong>{Math.round(score * 100)}%</strong> <span className={`rating rating-${r.tone}`}>{r.word}</span>
    </span>
  );
}

export function QualityStatusBadge({ status }: { status: QualityStatus }) {
  return <StatusChip tone={STATUS_TONE[status]}>{STATUS[status]}</StatusChip>;
}

/**
 * An asset's Quality tab: the overall score on a dark card with a card per dimension; the rules on
 * this asset with their latest result and a history of recent results; recommended rules from the
 * profile as a compact checklist that adds the ticked ones together; then value patterns, drift
 * and sample rows. Stewards add and run rules; the full rule form (pickers, editing, the guide) is
 * on the Quality rules page.
 */
export function QualityPanel({ asset }: { asset: Asset }) {
  const urn = asset.urn;
  const role = useRole();
  const [quality, setQuality] = useState<AssetQuality | null>(null);
  const [rules, setRules] = useState<RuleView[]>([]);
  const [drift, setDrift] = useState<DriftEvent[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [unmask, setUnmask] = useState(false);
  const [round, setRound] = useState(0);
  const steward = allows(role, "steward");

  const load = useCallback(() => {
    // "Show sensitive values" (admins) unmasks rules and drift too; servers that do not mask ignore it.
    Promise.all([qualityApi.asset(urn, unmask), qualityApi.rules({ urn }, unmask), qualityApi.drift(urn, unmask)])
      .then(([q, r, d]) => {
        setQuality(q);
        setRules(r);
        setDrift(d);
        setError(null);
        setRound((n) => n + 1);
      })
      .catch((e: Error) => setError(e.message));
  }, [urn, unmask]);

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

  const runRules = async () => {
    try {
      let run = await qualityApi.runAsset(urn);
      setNotice("Rules queued. Results appear here when a worker has run them.");
      for (let i = 0; i < 60 && (run.status === "queued" || run.status === "running"); i++) {
        await new Promise((r) => window.setTimeout(r, 1000));
        run = await qualityApi.run(run.id);
      }
      setNotice(run.status === "succeeded" ? `Rules run: ${run.detail ?? "done"}.` : `Rules did not finish: ${run.detail ?? run.status}.`);
      load();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <div className="quality">
      <h2 className="visually-hidden">Quality</h2>
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {quality === null && !error && <p role="status" className="muted">Loading quality…</p>}
      {quality && !quality.version && (
        <Card>
          <EmptyState icon={<Icon name="rules" size={22} />} title="Not profiled yet">
            Scan the source to measure the quality of this asset.
          </EmptyState>
        </Card>
      )}
      {quality?.version && (
        <>
          <Scores quality={quality} rules={rules} />
          <div className="km-split km-section">
            <Rules rules={rules} urn={urn} steward={steward} onRun={runRules} round={round} />
            {steward && (
              <Recommendations
                key={round}
                urn={urn}
                unmask={unmask}
                canUnmask={quality.canUnmask}
                onAdded={load}
              />
            )}
          </div>
          <Patterns
            quality={quality}
            steward={steward}
            onAccept={(field, shape) =>
              act(() => qualityApi.accept(urn, field, shape), `Shape ${shape} accepted for ${field}. A validity rule checks it.`)
            }
            onWithdraw={(id, label) => act(() => qualityApi.unaccept(urn, id), `${label} withdrawn.`)}
            onRule={(field, regex) =>
              act(
                () => qualityApi.addRule({ assetUrn: urn, field, type: "matches_pattern", params: { regex } }),
                `Pattern rule added for ${field}.`,
              )
            }
          />
          <Drift events={drift} />
          {quality.canSeeData && (
            <Samples
              asset={asset}
              quality={quality}
              steward={steward}
              unmask={unmask}
              onUnmask={setUnmask}
              onSensitive={(field, on) =>
                act(() => qualityApi.setSensitive(urn, field, on), `${field} is ${on ? "now" : "no longer"} marked sensitive.`)
              }
            />
          )}
        </>
      )}
    </div>
  );
}

/** The overall score on a dark card with a ring, and a card per dimension with its bar. */
function Scores({ quality, rules }: { quality: AssetQuality; rules: RuleView[] }) {
  const overall = quality.scores.asset.find((s) => s.dimension === "overall");
  const dims = quality.scores.asset.filter((s) => s.dimension !== "overall");
  const ran = rules.filter((r) => r.latest);
  const passing = ran.filter((r) => r.latest?.status === "pass").length;
  const v = quality.version!;
  return (
    <section aria-label="Quality scores" className="km-scores" style={{ ["--km-dims" as string]: Math.min(Math.max(dims.length, 1), 4) }}>
      <div className="km-card km-card--dark km-score-card">
        {overall ? (
          <ScoreRing value={overall.score} label={`Overall quality ${Math.round(overall.score * 100)} percent, ${rating(overall.score).word}`} />
        ) : (
          <span className="km-icon-tile km-icon-tile--lg km-tint-slate" aria-hidden="true">
            –
          </span>
        )}
        <div>
          <h3 className="km-score-card-title">Overall quality{overall ? `: ${rating(overall.score).word}` : ""}</h3>
          <p className="km-score-card-text">
            {ran.length > 0 ? `${passing} of ${ran.length} rules pass` : "No rule results yet"}
            <br />
            Profiled {formatAgo(v.observedAt)}
            {v.estimate && ` · estimated from ${formatNumber(v.sampledRows)} rows (${v.samplePercent}%)`}
            {v.origin !== "kilnmark" && ` · imported from ${v.origin}`}
          </p>
        </div>
      </div>
      {dims.map((d) => (
        <DimensionCard key={d.dimension} score={d} />
      ))}
    </section>
  );
}

function DimensionCard({ score }: { score: DimensionScore }) {
  const why = score.reasons[0];
  return (
    <div className="km-card km-dim">
      <h3 className="km-dim-label">{DIMENSIONS[score.dimension] ?? score.dimension}</h3>
      <p className="km-dim-value num">{Math.round(score.score * 100)}%</p>
      <Meter value={score.score} tone={DIM_FILL[score.dimension] ?? "accent"} size="lg" />
      {why && <p className="hint km-dim-why">{why}</p>}
    </div>
  );
}

/** The result in words: "Fails · 5.0%", "Warns · 4.0%", "Passes", "Could not run", "Not run". */
function resultText(view: RuleView): { tone: Tone; text: string; detail: string | null } {
  const l = view.latest;
  if (!l) return { tone: "neutral", text: "Not run", detail: null };
  const share = l.failing != null && l.total ? l.failing / l.total : null;
  const amount =
    view.rule.type === "freshness" || view.rule.type === "row_count" || view.rule.type === "row_count_change"
      ? null
      : view.rule.thresholdUnit === "count" && l.failing != null
        ? `${formatNumber(l.failing)} rows`
        : share != null
          ? formatShare(share)
          : null;
  const word = { pass: "Passes", warn: "Warns", fail: "Fails", error: "Could not run" }[l.status];
  return {
    tone: STATUS_TONE[l.status],
    text: l.status === "pass" || l.status === "error" || !amount ? word : `${word} · ${amount}`,
    detail: l.status === "error" ? (l.errorDetail ?? l.message) : l.status !== "pass" ? l.message : null,
  };
}

/** One bar per recent result, oldest first; described in words for screen readers. */
function History({ results, name }: { results: RuleResult[] | undefined; name: string }) {
  if (!results) return <span className="muted small">…</span>;
  if (results.length === 0) return <span className="muted small">No results yet</span>;
  const oldestFirst = [...results].reverse();
  const count = (s: QualityStatus) => results.filter((r) => r.status === s).length;
  const parts = [
    count("pass") && `${count("pass")} passed`,
    count("warn") && `${count("warn")} warned`,
    count("fail") && `${count("fail")} failed`,
    count("error") && `${count("error")} could not run`,
  ].filter(Boolean);
  const label = `${name}: last ${results.length} ${results.length === 1 ? "result" : "results"}, ${parts.join(", ")}; latest ${STATUS[results[0].status].toLowerCase()}`;
  return (
    <span className="km-history" role="img" aria-label={label}>
      {oldestFirst.map((r) => (
        <span key={r.id} className={`h-${r.status}`} title={`${STATUS[r.status]} · ${formatTime(r.observedAt)}`} />
      ))}
    </span>
  );
}

function Rules({
  rules,
  urn,
  steward,
  onRun,
  round,
}: {
  rules: RuleView[];
  urn: string;
  steward: boolean;
  onRun: () => void;
  round: number;
}) {
  const [filter, setFilter] = useState("all");
  const [history, setHistory] = useState<Record<number, RuleResult[]>>({});
  useEffect(() => {
    let live = true;
    // The last ten results of each rule (up to 50 rules), for the history bars.
    rules.slice(0, 50).forEach((r) =>
      qualityApi
        .results(r.rule.id, 10)
        .then((list) => live && setHistory((h) => ({ ...h, [r.rule.id]: list })))
        .catch(() => live && setHistory((h) => ({ ...h, [r.rule.id]: [] }))),
    );
    return () => {
      live = false;
    };
    // round: reload after rules run or are added.
  }, [rules, round]);

  const failing = rules.filter((r) => r.latest?.status === "fail" || r.latest?.status === "error");
  const warning = rules.filter((r) => r.latest?.status === "warn");
  const shown = filter === "failing" ? failing : filter === "warning" ? warning : rules;
  const options: FilterOption[] = [
    { id: "all", label: "All", count: rules.length },
    ...(failing.length ? [{ id: "failing", label: "Failing", count: failing.length, tone: "red" as const }] : []),
    ...(warning.length ? [{ id: "warning", label: "Warning", count: warning.length, tone: "amber" as const }] : []),
  ];
  return (
    <Card
      title="Rules on this table"
      titleId="rules-h"
      actions={
        <>
          {rules.length > 0 && <FilterChips label="Show rules" options={options} selected={filter} onSelect={setFilter} />}
          {steward && (
            <ButtonLink variant="primary" size="sm" href={href.rules(urn)} icon={<Icon name="plus" size={16} />}>
              Add a rule
            </ButtonLink>
          )}
        </>
      }
    >
      {rules.length === 0 ? (
        <EmptyState compact icon={<Icon name="rules" size={22} />} title="No rules yet">
          Tick a recommended rule, accept a value pattern below, or <a href={href.rules(urn)}>write a rule</a>.
        </EmptyState>
      ) : (
        <DataTable label="Rules on this table (scrolls sideways on small screens)" caption="Rules on this asset, their latest result and recent history">
          <thead>
            <tr>
              <th scope="col">Column</th>
              <th scope="col">Check</th>
              <th scope="col">Result</th>
              <th scope="col">Last 10 results</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((view) => {
              const r = resultText(view);
              return (
                <tr key={view.rule.id}>
                  <td>{view.rule.field ? <span className="km-code">{view.rule.field}</span> : <span className="muted small">Whole table</span>}</td>
                  <th scope="row">
                    {view.rule.name}
                    {!view.rule.enabled && <span className="tag tag-key">off</span>}
                    <div className="small muted">
                      {CHECKS.find((c) => c.type === view.rule.type)?.label ?? view.rule.type} ·{" "}
                      {DIMENSIONS[view.rule.dimension] ?? view.rule.dimension}
                    </div>
                  </th>
                  <td>
                    <StatusChip tone={r.tone}>{r.text}</StatusChip>
                    {view.latest?.estimate && <span className="tag tag-key">estimate</span>}
                    {r.detail && <div className="small muted km-clip" title={r.detail}>{r.detail}</div>}
                  </td>
                  <td>
                    <History results={history[view.rule.id]} name={view.rule.name} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </DataTable>
      )}
      <div className="km-card-foot">
        {steward && rules.length > 0 && (
          <button type="button" className="link-button" onClick={onRun}>
            Run rules now
          </button>
        )}
        <a href={href.rules(urn)}>Manage rules for this asset</a>
      </div>
    </Card>
  );
}

/** True for a recommendation whose values were held back (a sensitive column): it cannot be added in one click. */
const withheld = (r: Recommendation) =>
  (r.type === "allowed_values" || r.type === "in_range") && Object.keys(r.params ?? {}).length === 0;

/**
 * How much of the data a recommendation already fits, from how it was found: not null, unique,
 * allowed values and ranges hold for every row by construction; a pattern says its coverage. Null
 * for checks that are not about rows (freshness, row count).
 */
export function fitOf(r: Recommendation): number | null {
  if (r.type === "not_null" || r.type === "unique" || r.type === "allowed_values" || r.type === "in_range") return 1;
  if (r.type === "matches_pattern") {
    const m = /^([\d.]+)\s*%/.exec(r.reason);
    return m ? Number(m[1]) / 100 : null;
  }
  return null;
}

/** The check in a few words: "Not null", "In list (active, lapsed, closed)", "Between 1 and 9". */
function checkText(r: Recommendation): string {
  const p = r.params ?? {};
  switch (r.type) {
    case "allowed_values":
      return Array.isArray(p.values) && p.values.length ? `In list (${(p.values as string[]).join(", ")})` : "In a list of values";
    case "in_range":
      return p.min != null && p.max != null ? `Between ${p.min} and ${p.max}` : "In a range";
    case "freshness":
      return typeof p.maxAgeHours === "number" ? `Newest row under ${p.maxAgeHours} hours old` : "Recent";
    case "row_count_change":
      return typeof p.maxChange === "number" ? `Row count changes less than ${Math.round(p.maxChange * 100)}%` : "Row count is steady";
    case "matches_pattern":
      return "Matches its pattern";
    default:
      return CHECKS.find((c) => c.type === r.type)?.label ?? r.name;
  }
}

const recKey = (r: Recommendation) => `${r.type}:${r.field ?? ""}`;

/**
 * Recommended rules: what the data already obeys, as a compact list with a checkbox each. Those
 * that fit every row are ticked; dimension chips filter the list; "Add N selected" adds them with
 * the create-rule call, one at a time, then says in one message what was added and what was not,
 * and moves focus to that message.
 */
function Recommendations({
  urn,
  unmask,
  canUnmask,
  onAdded,
}: {
  urn: string;
  unmask: boolean;
  canUnmask: boolean;
  onAdded: () => void;
}) {
  const id = useId();
  const [list, setList] = useState<Recommendation[] | null>(null);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [group, setGroup] = useState("all");
  const [adding, setAdding] = useState(false);
  const [summary, setSummary] = useState<{ ok: string[]; failed: { name: string; why: string }[] } | null>(null);
  const summaryRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    qualityApi
      .recommendations(urn, unmask)
      .then((l) => {
        setList(l);
        setPicked(new Set(l.filter((r) => !withheld(r) && fitOf(r) === 1).map(recKey)));
      })
      .catch(() => setList([]));
  }, [urn, unmask]);

  useEffect(() => {
    if (summary) summaryRef.current?.focus();
  }, [summary]);

  const groups = useMemo(() => {
    const counts = new Map<string, number>();
    (list ?? []).forEach((r) => counts.set(r.dimension, (counts.get(r.dimension) ?? 0) + 1));
    return counts;
  }, [list]);

  if (!list) return null;
  const addable = list.filter((r) => !withheld(r));
  const shown = list.filter((r) => group === "all" || r.dimension === group);
  const shownAddable = shown.filter((r) => !withheld(r));
  const allShownPicked = shownAddable.length > 0 && shownAddable.every((r) => picked.has(recKey(r)));
  const chosen = addable.filter((r) => picked.has(recKey(r)));

  const toggle = (r: Recommendation, on: boolean) =>
    setPicked((p) => {
      const n = new Set(p);
      if (on) n.add(recKey(r));
      else n.delete(recKey(r));
      return n;
    });

  const add = async () => {
    if (adding || chosen.length === 0) return;
    setAdding(true);
    setSummary(null);
    const ok: string[] = [];
    const failed: { name: string; why: string }[] = [];
    for (const r of chosen) {
      try {
        await qualityApi.addRule({ assetUrn: urn, field: r.field, type: r.type, name: r.name, params: r.params });
        ok.push(r.name);
      } catch (e) {
        failed.push({ name: r.name, why: (e as Error).message });
      }
    }
    setAdding(false);
    setSummary({ ok, failed });
    if (ok.length) onAdded();
  };

  return (
    <Card
      title="Recommended from the profile"
      titleId={`${id}-h`}
      icon={<Icon name="ask" size={18} className="km-violet-icon" />}
      accentTop="violet"
    >
      <div ref={summaryRef} tabIndex={-1} role="status" className="km-summary">
        {summary && (
          <div className={`km-callout ${summary.failed.length ? (summary.ok.length ? "" : "km-callout--bad") : "km-callout--good"}`}>
            {summary.ok.length > 0 && (
              <p>
                Added {summary.ok.length} {summary.ok.length === 1 ? "rule" : "rules"}: {summary.ok.join(", ")}.
                {summary.failed.length === 0 && " They run after the next scan, or press Run rules now."}
              </p>
            )}
            {summary.failed.length > 0 && (
              <>
                <p>
                  {summary.failed.length} could not be added{summary.ok.length ? "" : ", so nothing changed"}:
                </p>
                <ul>
                  {summary.failed.map((f) => (
                    <li key={f.name}>
                      <strong>{f.name}</strong>: {f.why}
                    </li>
                  ))}
                </ul>
              </>
            )}
          </div>
        )}
      </div>
      {list.length === 0 ? (
        <EmptyState compact icon={<Icon name="circle-check" size={22} />} title="Nothing to suggest">
          Every check the profile can suggest is already a rule here.
        </EmptyState>
      ) : (
        <>
          <p className="km-card-sub">
            {list.length} {list.length === 1 ? "suggestion" : "suggestions"}. Tick the ones you want and add them together.
          </p>
          <FilterChips
            label="Show suggestions by dimension"
            selected={group}
            onSelect={setGroup}
            options={[
              { id: "all", label: "All", count: list.length },
              ...[...groups.entries()].map(([dim, n]) => ({
                id: dim,
                label: (DIMENSIONS[dim] ?? dim).replace(" (estimated)", ""),
                count: n,
                tone: DIM_CHIP[dim],
              })),
            ]}
          />
          <ul className="km-reco-list" aria-label="Suggested rules">
            {shown.map((r) => {
              const key = recKey(r);
              const fit = fitOf(r);
              const held = withheld(r);
              const on = !held && picked.has(key);
              const fitText = fit == null ? "not a row check" : `fits ${formatShare(fit)}`;
              const fitTone = fit == null ? "km-text-neutral" : fit >= 1 ? "km-text-good" : fit > 0.95 ? "" : "km-text-warn";
              return (
                <li key={key} className={on ? "km-reco is-on" : "km-reco"}>
                  <input
                    type="checkbox"
                    id={`${id}-${key}`}
                    checked={on}
                    disabled={held || adding}
                    onChange={(e) => toggle(r, e.target.checked)}
                    aria-describedby={`${id}-${key}-why`}
                  />
                  <label htmlFor={`${id}-${key}`}>
                    {r.field && <span className="km-code">{r.field}</span>}
                    <span className="km-reco-check">{checkText(r)}</span>
                    <span className={`km-reco-fit num ${fitTone}`}>{fitText}</span>
                    <span className="km-reco-why" id={`${id}-${key}-why`}>
                      {r.reason}
                      {held &&
                        (canUnmask
                          ? ". The values are held back: tick “Show sensitive values” under Sample rows to add it."
                          : ". The values are held back because the column is sensitive.")}
                    </span>
                  </label>
                </li>
              );
            })}
          </ul>
          <div className="buttons km-reco-actions">
            <Button variant="primary" onClick={add} aria-disabled={adding || chosen.length === 0}>
              {adding ? "Adding…" : `Add ${chosen.length} selected`}
            </Button>
            <Button
              onClick={() =>
                setPicked((p) => {
                  const n = new Set(p);
                  shownAddable.forEach((r) => (allShownPicked ? n.delete(recKey(r)) : n.add(recKey(r))));
                  return n;
                })
              }
              disabled={adding || shownAddable.length === 0}
            >
              {allShownPicked ? "Clear selection" : "Select all"}
            </Button>
            <a href={docHref("quality-rules")} className="km-push-right">
              How rules work
            </a>
          </div>
        </>
      )}
    </Card>
  );
}

function Examples({ values }: { values?: (string | null)[] }) {
  if (!values || values.length === 0) return <span className="muted">–</span>;
  return (
    <ul className="examples">
      {values.map((v, i) => (
        <li key={i} className="mono">
          {v === null ? <span className="muted">null</span> : v === "" ? <span className="muted">(empty)</span> : v}
        </li>
      ))}
    </ul>
  );
}

function Patterns({
  quality,
  steward,
  onAccept,
  onWithdraw,
  onRule,
}: {
  quality: AssetQuality;
  steward: boolean;
  onAccept: (field: string, shape: string) => void;
  onWithdraw: (id: number, label: string) => void;
  onRule: (field: string, regex: string) => void;
}) {
  const id = useId();
  const [field, setField] = useState(quality.patterns[0]?.field ?? "");
  const column: ColumnPatterns | undefined =
    quality.patterns.find((p) => p.field === field) ?? quality.patterns[0];
  if (!column) return null;
  const accepted = quality.accepted.filter((a) => a.field === column.field);
  return (
    <Card title="Value patterns" titleId={`${id}-h`} className="km-section">
      <p className="hint">
        Each value reduced to its shape: A for capitals, a for small letters, 9 for digits. Counted over every row.
        {column.collapsed && " Runs of letters and digits are shown once with + because this column is free text."}
      </p>
      <div className="field inline-field">
        <label htmlFor={`${id}-col`}>Column</label>
        <select id={`${id}-col`} value={column.field} onChange={(e) => setField(e.target.value)}>
          {quality.patterns.map((p) => (
            <option key={p.field} value={p.field}>
              {p.field}
            </option>
          ))}
        </select>
      </div>
      <DataTable
        label={`Value shapes of ${column.field} (scrolls sideways)`}
        caption={`Value shapes of ${column.field}, most frequent first, with their share of ${formatNumber(column.values)} values`}
        className="patterns"
      >
        <thead>
          <tr>
            <th scope="col">Shape</th>
            <th scope="col">Share</th>
            <th scope="col">Values</th>
            {quality.canSeeData && <th scope="col">Examples</th>}
            <th scope="col">Accepted</th>
          </tr>
        </thead>
        <tbody>
          {column.shapes.map((s) => {
            const acc = accepted.find((a) => a.shape === s.shape);
            return (
              <tr key={s.shape}>
                <th scope="row" className="mono">
                  <span className="shape">{s.shape === "" ? "(empty)" : s.shape}</span>
                </th>
                <td>
                  <span className="heat">
                    <span className="heat-bar" aria-hidden="true">
                      <span style={{ width: `${Math.max(1, Math.round(s.share * 100))}%` }} />
                    </span>
                    {formatShare(s.share)}
                  </span>
                </td>
                <td>{formatNumber(s.count)}</td>
                {quality.canSeeData && (
                  <td>
                    <Examples values={s.examples} />
                  </td>
                )}
                <td>
                  {acc ? (
                    <>
                      <StatusChip tone="good">Accepted</StatusChip>{" "}
                      {steward && (
                        <button type="button" className="link-button" onClick={() => onWithdraw(acc.id, `Shape ${s.shape}`)}
                          aria-label={`Withdraw accepted shape ${s.shape} of ${column.field}`}>
                          Withdraw
                        </button>
                      )}
                    </>
                  ) : steward ? (
                    <Button size="sm" onClick={() => onAccept(column.field, s.shape)}
                      aria-label={`Accept shape ${s.shape} for ${column.field}`}>
                      Accept
                    </Button>
                  ) : (
                    <span className="muted">No</span>
                  )}
                </td>
              </tr>
            );
          })}
          {column.other > 0 && (
            <tr>
              <th scope="row">Other shapes</th>
              <td>{formatShare(column.other / column.values)}</td>
              <td>{formatNumber(column.other)}</td>
              {quality.canSeeData && (
                <td>
                  <Examples values={column.otherExamples} />
                </td>
              )}
              <td />
            </tr>
          )}
        </tbody>
      </DataTable>
      {column.formats.length > 0 && (
        <p className="small">
          Formats found:{" "}
          {column.formats
            .map((f) => `${f.format.replace(/_/g, " ")} ${formatShare(f.count / column.values)}`)
            .join(", ")}
        </p>
      )}
      {column.suggestion && (
        <p className="small">
          Suggested expression (covers {formatShare(column.suggestion.coverage)}, from {column.suggestion.basis}):{" "}
          <code className="km-code">{column.suggestion.regex}</code>{" "}
          {steward && (
            <Button size="sm" onClick={() => onRule(column.field, column.suggestion!.regex)}
              aria-label={`Add a pattern rule for ${column.field} with the suggested expression`}>
              Add as a rule
            </Button>
          )}
        </p>
      )}
    </Card>
  );
}

function Drift({ events }: { events: DriftEvent[] }) {
  return (
    <Card title="Drift" titleId="drift-h" subtitle="Changes between the last scans" className="km-section">
      {events.length === 0 ? (
        <EmptyState compact icon={<Icon name="circle-check" size={22} />} title="No drift">
          Nothing changed shape between the last scans.
        </EmptyState>
      ) : (
        <ul className="km-rows">
          {events.map((e) => (
            <li key={e.id} className="km-row">
              <SeverityChip severity={e.severity} />
              <div className="km-row-body">
                <p className="km-row-title">
                  {e.message}
                  {e.detail && <span className="muted"> ({e.detail})</span>}
                </p>
                <p className="km-row-detail">
                  {formatTime(e.detectedAt)}
                  {e.against === "baseline" ? " · against the baseline" : ""}
                </p>
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function Samples({
  asset,
  quality,
  steward,
  unmask,
  onUnmask,
  onSensitive,
}: {
  asset: Asset;
  quality: AssetQuality;
  steward: boolean;
  unmask: boolean;
  onUnmask: (v: boolean) => void;
  onSensitive: (field: string, on: boolean) => void;
}) {
  const id = useId();
  const [sample, setSample] = useState<SampleRows | null>(null);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    qualityApi
      .samples(asset.urn, unmask)
      .then(setSample)
      .catch((e: Error) => setError(e.message));
  }, [open, asset.urn, unmask, quality.sensitive]);

  return (
    <Card title="Sample rows" titleId={`${id}-h`} className="km-section">
      <p className="hint">
        The same rows on every scan, chosen by the hash of each row's key. Columns marked sensitive are masked.
      </p>
      <div className="buttons km-gap-top">
        <button type="button" aria-expanded={open} aria-controls={`${id}-rows`} onClick={() => setOpen(!open)}>
          {open ? "Hide sample rows" : "Show sample rows"}
        </button>
        {quality.canUnmask && (
          <label className="check">
            <input type="checkbox" checked={unmask} onChange={(e) => onUnmask(e.target.checked)} /> Show sensitive values
          </label>
        )}
      </div>
      {steward && (
        <fieldset className="sensitive">
          <legend>Sensitive columns</legend>
          <p id={`${id}-sens-hint`} className="hint">Stewards mark columns sensitive; only an admin can unmark one.</p>
          {asset.fields.map((f) => (
            <label key={f.name} className="check">
              <input
                type="checkbox"
                checked={quality.sensitive.includes(f.name)}
                disabled={quality.sensitive.includes(f.name) && !quality.canUnmask}
                aria-describedby={`${id}-sens-hint`}
                onChange={(e) => onSensitive(f.name, e.target.checked)}
              />{" "}
              <span className="mono">{f.name}</span>
            </label>
          ))}
        </fieldset>
      )}
      <div id={`${id}-rows`}>
        {open && error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        {open && sample && !sample.kept && <p>{sample.message}</p>}
        {open && sample?.kept && sample.columns && sample.rows && (
          <DataTable
            label="Sample rows (scrolls sideways)"
            className="sample"
            showCaption
            caption={
              <>
                {formatNumber(sample.rows.length)} of {formatNumber(sample.seen)} rows,{" "}
                {sample.method === "key"
                  ? `chosen by the hash of ${sample.keyFields?.join(", ")}`
                  : sample.method === "row"
                    ? "chosen by the hash of each whole row"
                    : "chosen by reservoir sampling"}
                {sample.masked && sample.masked.length > 0 && `; masked: ${sample.masked.join(", ")}`}
              </>
            }
          >
            <thead>
              <tr>
                {sample.columns.map((c) => (
                  <th scope="col" key={c} className="mono">
                    {c}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sample.rows.slice(0, 100).map((r, i) => (
                <tr key={i}>
                  {r.map((v, j) => (
                    <td key={j} className="mono small">
                      {v === null ? <span className="muted">null</span> : v}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </DataTable>
        )}
      </div>
    </Card>
  );
}
