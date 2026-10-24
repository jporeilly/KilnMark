import { useEffect, useState } from "react";
import { api, formatTime, type Asset } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { TableDetails } from "../asset/TableDetails";

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
          <li>
            <a href={href.browse(asset.source)}>{asset.source}</a>
          </li>
          <li>
            <a href={href.browse(asset.source, asset.container)}>{asset.container || "(top level)"}</a>
          </li>
          <li aria-current="page">{asset.name}</li>
        </ol>
      </nav>
      <h1 tabIndex={-1} className="mono">
        {asset.name}
      </h1>
      <p className="meta">
        <span className="tag">{asset.kind}</span>
        {asset.removedAt && <span className="badge badge-failed">No longer in the source</span>}
      </p>
      {asset.description ? <p className="lead">{asset.description}</p> : <p className="muted">No description yet.</p>}

      <TableDetails asset={asset} />
      <p className="muted small">
        Identifier <span className="mono">{asset.urn}</span> · first seen {formatTime(asset.firstSeenAt)} ·{" "}
        <a href={href.search(asset.name)}>search for similar names</a>
      </p>
    </>
  );
}
