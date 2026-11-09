import { useEffect, useState } from "react";
import { pipelinesApi, type AssetLineage } from "../api";
import { LineageGraph } from "./LineageGraph";
import type { LineageLink } from "./lineage";

/**
 * An asset's lineage as "came from" pairs: each asset upstream into it, and it into each asset
 * downstream, once per pipeline that recorded the pair (the API gives one entry per run).
 */
export function assetLinks(l: AssetLineage): LineageLink[] {
  const seen = new Set<string>();
  const out: LineageLink[] = [];
  const add = (input: string, output: string, pipeline: string) => {
    const key = JSON.stringify([input, output, pipeline]);
    if (seen.has(key)) return;
    seen.add(key);
    out.push({ input, output, pipeline });
  };
  for (const e of l.upstream) add(e.urn, l.urn, e.pipeline);
  for (const e of l.downstream) add(l.urn, e.urn, e.pipeline);
  return out;
}

/**
 * The Lineage section of an asset's Overview: what it came from on the left, what was made from it
 * on the right, each line naming the pipelines that recorded it.
 */
export function AssetLineagePanel({ urn, name }: { urn: string; name: string }) {
  const [lineage, setLineage] = useState<AssetLineage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setLineage(null);
    setError(null);
    pipelinesApi
      .lineage(urn)
      .then((l) => live && setLineage(l))
      .catch((e: Error) => live && setError(e.message));
    return () => {
      live = false;
    };
  }, [urn]);

  const links = lineage ? assetLinks(lineage) : [];
  return (
    <section aria-labelledby="lineage-h" className="panel">
      <h2 id="lineage-h">Lineage</h2>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {!lineage && !error && <p role="status">Loading lineage…</p>}
      {lineage && links.length === 0 && (
        <p className="muted">No lineage recorded. Kilnmark records it when a pipeline writes a table or file.</p>
      )}
      {links.length > 0 && <LineageGraph links={links} centre={urn} label={`Lineage of ${name}`} />}
    </section>
  );
}
