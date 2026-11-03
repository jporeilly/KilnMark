import { useState } from "react";
import { Switch } from "../components/ui";
import { allows, formatNumber } from "../api";
import { useRole } from "../components/role";
import { href } from "../router";
import { browseApi, type Relationship, type RelationshipEnd } from "./browseApi";

const percent = new Intl.NumberFormat("en-GB", { style: "percent", maximumFractionDigits: 0 });

/** Who the server names for a relationship accepted for its confidence (RelationshipStore.AUTO_ACCEPTED_BY). */
export const AUTO_ACCEPTED_BY = "Kilnmark (95% or more confident)";

const STATUS: Record<Relationship["status"], string> = {
  proposed: "Proposed",
  accepted: "Accepted",
  rejected: "Rejected",
};

/** One end as people read it: container.name (fields), with the source when it differs. */
export function endText(e: RelationshipEnd, withSource = false) {
  const where = e.container ? `${e.container}.${e.name}` : e.name;
  return `${withSource ? `${e.source}: ` : ""}${where} (${e.fields.join(", ")})`;
}

/** Why a relationship is there, in a sentence. */
export function why(r: Relationship) {
  if (r.kind === "declared") return r.name ? `Foreign key ${r.name} in the source` : "Foreign key in the source";
  const e = r.evidence ?? {};
  const parts: string[] = [];
  if (e.rule) parts.push(`Names: ${e.rule}`);
  if (e.containment != null) {
    parts.push(
      `${percent.format(e.containment)} of about ${formatNumber(e.fromDistinct)} distinct values found among ` +
        `about ${formatNumber(e.toDistinct)}`,
    );
  } else {
    parts.push("values could not be compared");
  }
  if (e.unique) parts.push(`target unique by ${e.unique}`);
  return parts.join("; ");
}

function End({ end, self, crossSource }: { end: RelationshipEnd; self?: boolean; crossSource: boolean }) {
  const fields = <span className="muted"> ({end.fields.join(", ")})</span>;
  if (self) {
    return (
      <>
        <span>This {end.kind.includes("view") ? "view" : "asset"}</span>
        {fields}
      </>
    );
  }
  return (
    <>
      <a href={href.asset(end.urn)} className="mono">
        {crossSource && <span className="muted">{end.source}: </span>}
        {end.container ? `${end.container}.` : ""}
        {end.name}
      </a>
      {fields}
    </>
  );
}

/**
 * Relationships as a table: the accessible form of a diagram, and where stewards accept or reject
 * inferred ones. Rejected relationships are hidden unless asked for.
 */
export function RelationshipTable({
  relationships,
  caption,
  perspective,
  onChange,
  listRejected = false,
}: {
  relationships: Relationship[];
  caption: string;
  /** The asset the page is about: shown as "This asset". */
  perspective?: string;
  onChange: (updated: Relationship) => void;
  /** Show rejected ones without asking (a list filtered to them). */
  listRejected?: boolean;
}) {
  const role = useRole();
  const canDecide = allows(role, "steward");
  const [showRejected, setShowRejected] = useState(false);
  const [busy, setBusy] = useState<number | null>(null);
  const [message, setMessage] = useState("");
  const [error, setError] = useState<string | null>(null);
  const rejected = relationships.filter((r) => r.status === "rejected").length;
  const shown = relationships.filter((r) => listRejected || showRejected || r.status !== "rejected");
  const crossSource = new Set(relationships.flatMap((r) => [r.from.source, r.to.source])).size > 1;

  const decide = (r: Relationship, decision: "accept" | "reject") => {
    setBusy(r.id);
    setError(null);
    browseApi
      .decide(r.id, decision)
      .then((updated) => {
        onChange(updated);
        setMessage(`${decision === "accept" ? "Accepted" : "Rejected"}: ${endText(r.from)} to ${endText(r.to)}.`);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(null));
  };

  if (relationships.length === 0) {
    return <p className="muted">No relationships found yet. Scanning a source finds declared keys and proposes others.</p>;
  }

  return (
    <div className="rel-table">
      <p className="visually-hidden" role="status" aria-live="polite">
        {message}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {rejected > 0 && !listRejected && (
        <div className="km-inline-switch">
          <Switch label={`Show ${rejected} rejected`} checked={showRejected} onChange={setShowRejected} />
        </div>
      )}
      <div className="table-scroll" role="region" aria-label={`${caption} (scrolls sideways on small screens)`} tabIndex={0}>
        <table className="rel">
          <caption className="visually-hidden">{caption}</caption>
          <thead>
            <tr>
              <th scope="col">From</th>
              <th scope="col">Refers to</th>
              <th scope="col">Status</th>
              <th scope="col" className="rel-why">Why</th>
              {canDecide && <th scope="col">Decision</th>}
            </tr>
          </thead>
          <tbody>
            {shown.map((r) => (
              <tr key={r.id}>
                <td>
                  <End end={r.from} self={r.from.urn === perspective} crossSource={crossSource} />
                </td>
                <td>
                  <End end={r.to} self={r.to.urn === perspective} crossSource={crossSource} />
                </td>
                <td>
                  <div className="rel-status">
                    <span className={`badge badge-rel-${r.status}`}>{STATUS[r.status]}</span>
                    <span className="small">
                      {r.kind === "declared"
                        ? "Declared by the source"
                        : `Inferred${r.confidence != null ? ` · ${percent.format(r.confidence)} confidence` : ""}`}
                    </span>
                    {r.decidedBy && (
                      <span className="muted small">
                        {r.decidedBy === AUTO_ACCEPTED_BY ? "Automatically, at 95% or more" : `by ${r.decidedBy}`}
                      </span>
                    )}
                  </div>
                </td>
                <td className="small rel-why">{why(r)}</td>
                {canDecide && (
                  <td>
                    {r.kind === "inferred" ? (
                      <div className="buttons tight">
                        {r.status !== "accepted" && (
                          <button
                            type="button"
                            disabled={busy === r.id}
                            aria-label={`Accept ${endText(r.from)} refers to ${endText(r.to)}`}
                            onClick={() => decide(r, "accept")}
                          >
                            Accept
                          </button>
                        )}
                        {r.status !== "rejected" && (
                          <button
                            type="button"
                            disabled={busy === r.id}
                            aria-label={`Reject ${endText(r.from)} refers to ${endText(r.to)}`}
                            onClick={() => decide(r, "reject")}
                          >
                            Reject
                          </button>
                        )}
                      </div>
                    ) : (
                      <span className="muted small">Set by the source</span>
                    )}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
