import { useEffect, useMemo, useState } from "react";
import { formatNumber, formatTime, type Asset } from "../api";
import { href } from "../router";
import { browseApi, type Relationship } from "../browse/browseApi";
import { RelationshipTable } from "../browse/RelationshipTable";
import { ColumnStats } from "./ColumnStats";
import "../browse/browse.css";

/**
 * The metadata and statistics of a table, view or file with columns: on the Overview tab a summary
 * and its relationships in and out, on the Columns tab per-column statistics. The asset page shows
 * it for assets with columns; documents have their own view.
 */
export function TableDetails({ asset, part }: { asset: Asset; part: "overview" | "columns" }) {
  const [relationships, setRelationships] = useState<Relationship[] | null>(null);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setRelationships(null);
    browseApi
      .relationshipsOf(asset.urn)
      .then((p) => {
        if (!live) return;
        setRelationships(p.items);
        setTotal(p.total);
      })
      .catch((e: Error) => live && setError(e.message));
    return () => {
      live = false;
    };
  }, [asset.urn]);

  const foreignKeys = useMemo(
    () =>
      new Set(
        (relationships ?? [])
          .filter((r) => r.from.urn === asset.urn && r.status !== "rejected")
          .flatMap((r) => r.from.fields),
      ),
    [relationships, asset.urn],
  );
  const outgoing = (relationships ?? []).filter((r) => r.from.urn === asset.urn && r.status !== "rejected").length;
  const incoming = (relationships ?? []).filter((r) => r.to.urn === asset.urn && r.status !== "rejected").length;
  const [loadingMore, setLoadingMore] = useState(false);
  const more = () => {
    if (!relationships || loadingMore) return;
    setLoadingMore(true);
    browseApi
      .relationshipsOf(asset.urn, relationships.length)
      .then((p) => {
        // Append to the list as it is now, never a copy captured before the request.
        setRelationships((rs) => [...(rs ?? []), ...p.items.filter((r) => !(rs ?? []).some((x) => x.id === r.id))]);
        setTotal(p.total);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoadingMore(false));
  };
  const onChange = (updated: Relationship) =>
    setRelationships((rs) => rs && rs.map((r) => (r.id === updated.id ? updated : r)));

  if (part === "columns") return <ColumnStats asset={asset} foreignKeys={foreignKeys} />;

  return (
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
        <div>
          <dt>Source</dt>
          <dd>
            <a href={href.browse(asset.source)}>{asset.source}</a>
          </dd>
        </div>
        <div>
          <dt>In</dt>
          <dd className="mono">
            <a href={href.browse(asset.source, asset.container)}>{asset.container || "(top level)"}</a>
          </dd>
        </div>
      </dl>

      <section aria-labelledby="rel-h" className="panel">
        <div className="panel-head">
          <h2 id="rel-h">Relationships</h2>
          {relationships && relationships.length > 0 && <a href={href.neighbours(asset.urn)}>Show as a diagram</a>}
        </div>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        {!relationships && !error && <p role="status">Loading relationships…</p>}
        {relationships && (
          <>
            {relationships.length > 0 && (
              <p className="muted">
                Refers to {outgoing} {outgoing === 1 ? "asset" : "assets"}; referred to by {incoming}
                {relationships.length < total ? ` (of the first ${formatNumber(relationships.length)} shown)` : ""}.
              </p>
            )}
            <RelationshipTable
              relationships={relationships}
              caption={`Relationships of ${asset.name}`}
              perspective={asset.urn}
              onChange={onChange}
            />
            {relationships.length < total && (
              <button type="button" onClick={more} disabled={loadingMore}>
                Show more relationships ({formatNumber(relationships.length)} of {formatNumber(total)} shown)
              </button>
            )}
          </>
        )}
      </section>
    </>
  );
}
