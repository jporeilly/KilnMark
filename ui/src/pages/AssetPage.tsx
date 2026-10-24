import { useEffect, useState } from "react";
import { api, formatNumber, formatTime, type Asset, type DocumentAsset, type Field } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { DocumentDetails, PartOf } from "../components/DocumentDetails";
import { DocumentReader } from "../components/DocumentReader";
import { KindIcon } from "../components/KindIcon";

const percent = new Intl.NumberFormat("en-GB", { style: "percent", maximumFractionDigits: 1 });

function filled(f: Field) {
  const p = f.profile;
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

/** Timestamps arrive as ISO text; show them as people read them. Dates and numbers stay as they are. */
function value(v: string) {
  return /^\d{4}-\d{2}-\d{2}T/.test(v) ? formatTime(v) : v;
}

function rangeText(f: Field) {
  const p = f.profile;
  if (!p) return "–";
  if (p.min_length != null && p.max_length != null) {
    return p.min_length === p.max_length
      ? `${p.min_length} characters`
      : `${p.min_length} to ${p.max_length} characters`;
  }
  if (p.true != null) return `${formatNumber(p.true)} true`;
  if (p.min != null && p.max != null) return `${value(p.min)} to ${value(p.max)}`;
  return "–";
}

export function AssetPage({ urn }: { urn: string }) {
  const [asset, setAsset] = useState<Asset | null>(null);
  const [error, setError] = useState<string | null>(null);
  useTitle(asset ? `${asset.container}.${asset.name}` : "Asset");

  useEffect(() => {
    let live = true;
    setAsset(null);
    setError(null);
    api
      .asset(urn)
      .then((a) => live && setAsset(a))
      .catch((e: Error) => live && setError(e.message));
    return () => {
      live = false;
    };
  }, [urn]);

  if (error) {
    return (
      <>
        <h1 tabIndex={-1}>Asset not found</h1>
        <p role="alert" className="error">{error}</p>
      </>
    );
  }
  if (!asset) {
    return (
      <>
        <h1 tabIndex={-1}>Loading…</h1>
        <p role="status">Loading the asset</p>
      </>
    );
  }

  return (
    <>
      <nav aria-label="Breadcrumb" className="crumbs">
        <ol>
          <li>{asset.source}</li>
          <li>{asset.container}</li>
          <li aria-current="page">{asset.name}</li>
        </ol>
      </nav>
      <h1 tabIndex={-1} className="mono">
        {asset.name}
      </h1>
      <p className="meta">
        <span className="tag">
          <KindIcon kind={asset.kind} name={asset.name} />
          {asset.kind}
        </span>
        {asset.removedAt && <span className="badge badge-failed">No longer in the source</span>}
      </p>
      {asset.description ? <p className="lead">{asset.description}</p> : <p className="muted">No description yet.</p>}
      {(asset as DocumentAsset).parent && <PartOf parent={(asset as DocumentAsset).parent!} kind={asset.kind} />}
      {asset.kind === "document" && <DocumentDetails asset={asset as DocumentAsset} />}
      {asset.kind === "document" && (asset as DocumentAsset).document?.readable && (
        <DocumentReader urn={asset.urn} name={asset.name} />
      )}
      {asset.kind !== "document" && (
      <>

      <dl className="facts">
        <div>
          <dt>Rows</dt>
          <dd>
            {formatNumber(asset.rowCount)}
            {asset.rowCountComplete === false && <span className="muted"> (sample read)</span>}
          </dd>
        </div>
        <div>
          <dt>Columns</dt>
          <dd>{asset.fields.length}</dd>
        </div>
        <div>
          <dt>Primary key</dt>
          <dd className="mono">{asset.primaryKey.length ? asset.primaryKey.join(", ") : "None declared"}</dd>
        </div>
        <div>
          <dt>Last scanned</dt>
          <dd>{formatTime(asset.lastScannedAt)}</dd>
        </div>
      </dl>

      <section aria-labelledby="fields-h" className="panel">
        <h2 id="fields-h">Columns and their profiles</h2>
        <div className="table-scroll" role="region" aria-label="Columns table (scrolls sideways on small screens)" tabIndex={0}>
          <table className="fields">
            <caption className="visually-hidden">
              Each column of {asset.name}: its type, how much of it is filled, distinct values, range and most
              common values
            </caption>
            <thead>
              <tr>
                <th scope="col">Column</th>
                <th scope="col">Type</th>
                <th scope="col">Filled</th>
                <th scope="col">Distinct</th>
                <th scope="col">Range</th>
                <th scope="col">Most common</th>
              </tr>
            </thead>
            <tbody>
              {asset.fields.map((f) => {
                const fill = filled(f);
                return (
                  <tr key={f.name}>
                    <th scope="row" className="mono">
                      {f.name}
                      {asset.primaryKey.includes(f.name) && <span className="tag tag-key">key</span>}
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
                    <td>{rangeText(f)}</td>
                    <td>
                      {f.profile?.top && f.profile.top.length > 0 ? (
                        <ul className="top">
                          {f.profile.top.slice(0, 3).map((t) => (
                            <li key={t.value}>
                              <span className="mono">{t.value}</span>{" "}
                              <span className="muted">{formatNumber(t.count)}</span>
                            </li>
                          ))}
                        </ul>
                      ) : (
                        "–"
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>
      </>
      )}
      <p className="muted small">
        Identifier <span className="mono">{asset.urn}</span> · first seen {formatTime(asset.firstSeenAt)} ·{" "}
        <a href={href.search(asset.name)}>search for similar names</a>
      </p>
    </>
  );
}
