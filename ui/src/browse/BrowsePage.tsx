import { useEffect, useState } from "react";
import { PageHeader } from "../components/ui";
import { api, formatNumber, type Asset } from "../api";
import { useTitle } from "../components/useTitle";
import { href } from "../router";
import { BrowseTree } from "./BrowseTree";
import { DiagramSection } from "./DiagramSection";
import { browseApi, type BrowseAsset, type BrowseContainer, type BrowseSource, type Page } from "./browseApi";
import { formatLabel, Icon, sourceWords } from "./icons";
import "./browse.css";

const PAGE = 100;

/**
 * Browse: the catalogue as a tree beside what is chosen in it. A source lists its containers; a
 * container (schema, folder or bucket prefix) lists its assets and, when it holds tables, shows
 * them as a diagram with their relationships. ?asset= shows one asset and its neighbours.
 */
export function BrowsePage({
  source,
  container,
  asset,
}: {
  source: string | null;
  container: string | null;
  asset: string | null;
}) {
  const [sources, setSources] = useState<BrowseSource[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    browseApi
      .sources()
      .then(setSources)
      .catch((e: Error) => setError(e.message));
  }, []);
  const chosen = sources?.find((s) => s.name === source) ?? null;
  const words = sourceWords(chosen?.kind ?? "");
  const title = asset
    ? "Related tables"
    : container != null && source
      ? `${container || "(top level)"}`
      : chosen
        ? chosen.label
        : "Browse";
  useTitle(title);

  return (
    <>
      <PageHeader
        title={title}
        crumbs={[
          ...(source || container != null || asset ? [{ label: "Browse", href: href.browse() }] : []),
          ...(source
            ? [container != null ? { label: chosen?.label ?? source, href: href.browse(source) } : { label: chosen?.label ?? source }]
            : []),
          ...(source && container != null ? [{ label: container || "(top level)" }] : []),
        ]}
        meta={!source && !asset ? "Every source, schema, folder, table and file Kilnmark has scanned" : undefined}
      />
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <div className="browse">
        <aside className="browse-side" aria-label="Catalogue tree">
          <BrowseTree selectedSource={source} selectedContainer={container} />
        </aside>
        <div className="browse-main">
          {asset ? (
            <Neighbours urn={asset} />
          ) : source && container != null ? (
            <ContainerView source={source} container={container} words={words.container} />
          ) : source ? (
            <SourceView source={source} label={chosen?.label ?? source} kind={chosen?.kind ?? ""} />
          ) : (
            <Overview sources={sources} />
          )}
        </div>
      </div>
    </>
  );
}

