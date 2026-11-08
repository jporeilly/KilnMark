import { useEffect, useState } from "react";
import { accessReviewApi, formatTime, type AccessReview, type AccessReviewEntry } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { Card, DataTable, EmptyState, PageHeader, StatusChip } from "../components/ui";

/** Saves text as a file, through a link the browser follows at once. */
function save(text: string, name: string, type: string) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** What an entry can do, in words. */
export function access(e: AccessReviewEntry): string {
  const role = e.role ?? "no role";
  return e.unmask ? `${role}, sees unmasked values` : role;
}

/** When an entry was last used, or last signed in, in words. */
export function lastSeen(e: AccessReviewEntry): string {
  if (!e.lastUsedAt) return "Never";
  const when = formatTime(e.lastUsedAt);
  return e.kind === "provider" && e.signIns != null ? `${when} (${e.signIns} sign-ins)` : when;
}

/**
 * Settings › Access review, for admins (ADR 0037): every API token and everyone who signs in with
 * the identity provider, with what they can do and when they last did anything, for the periodic
 * review auditors ask for. Each download is recorded in the audit log.
 */
export function AccessReviewPage() {
  useTitle("Access review");
  const [review, setReview] = useState<AccessReview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    accessReviewApi
      .get()
      .then(setReview)
      .catch((e: Error) => setError(e.message));
  }, []);

  const download = () => {
    setSaving(true);
    accessReviewApi
      .csv()
      .then((text) => save(text, "kilnmark-access-review.csv", "text/csv"))
      .catch((e: Error) => setError(e.message))
      .finally(() => setSaving(false));
  };

  const tokens = review?.entries.filter((e) => e.kind === "token") ?? [];
  const people = review?.entries.filter((e) => e.kind === "provider") ?? [];

  return (
    <>
      <PageHeader
        title="Access review"
        crumbs={[{ label: "Settings", href: href.settings() }, { label: "Access review" }]}
        lead="Who can do what in Kilnmark: every API token, and everyone who signs in with your identity provider, with their role, whether they see sensitive values, and when they last did anything. Review it as often as your policy asks, revoke what is no longer needed under API tokens, and keep the download as your evidence."
        actions={
          <button type="button" className="primary" onClick={download} disabled={saving || !review}>
            {saving ? "Preparing…" : "Download CSV"}
          </button>
        }
      />
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {review && (
        <p className="muted">
          {review.tokenMaxLifetimeDays != null
            ? `Tokens on this server last at most ${review.tokenMaxLifetimeDays} days.`
            : "Tokens on this server may never expire: an administrator can require a longest lifetime (kilnmark.auth.token-max-lifetime)."}
        </p>
      )}
      <Card title="API tokens" titleId="review-tokens-h" className="km-section">
        {!review && !error && (
          <p role="status" className="muted">
            Loading…
          </p>
        )}
        {review && tokens.length === 0 && (
          <EmptyState title="No tokens" compact>
            No API tokens have been made.
          </EmptyState>
        )}
        {tokens.length > 0 && (
          <DataTable label="API tokens">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Can</th>
                <th scope="col">Status</th>
                <th scope="col">Expires</th>
                <th scope="col">Last used</th>
                <th scope="col">Made by</th>
              </tr>
            </thead>
            <tbody>
              {tokens.map((e, i) => (
                <tr key={`t${i}`}>
                  <td>{e.name}</td>
                  <td>{access(e)}</td>
                  <td>{e.status}</td>
                  <td className="small">{e.expiresAt ? formatTime(e.expiresAt) : "Never"}</td>
                  <td className="small">{lastSeen(e)}</td>
                  <td className="small">{e.createdBy}</td>
                </tr>
              ))}
            </tbody>
          </DataTable>
        )}
      </Card>
      <Card title="People who sign in with your identity provider" titleId="review-people-h" className="km-section">
        {review && people.length === 0 && (
          <EmptyState title="No one yet" compact>
            No one has signed in with an identity provider and been given a role.
          </EmptyState>
        )}
        {people.length > 0 && (
          <DataTable label="People who sign in with your identity provider">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Can (at last sign-in)</th>
                <th scope="col">Last signed in</th>
              </tr>
            </thead>
            <tbody>
              {people.map((e, i) => (
                <tr key={`p${i}`}>
                  <td>
                    {e.name}
                    {e.example && (
                      <>
                        {" "}
                        <StatusChip tone="warn" kind="example" title="A kilnmark.com example account, such as the Starter Pack's people: not for an install with real data">
                          Example account
                        </StatusChip>
                      </>
                    )}
                  </td>
                  <td>{access(e)}</td>
                  <td className="small">{lastSeen(e)}</td>
                </tr>
              ))}
            </tbody>
          </DataTable>
        )}
        <p className="hint">
          Their role comes from their groups at your identity provider each time they sign in: to take it away, take
          them out of the group there.
        </p>
        {people.some((e) => e.example) && (
          <p className="warning" role="note">
            Example accounts (kilnmark.com) have signed in here. They come with the Starter Pack, for trials and
            training: turn the pack off under Settings, Starter Pack, before this install holds real data.
          </p>
        )}
      </Card>
    </>
  );
}
