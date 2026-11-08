import { Fragment, useState } from "react";
import { formatNumber, formatTime, type Asset, type Field, type HistogramBin, type Profile, plural } from "../api";

const percent = new Intl.NumberFormat("en-GB", { style: "percent", maximumFractionDigits: 1 });

/** Timestamps arrive as ISO text; show them as people read them. Dates and numbers stay as they are. */
export function value(v: string) {
  return /^\d{4}-\d{2}-\d{2}T/.test(v) ? formatTime(v) : v;
}

function filled(p: Profile | null) {
  if (!p || p.rows === 0) return null;
  return (p.rows - p.nulls) / p.rows;
}

function distinctText(f: Field, primaryKey: string[]) {
  const p = f.profile;
  if (!p) return "–";
  const values = p.rows - p.nulls;
  if (values === 0) return "–";
  if (primaryKey.length === 1 && primaryKey[0] === f.name) return `${formatNumber(values)} (unique key)`;
  const n = formatNumber(p.distinct);
  return p.distinct_exact === false ? `about ${n}` : n;
}

function rangeText(p: Profile | null) {
  if (!p) return "–";
  if (p.min_length != null && p.max_length != null) {
    return p.min_length === p.max_length
      ? plural(p.min_length, "character")
      : `${formatNumber(p.min_length)} to ${plural(p.max_length, "character")}`;
  }
  if (p.true != null) return `${formatNumber(p.true)} true`;
  if (p.min != null && p.max != null) return `${value(p.min)} to ${value(p.max)}`;
  return "–";
}