function Overview({ sources }: { sources: BrowseSource[] | null }) {
  if (!sources) return <p role="status">Loading sources…</p>;
  return (
    <section className="panel" aria-labelledby="overview-h">
      <h2 id="overview-h">Sources</h2>
      <p className="hint">Choose a source in the tree, or below, to see what it holds.</p>
      <div className="table-scroll" role="region" aria-label="Sources (scrolls sideways on small screens)" tabIndex={0}>
        <table>
          <caption className="visually-hidden">Every source, with how many containers and assets it holds</caption>
          <thead>
            <tr>
              <th scope="col">Source</th>
              <th scope="col">Kind</th>
              <th scope="col">Schemas or folders</th>
              <th scope="col">Assets</th>
            </tr>
          </thead>
          <tbody>
            {sources.map((s) => (
              <tr key={s.name}>
                <th scope="row">
                  <a href={href.browse(s.name)}>{s.label}</a>
                </th>
                <td>{sourceWords(s.kind).kind}</td>
                <td>{formatNumber(s.containers)}</td>
                <td>{formatNumber(s.assets)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/** Kinds of asset in a container, in words: "3 tables, 1 view". */
function kindsText(kinds: Record<string, number>) {
  return Object.entries(kinds)
    .map(([k, n]) => `${formatNumber(n)} ${k === "document" ? (n === 1 ? "document" : "documents") : n === 1 ? k : `${k}s`}`)
    .join(", ");
}

function SourceView({ source, label, kind }: { source: string; label: string; kind: string }) {
  const [page, setPage] = useState<Page<BrowseContainer> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const words = sourceWords(kind);
  useEffect(() => {
    setPage(null);
    browseApi
      .containers(source, "", 0, PAGE)
      .then(setPage)
      .catch((e: Error) => setError(e.message));
  }, [source]);
  const more = () =>
    page &&
    browseApi
      .containers(source, "", page.items.length, PAGE)
      .then((p) => setPage({ ...p, items: [...page.items, ...p.items] }))
      .catch((e: Error) => setError(e.message));
  return (
    <section className="panel" aria-labelledby="source-h">
      <h2 id="source-h">
        {words.containers[0].toUpperCase() + words.containers.slice(1)} in {label}
      </h2>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {!page && !error && <p role="status">Loading…</p>}
      {page && page.items.length === 0 && <p className="muted">Nothing found yet. Scan the source on the Sources page.</p>}
      {page && page.items.length > 0 && (
        <>
          <ul className="plain-list browse-list">
            {page.items.map((c) => (
              <li key={c.name}>
                <a href={href.browse(source, c.name)} className="mono">
                  {c.name || "(top level)"}
                </a>{" "}
                <span className="muted">{kindsText(c.kinds)}</span>
              </li>
            ))}
          </ul>
          {page.items.length < page.total && (
            <button type="button" onClick={more}>
              Show more ({formatNumber(page.items.length)} of {formatNumber(page.total)} shown)
            </button>
          )}
        </>
      )}
    </section>
  );
}

function ContainerView({ source, container, words }: { source: string; container: string; words: string }) {
  const [page, setPage] = useState<Page<BrowseAsset> | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setPage(null);
    setError(null);
    browseApi
      .assets(source, container, "", 0, PAGE)
      .then(setPage)
      .catch((e: Error) => setError(e.message));
  }, [source, container]);
  const more = () =>
    page &&
    browseApi
      .assets(source, container, "", page.items.length, PAGE)
      .then((p) => setPage({ ...p, items: [...page.items, ...p.items] }))
      .catch((e: Error) => setError(e.message));
  const tabular = page?.items.some((a) => a.kind !== "document") ?? false;
  return (
    <>
      <section className="panel" aria-labelledby="contents-h">
        <h2 id="contents-h">What this {words} holds</h2>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        {!page && !error && <p role="status">Loading…</p>}
        {page && page.items.length === 0 && <p className="muted">Nothing here.</p>}
        {page && page.items.length > 0 && (
          <>
            <div className="table-scroll" role="region" aria-label="Assets (scrolls sideways on small screens)" tabIndex={0}>
              <table>
                <caption className="visually-hidden">
                  Assets in {container || "the top level"}, with their kind, rows and columns
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Name</th>
                    <th scope="col">Kind</th>
                    <th scope="col">Rows</th>
                    <th scope="col">Columns</th>
                    <th scope="col">Description</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((a) => (
                    <tr key={a.urn}>
                      <th scope="row">
                        <a href={href.asset(a.urn)} className="mono">
                          {a.name}
                        </a>
                      </th>
                      <td>
                        <span className="kind-label">
                          <Icon name={a.format} file={a.name} />
                          {formatLabel(a.format)}
                        </span>
                      </td>
                      <td>{formatNumber(a.rowCount)}</td>
                      <td>{a.fields > 0 ? formatNumber(a.fields) : "–"}</td>
                      <td className="small">{a.description ?? ""}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {page.items.length < page.total && (
              <button type="button" onClick={more}>
                Show more ({formatNumber(page.items.length)} of {formatNumber(page.total)} shown)
              </button>
            )}
          </>
        )}
      </section>
      {tabular && (
        <DiagramSection scope={{ kind: "container", source, container }} title={`Diagram of ${container || "the top level"}`} />
      )}
    </>
  );
}

function Neighbours({ urn }: { urn: string }) {
  const [asset, setAsset] = useState<Asset | null>(null);
  useEffect(() => {
    api
      .asset(urn)
      .then(setAsset)
      .catch(() => setAsset(null));
  }, [urn]);
  return (
    <>
      <p>
        {asset ? (
          <>
            Tables related to <a href={href.asset(urn)} className="mono">{`${asset.container}.${asset.name}`}</a> in{" "}
            {asset.source}.{" "}
            <a href={href.browse(asset.source, asset.container)}>Show all of {asset.container || "its top level"}</a>
          </>
        ) : (
          "Tables related to the chosen one."
        )}
      </p>
      <DiagramSection scope={{ kind: "asset", urn, depth: 1 }} title="Diagram of related tables" />
    </>
  );
}
