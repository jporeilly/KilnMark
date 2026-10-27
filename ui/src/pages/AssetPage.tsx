import { useEffect, useState } from "react";
import { api, formatNumber, formatTime, type Asset, type DocumentAsset } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { TableDetails } from "../asset/TableDetails";
import { DocumentDetails, PartOf } from "../components/DocumentDetails";
import { DocumentReader } from "../components/DocumentReader";
import { KindIcon } from "../components/KindIcon";
import { QualityPanel } from "../components/QualityPanel";
import { SourceTile } from "../components/ConnectorTile";
import { Card, EmptyState, PageHeader, StatusChip, TabPanel, Tabs, useTabsId } from "../components/ui";
import { Icon } from "../components/icons";
import { formatAgo } from "../format";

/**
 * One asset: a header with its source tile, name and facts, then tabs. Tables, views and files
 * with columns have Overview (summary and relationships), Columns (statistics) and Quality
 * (scores, rules with their history, recommended rules, patterns, drift and samples). Documents
 * have Overview and, when readable, Text. The tab is kept in the address (?tab=), so it can be
 * linked to and survives a reload.
 */
export function AssetPage({ urn, tab }: { urn: string; tab?: string | null }) {
  const [asset, setAsset] = useState<Asset | null>(null);
  const [error, setError] = useState<string | null>(null);
  const idBase = useTabsId();
  // The source's kind (for its tile) is not on the asset: ask for the source once.
  const [kind, setKind] = useState<string | null>(null);
  useTitle(asset ? `${asset.container ? `${asset.container}.` : ""}${asset.name}` : "Asset");

  useEffect(() => {
    let live = true;
    setAsset(null);
    setError(null);
    api
      .asset(urn)
      .then((a) => {
        if (!live) return;
        setAsset(a);
        api
          .source(a.source)
          .then((src) => live && setKind(src.kind))
          .catch(() => undefined);
      })
      .catch((e: Error) => live && setError(e.message));
    return () => {
      live = false;
    };
  }, [urn]);

  if (error) {
    return (
      <>
        <PageHeader title="Asset not found" crumbs={[{ label: "Browse", href: href.browse() }]} />
        <Card>
          <EmptyState icon={<Icon name="search" size={22} />} title="This asset could not be opened">
            <p role="alert">{error}</p>
          </EmptyState>
        </Card>
      </>
    );
  }
  if (!asset) {
    return (
      <>
        <PageHeader title="Loading…" />
        <p role="status" className="muted">
          Loading the asset
        </p>
      </>
    );
  }

  const doc = asset as DocumentAsset;
  const isDocument = asset.kind === "document";
  const tabs = isDocument
    ? [{ id: "overview", label: "Overview" }, ...(doc.document?.readable ? [{ id: "text", label: "Text" }] : [])]
    : [
        { id: "overview", label: "Overview" },
        { id: "columns", label: "Columns", count: asset.fields.length },
        { id: "quality", label: "Quality" },
      ];
  const selected = tabs.some((t) => t.id === tab) ? tab! : "overview";
  const select = (id: string) => {
    // Replace the address so Back leaves the asset rather than stepping through its tabs.
    const next = href.asset(asset.urn, id === "overview" ? undefined : id);
    window.history.replaceState(null, "", next);
    window.dispatchEvent(new HashChangeEvent("hashchange"));
  };

  const meta = [
    asset.kind.charAt(0).toUpperCase() + asset.kind.slice(1),
    asset.rowCount != null ? `${formatNumber(asset.rowCount)} rows` : null,
    !isDocument ? `${asset.fields.length} ${asset.fields.length === 1 ? "column" : "columns"}` : null,
    asset.lastScannedAt ? `scanned ${formatAgo(asset.lastScannedAt)}` : null,
  ].filter(Boolean);

  return (
    <>
      <PageHeader
        crumbs={[
          { label: "Browse", href: href.browse() },
          { label: asset.source, href: href.browse(asset.source) },
          { label: asset.container || "(top level)", href: href.browse(asset.source, asset.container) },
          { label: asset.name },
        ]}
        tile={<SourceTile kind={kind ?? "database"} size="lg" />}
        title={asset.container ? `${asset.container}.${asset.name}` : asset.name}
        titleClassName="km-asset-title"
        meta={meta.join(" · ")}
        chips={
          <>
            <StatusChip tone="sky">
              <KindIcon kind={asset.kind} name={asset.name} />
              {asset.kind}
            </StatusChip>
            {asset.removedAt && <StatusChip tone="bad">No longer in the source</StatusChip>}
          </>
        }
      />
      {asset.description ? <p className="lead">{asset.description}</p> : null}
      {doc.parent && <PartOf parent={doc.parent} kind={asset.kind} />}

      <div data-keeps-focus>
        <Tabs tabs={tabs} selected={selected} onSelect={select} label="Asset sections" idBase={idBase} />
      </div>
      <TabPanel idBase={idBase} selected={selected}>
        {isDocument && selected === "overview" && <DocumentDetails asset={doc} />}
        {isDocument && selected === "text" && doc.document?.readable && (
          <DocumentReader urn={asset.urn} name={asset.name} profile={doc.document} />
        )}
        {!isDocument && (selected === "overview" || selected === "columns") && (
          <TableDetails asset={asset} part={selected} />
        )}
        {!isDocument && selected === "quality" && <QualityPanel asset={asset} />}
      </TabPanel>
      <p className="muted small km-footnote">
        Identifier <span className="mono">{asset.urn}</span> · first seen {formatTime(asset.firstSeenAt)} ·{" "}
        <a href={href.search(asset.name)}>search for similar names</a>
      </p>
    </>
  );
}

