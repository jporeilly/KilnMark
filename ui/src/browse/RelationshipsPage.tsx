import { useEffect, useState } from "react";
import { PageHeader } from "../components/ui";
import { allows, formatNumber } from "../api";
import { useRole } from "../components/role";
import { useTitle } from "../components/useTitle";
import { go, href } from "../router";
import { browseApi, type BrowseSource, type Page, type Relationship } from "./browseApi";
import { BrowseTabs } from "./BrowseTabs";
import { RelationshipTable } from "./RelationshipTable";
import "./browse.css";

const PAGE = 50;

/** Every relationship, filtered by status and source: where stewards review proposals. */
export function RelationshipsPage({ status }: { status: string }) {
  useTitle("Relationships");
  const role = useRole();
  const [source, setSource] = useState("");
  const [sources, setSources] = useState<BrowseSource[]>([]);
  const [page, setPage] = useState<Page<Relationship> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const filter = status === "all" ? undefined : status;

  useEffect(() => {
    browseApi
      .sources()
      .then(setSources)
      .catch(() => setSources([]));
  }, []);

  useEffect(() => {
    let live = true;
    setPage(null);
    setError(null);
    browseApi
      .relationships({ status: filter, source: source || undefined, offset: 0, limit: PAGE })
      .then((p) => live && setPage(p))
      .catch((e: Error) => live && setError(e.message));
    return () => {
      live = false;
    };
  }, [filter, source]);

  const more = () =>
    page &&
    browseApi
      .relationships({ status: filter, source: source || undefined, offset: page.items.length, limit: PAGE })
      .then((p) => setPage({ ...p, items: [...page.items, ...p.items] }))
      .catch((e: Error) => setError(e.message));

  const onChange = (updated: Relationship) =>
    setPage((p) => p && { ...p, items: p.items.map((r) => (r.id === updated.id ? updated : r)) });

  return (
    <>
      <PageHeader
        title="Relationships"
        crumbs={[{ label: "Browse", href: href.browse() }, { label: "Relationships" }]}
        lead={
          <>
            Keys the sources declare, and joins Kilnmark found in names and values. Joins found with 95% confidence
            or more are accepted automatically; the rest wait here.{" "}
            {allows(role, "steward")
              ? "Accept the proposals that are real joins and reject the rest; your decisions are kept on every scan."
              : "A steward accepts or rejects the proposals."}
          </>
        }
      />
      <BrowseTabs current="relationships" />
      {/* Changing a filter changes the address; keep focus on the control rather than the heading. */}
      <div className="dg-controls" data-keeps-focus>
        <div className="field">
          <label htmlFor="rel-status">Show</label>
          <select id="rel-status" value={status} onChange={(e) => go(href.relationships(e.target.value))}>
            <option value="proposed">Proposals to review</option>
            <option value="accepted">Accepted and declared</option>
            <option value="rejected">Rejected</option>
            <option value="all">All</option>
          </select>
        </div>
        <div className="field">
          <label htmlFor="rel-source">Source</label>
          <select id="rel-source" value={source} onChange={(e) => setSource(e.target.value)}>
            <option value="">Every source</option>
            {sources.map((s) => (
              <option key={s.name} value={s.name}>
                {s.label}
              </option>
            ))}
          </select>
        </div>
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {!page && !error && <p role="status">Loading relationships…</p>}
      {page && (
        <section className="panel" aria-labelledby="rel-h">
          <h2 id="rel-h">
            {formatNumber(page.total)} {page.total === 1 ? "relationship" : "relationships"}
          </h2>
          <RelationshipTable
            relationships={page.items}
            caption="Relationships"
            onChange={onChange}
            listRejected={status === "rejected" || status === "all"}
          />
          {page.items.length < page.total && (
            <button type="button" onClick={more}>
              Show more ({formatNumber(page.items.length)} of {formatNumber(page.total)} shown)
            </button>
          )}
        </section>
      )}
    </>
  );
}