/** A small histogram, drawn as bars, with the same numbers in a table for screen readers. */
function Histogram({ bins, field }: { bins: HistogramBin[]; field: string }) {
  const max = Math.max(1, ...bins.map((b) => b.count));
  const w = 280;
  const h = 72;
  const gap = 2;
  const bw = (w - gap * (bins.length - 1)) / bins.length;
  const label = (b: HistogramBin) => (b.from === b.to ? value(b.from) : `${value(b.from)} to ${value(b.to)}`);
  return (
    <figure className="histogram">
      <figcaption>How values spread from the smallest to the largest (estimated)</figcaption>
      <svg viewBox={`0 0 ${w} ${h}`} width={w} height={h} aria-hidden="true" focusable="false">
        {bins.map((b, i) => {
          const bh = b.count === 0 ? 0 : Math.max(2, (b.count / max) * (h - 4));
          return (
            <rect key={i} x={i * (bw + gap)} y={h - bh} width={bw} height={bh} rx={1.5}>
              <title>{`${label(b)}: ${formatNumber(b.count)}`}</title>
            </rect>
          );
        })}
      </svg>
      <div className="histogram-axis small muted" aria-hidden="true">
        <span>{value(bins[0].from)}</span>
        <span>{value(bins[bins.length - 1].to)}</span>
      </div>
      <table className="visually-hidden">
        <caption>Distribution of {field}</caption>
        <thead>
          <tr>
            <th scope="col">Values</th>
            <th scope="col">Rows</th>
          </tr>
        </thead>
        <tbody>
          {bins.map((b, i) => (
            <tr key={i}>
              <th scope="row">{label(b)}</th>
              <td>{formatNumber(b.count)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}

/** Everything the profile knows about one column. */
function ColumnDetail({ field, primaryKey }: { field: Field; primaryKey: string[] }) {
  const p = field.profile;
  if (!p) return <p className="muted">This column has not been profiled yet. Scan its source to profile it.</p>;
  const values = p.rows - p.nulls;
  const facts: [string, string][] = [
    ["Rows profiled", formatNumber(p.rows)],
    ["Empty (null)", p.rows ? `${formatNumber(p.nulls)} (${percent.format(p.nulls / p.rows)})` : "–"],
    [p.distinct_exact === false ? "Distinct (estimated)" : "Distinct", distinctText(field, primaryKey)],
  ];
  if (p.min != null) facts.push(["Smallest", value(p.min)]);
  if (p.p25 != null) facts.push(["Lower quartile", value(p.p25)]);
  if (p.median != null) facts.push(["Median", value(p.median)]);
  if (p.p75 != null) facts.push(["Upper quartile", value(p.p75)]);
  if (p.max != null) facts.push(["Largest", value(p.max)]);
  if (p.mean != null) facts.push(["Mean", p.mean]);
  if (p.min_length != null) facts.push(["Shortest", plural(p.min_length, "character")]);
  if (p.max_length != null) facts.push(["Longest", plural(p.max_length, "character")]);
  if (p.true != null && values > 0) facts.push(["True", `${formatNumber(p.true)} (${percent.format(p.true / values)})`]);
  return (
    <div className="col-detail">
      <dl className="col-facts">
        {facts.map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd>{v}</dd>
          </div>
        ))}
      </dl>
      {p.histogram && p.histogram.length > 0 && <Histogram bins={p.histogram} field={field.name} />}
      {p.top && p.top.length > 0 && (
        <div className="top-values">
          <h3>Most common values</h3>
          <ul>
            {p.top.map((t) => (
              <li key={t.value}>
                <span className="mono" title={t.value}>{t.value}</span>
                <span className="top-bar" aria-hidden="true">
                  <span style={{ width: `${Math.round((t.count / Math.max(1, p.rows)) * 100)}%` }} />
                </span>
                <span className="muted">
                  {formatNumber(t.count)} ({percent.format(t.count / Math.max(1, p.rows))})
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {field.description && <p className="small">{field.description}</p>}
      {field.profiledAt && <p className="muted small">Profiled {formatTime(field.profiledAt)}</p>}
    </div>
  );
}

/**
 * Per-column statistics from the profile, as a compact table. Each column name opens a panel
 * below its row with the full profile: quartiles, a histogram, most common values and lengths.
 */
export function ColumnStats({ asset, foreignKeys }: { asset: Asset; foreignKeys: Set<string> }) {
  const [open, setOpen] = useState<Set<string>>(() => new Set());
  const toggle = (name: string) =>
    setOpen((o) => {
      const next = new Set(o);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  const all = open.size === asset.fields.length;
  return (
    <section aria-labelledby="fields-h" className="panel">
      <div className="panel-head">
        <h2 id="fields-h">Columns and their statistics</h2>
        {asset.fields.length > 0 && (
          <button type="button" onClick={() => setOpen(all ? new Set() : new Set(asset.fields.map((f) => f.name)))}>
            {all ? "Close every column" : "Open every column"}
          </button>
        )}
      </div>
      {asset.fields.length === 0 ? (
        <p className="muted">No columns recorded.</p>
      ) : (
        <div className="table-scroll" role="region" aria-label="Columns table (scrolls sideways on small screens)" tabIndex={0}>
          <table className="fields">
            <caption className="visually-hidden">
              Each column of {asset.name}: its type, how much of it is filled, distinct values and range. Open a
              column for its full profile.
            </caption>
            <thead>
              <tr>
                <th scope="col">Column</th>
                <th scope="col">Type</th>
                <th scope="col">Filled</th>
                <th scope="col">Distinct</th>
                <th scope="col">Range or length</th>
              </tr>
            </thead>
            <tbody>
              {asset.fields.map((f) => {
                const fill = filled(f.profile);
                const isOpen = open.has(f.name);
                const panel = `col-${f.position}`;
                return (
                  <Fragment key={f.name}>
                    <tr className={isOpen ? "is-open" : undefined}>
                      <th scope="row">
                        <button
                          type="button"
                          className="disclosure mono"
                          aria-expanded={isOpen}
                          aria-controls={panel}
                          onClick={() => toggle(f.name)}
                        >
                          <span aria-hidden="true">{isOpen ? "▾" : "▸"}</span> {f.name}
                        </button>
                        {asset.primaryKey.includes(f.name) && <span className="tag tag-key">key</span>}
                        {foreignKeys.has(f.name) && <span className="tag tag-key">refers</span>}
                      </th>
                      <td className="mono">
                        {f.sourceType}
                        {!f.nullable && <span className="muted"> not null</span>}
                      </td>
                      <td>
                        {fill == null ? (
                          "–"
                        ) : (
                          <span className="fill">
                            <span className="fill-bar" aria-hidden="true">
                              <span style={{ width: `${Math.round(fill * 100)}%` }} />
                            </span>
                            {percent.format(fill)}
                          </span>
                        )}
                      </td>
                      <td>{distinctText(f, asset.primaryKey)}</td>
                      <td>{rangeText(f.profile)}</td>
                    </tr>
                    {isOpen && (
                      <tr className="detail-row">
                        <td colSpan={5} id={panel}>
                          <ColumnDetail field={f} primaryKey={asset.primaryKey} />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
