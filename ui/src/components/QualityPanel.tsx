import { useCallback, useEffect, useId, useState } from "react";
import {
  allows,
  api,
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
  type Me,
  type QualityStatus,
  type Recommendation,
  type RuleView,
  type SampleRows,
} from "../api";
import { href } from "../router";

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

const STATUS: Record<QualityStatus, string> = {
  pass: "Pass",
  warn: "Warning",
  fail: "Fail",
  error: "Could not run",
};

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
  return <span className={`badge badge-q-${status}`}>{STATUS[status]}</span>;
}

/**
 * The asset page's Quality section: scores by dimension with reasons, value patterns with their
 * shares and examples (accept a pattern to make it a validity rule), rules and their latest
 * results, drift since the last scan, sample rows for roles that may see data, and recommended
 * rules.
 */
export function QualityPanel({ asset }: { asset: Asset }) {
  const urn = asset.urn;
  const [me, setMe] = useState<Me | null>(null);
  const [quality, setQuality] = useState<AssetQuality | null>(null);
  const [rules, setRules] = useState<RuleView[]>([]);
  const [drift, setDrift] = useState<DriftEvent[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [unmask, setUnmask] = useState(false);
  const steward = allows(me?.role, "steward");

  const load = useCallback(() => {
    // "Show sensitive values" (admins) unmasks rules and drift too; servers that do not mask ignore it.
    Promise.all([qualityApi.asset(urn, unmask), qualityApi.rules({ urn }, unmask), qualityApi.drift(urn, unmask)])
      .then(([q, r, d]) => {
        setQuality(q);
        setRules(r);
        setDrift(d);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [urn, unmask]);

  useEffect(() => {
    api.me().then(setMe).catch(() => setMe(null));
  }, []);
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

  const overall = quality?.scores.asset.find((s) => s.dimension === "overall");

  return (
    <section aria-labelledby="quality-h" className="panel quality">
      <h2 id="quality-h">Quality</h2>
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {quality === null && !error && <p>Loading quality…</p>}
      {quality && !quality.version && <p>Not profiled yet. Scan the source to measure the quality of this asset.</p>}
      {quality?.version && (
        <>
          <p className="meta">
            {overall ? (
              <>
                Overall <ScoreText score={overall.score} />
              </>
            ) : (
              "No score yet"
            )}
            {" · "}profiled {formatTime(quality.version.observedAt)}
            {quality.version.estimate && (
              <>
                {" · "}
                <span className="tag">estimate</span>
                from a sample of {formatNumber(quality.version.sampledRows)} rows ({quality.version.samplePercent}% of the
                table)
              </>
            )}
            {quality.version.origin !== "kilnmark" && <> · imported from {quality.version.origin}</>}
          </p>
          <Scores scores={quality.scores.asset.filter((s) => s.dimension !== "overall")} />
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
          <Rules rules={rules} urn={urn} steward={steward} onRun={runRules} />
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
          {steward && (
            <Recommendations
              key={rules.length}
              urn={urn}
              unmask={unmask}
              canUnmask={quality.canUnmask}
              onAdd={(r) =>
                act(
                  () =>
                    qualityApi.addRule({ assetUrn: urn, field: r.field, type: r.type, name: r.name, params: r.params }),
                  `Rule "${r.name}" added.`,
                )
              }
            />
          )}
        </>
      )}
    </section>
  );
}

function Scores({ scores }: { scores: DimensionScore[] }) {
  if (scores.length === 0) return null;
  return (
    <>
      <h3>Scores by dimension</h3>
      <div className="table-scroll" role="region" aria-label="Scores by dimension (scrolls sideways on small screens)" tabIndex={0}>
        <table>
          <caption className="visually-hidden">Quality score in each dimension, with the main reasons for any deduction</caption>
          <thead>
            <tr>
              <th scope="col">Dimension</th>
              <th scope="col">Score</th>
              <th scope="col">Why</th>
            </tr>
          </thead>
          <tbody>
            {scores.map((s) => (
              <tr key={s.dimension}>
                <th scope="row">{DIMENSIONS[s.dimension] ?? s.dimension}</th>
                <td>
                  <ScoreText score={s.score} />
                </td>
                <td className="small">
                  {s.reasons.length === 0 ? (
                    <span className="muted">Nothing found</span>
                  ) : (
                    <ul className="plain-list">
                      {s.reasons.slice(0, 3).map((r) => (
                        <li key={r}>{r}</li>
                      ))}
                    </ul>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
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
    <>
      <h3>Value patterns</h3>
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
      <div className="table-scroll" role="region" aria-label={`Value shapes of ${column.field} (scrolls sideways)`} tabIndex={0}>
        <table className="patterns">
          <caption className="visually-hidden">
            Value shapes of {column.field}, most frequent first, with their share of {formatNumber(column.values)} values
          </caption>
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
                        <span className="badge badge-q-pass">Accepted</span>{" "}
                        {steward && (
                          <button type="button" className="link-button" onClick={() => onWithdraw(acc.id, `Shape ${s.shape}`)}
                            aria-label={`Withdraw accepted shape ${s.shape} of ${column.field}`}>
                            Withdraw
                          </button>
                        )}
                      </>
                    ) : steward ? (
                      <button type="button" onClick={() => onAccept(column.field, s.shape)}
                        aria-label={`Accept shape ${s.shape} for ${column.field}`}>
                        Accept
                      </button>
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
        </table>
      </div>
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
          <code className="mono">{column.suggestion.regex}</code>{" "}
          {steward && (
            <button type="button" onClick={() => onRule(column.field, column.suggestion!.regex)}
              aria-label={`Add a pattern rule for ${column.field} with the suggested expression`}>
              Add as a rule
            </button>
          )}
        </p>
      )}
    </>
  );
}

function Rules({ rules, urn, steward, onRun }: { rules: RuleView[]; urn: string; steward: boolean; onRun: () => void }) {
  return (
    <>
      <h3>Rules and results</h3>
      {rules.length === 0 ? (
        <p>No rules yet. Accept a pattern above, add a recommended rule below, or <a href={href.rules(urn)}>write a rule</a>.</p>
      ) : (
        <div className="table-scroll" role="region" aria-label="Rules of this asset (scrolls sideways)" tabIndex={0}>
          <table>
            <caption className="visually-hidden">Rules on this asset and their latest results</caption>
            <thead>
              <tr>
                <th scope="col">Rule</th>
                <th scope="col">Dimension</th>
                <th scope="col">Status</th>
                <th scope="col">Failing rows</th>
                <th scope="col">Last run</th>
              </tr>
            </thead>
            <tbody>
              {rules.map(({ rule, latest }) => (
                <tr key={rule.id}>
                  <th scope="row">
                    {rule.name}
                    {!rule.enabled && <span className="tag">off</span>}
                  </th>
                  <td>{DIMENSIONS[rule.dimension] ?? rule.dimension}</td>
                  <td>
                    {latest ? <QualityStatusBadge status={latest.status} /> : <span className="badge badge-none">Not run</span>}
                    {latest?.estimate && <span className="tag">estimate</span>}
                  </td>
                  <td>
                    {latest?.failing != null
                      ? `${formatNumber(latest.failing)} of ${formatNumber(latest.total)}`
                      : latest?.message ?? "–"}
                  </td>
                  <td className="small">{latest ? formatTime(latest.observedAt) : "–"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="buttons">
        {steward && rules.length > 0 && (
          <button type="button" onClick={onRun}>
            Run rules now
          </button>
        )}
        <a href={href.rules(urn)}>Manage rules for this asset</a>
      </div>
    </>
  );
}

const SEVERITY: Record<string, string> = { low: "Low", medium: "Medium", high: "High" };

function Drift({ events }: { events: DriftEvent[] }) {
  return (
    <>
      <h3>Drift</h3>
      {events.length === 0 ? (
        <p>No drift found between the last scans.</p>
      ) : (
        <ul className="plain-list drift">
          {events.map((e) => (
            <li key={e.id}>
              <span className={`badge badge-sev-${e.severity}`}>{SEVERITY[e.severity]}</span> {e.message}
              {e.detail && <span className="small"> ({e.detail})</span>}{" "}
              <span className="small muted">
                {formatTime(e.detectedAt)}
                {e.against === "baseline" ? " · against the baseline" : ""}
              </span>
            </li>
          ))}
        </ul>
      )}
    </>
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
    <>
      <h3>Sample rows</h3>
      <p className="hint">
        The same rows on every scan, chosen by the hash of each row's key. Columns marked sensitive are masked.
      </p>
      <div className="buttons">
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
          <div className="table-scroll" role="region" aria-label="Sample rows (scrolls sideways)" tabIndex={0}>
            <table className="sample">
              <caption>
                {formatNumber(sample.rows.length)} of {formatNumber(sample.seen)} rows,{" "}
                {sample.method === "key"
                  ? `chosen by the hash of ${sample.keyFields?.join(", ")}`
                  : sample.method === "row"
                    ? "chosen by the hash of each whole row"
                    : "chosen by reservoir sampling"}
                {sample.masked && sample.masked.length > 0 && `; masked: ${sample.masked.join(", ")}`}
              </caption>
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
            </table>
          </div>
        )}
      </div>
    </>
  );
}

/** True for a recommendation whose values were held back (a sensitive column): it cannot be added in one click. */
const withheld = (r: Recommendation) =>
  (r.type === "allowed_values" || r.type === "in_range") && Object.keys(r.params ?? {}).length === 0;

function Recommendations({
  urn,
  unmask,
  canUnmask,
  onAdd,
}: {
  urn: string;
  unmask: boolean;
  canUnmask: boolean;
  onAdd: (r: Recommendation) => void;
}) {
  const [list, setList] = useState<Recommendation[] | null>(null);
  useEffect(() => {
    qualityApi
      .recommendations(urn, unmask)
      .then(setList)
      .catch(() => setList([]));
  }, [urn, unmask]);
  if (!list || list.length === 0) return null;
  return (
    <>
      <h3>Recommended rules</h3>
      <p className="hint">What the data already obeys, from its profile. Add a rule to notice when it stops.</p>
      <ul className="plain-list recommendations">
        {list.map((r) => (
          <li key={`${r.type}:${r.field ?? ""}`}>
            <strong>{r.name}</strong> <span className="muted small">({DIMENSIONS[r.dimension] ?? r.dimension})</span>
            <div className="small">{r.reason}</div>
            {!withheld(r) ? (
              <button type="button" onClick={() => onAdd(r)} aria-label={`Add rule: ${r.name}`}>
                Add rule
              </button>
            ) : (
              canUnmask && <div className="small muted">Tick “Show sensitive values” under Sample rows to see the values and add it.</div>
            )}
          </li>
        ))}
      </ul>
    </>
  );
}
