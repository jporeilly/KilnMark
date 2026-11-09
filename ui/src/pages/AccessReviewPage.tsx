import { useEffect, useState } from "react";
import {
  accessReviewApi,
  formatTime,
  type AccessReview,
  type AccessReviewEntry,
  type AccessReviewLine,
  type AccessRole,
  plural,
} from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { Card, DataTable, EmptyState, PageHeader, StatusChip } from "../components/ui";
import { Who } from "../components/Who";

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

const ROLES: AccessRole[] = ["viewer", "steward", "designer", "admin"];
const ROLE_NAME: Record<AccessRole, string> = { viewer: "Viewer", steward: "Steward", designer: "Designer", admin: "Admin" };

/** What an entry can do, in words. */
export function access(e: AccessReviewEntry): string {
  const role = e.role ?? "no role";
  return e.unmask ? `${role}, sees unmasked values` : role;
}

/** Whether a person has not signed in since Kilnmark recorded them being made, activated or disabled. */
export function notSignedIn(e: AccessReviewEntry): boolean {
  return e.state === "waiting" || e.state === "not-activated" || e.state === "disabled";
}

/**
 * Whether an entry sees values marked sensitive unmasked now: an active token holding the grant, or a
 * person whose last sign-in gave it.
 */
export function seesUnmasked(e: AccessReviewEntry): boolean {
  if (!e.unmask) return false;
  return e.kind === "token" ? e.status === "active" : !notSignedIn(e);
}

/**
 * The sensitive-data grant of a person, in words: whether they see sensitive values unmasked (now, or
 * once they sign in), or hold the grant's group without being a designer, so see them masked.
 */
export function sensitiveWords(e: AccessReviewEntry): string {
  const groups = e.sensitiveGroups ?? [];
  if (e.unmask) {
    if (e.state === "waiting") return "Will see them unmasked once signed in";
    return notSignedIn(e) ? "Will see them unmasked once activated and signed in" : "Sees them unmasked";
  }
  if (groups.length > 0) {
    // a designer whose last sign-in gave no grant: the unmask settings changed since
    return e.role === "designer"
      ? `In ${groups.join(", ")}, but their last sign-in did not give the grant: sees them masked`
      : `In ${groups.join(", ")}, but not a designer: sees them masked`;
  }
  return e.groups == null && e.kind === "provider" ? "Not recorded" : "No";
}

/** A person's groups, in words. */
export function groupWords(e: AccessReviewEntry): string {
  if (e.groups == null) return "Not recorded";
  return e.groups.length === 0 ? "None that gives a role" : e.groups.join(", ");
}

/** What a role may do in one line of the table, in words. */
export function cellWord(v: AccessReviewLine["byRole"][AccessRole]): string {
  return v === "yes" ? "Yes" : v === "grant" ? "With the grant" : "No";
}

/**
 * What the review says when example accounts have signed in. They come only with a Starter Pack
 * install, which never becomes a production one (ADR 0051): turning the pack off would leave the
 * example people at the identity provider, so for real data the install is made afresh.
 */
export const EXAMPLE_ACCOUNTS =
  "Example accounts (kilnmark.com) have signed in here. They come with the Starter Pack: a Starter Pack install is " +
  "for training and development only. For real data, install Kilnmark afresh in production mode.";

/**
 * What the review cannot know, because only the identity provider holds it: Kilnmark never asks it,
 * and holds no credential for the bundled Keycloak.
 */
export const ONLY_AT_THE_PROVIDER =
  "Kilnmark knows people from its own records: everyone who has signed in, and the people the control script made " +
  "in the bundled Keycloak. It never asks your identity provider, so someone added or changed only there (in " +
  "Keycloak's console, say) shows here once they sign in, and someone removed or disabled there still shows as " +
  "they were. How long a session may sit unused is your provider's own setting too (the realm keycloak-setup " +
  "makes allows 2 hours). Check those at your identity provider.";

/** When an entry was last used, or last signed in, in words. */
export function lastSeen(e: AccessReviewEntry): string {
  if (notSignedIn(e)) {
    return e.createdAt ? `Not yet (added ${formatTime(e.createdAt)})` : "Not yet";
  }
  if (!e.lastUsedAt) return "Never";
  const when = formatTime(e.lastUsedAt);
  // a sign-in given no role counts no sign-ins, which would read oddly beside its time
  return e.kind === "provider" && e.signIns != null && e.state !== "no-role"
    ? `${when} (${plural(e.signIns, "sign-in")})`
    : when;
}

/** The tone of a person's state. */
function stateTone(e: AccessReviewEntry): "good" | "warn" | "bad" | "neutral" {
  switch (e.state) {
    case "signed-in":
      return e.role ? "good" : "neutral";
    case "disabled":
      return "bad";
    case "no-role":
      return "neutral";
    default:
      return "warn";
  }
}

/** A chip for whoever sees values marked sensitive unmasked now. */
function Unmasked() {
  return (
    <StatusChip tone="bad" kind="unmasked" title="Sees values of columns marked sensitive unmasked">
      Sees sensitive values
    </StatusChip>
  );
}

/**
 * Settings › Access review, for admins (ADR 0037): what each role may do, the settings that limit
 * people, every API token and everyone who signs in with the identity provider (made by the control
 * script and waiting, or signed in), with what they can do and when they last did anything, for the
 * periodic review auditors ask for. Each view and download is recorded in the audit log.
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
  const lines = review?.roles ?? [];
  const rules = review?.rules ?? [];
  const groups = review?.groups ?? {};
  const unmaskGroups = groups.unmask ?? [];

  return (
    <>
      <PageHeader
        title="Access review"
        crumbs={[{ label: "Settings", href: href.settings() }, { label: "Access review" }]}
        lead="Who can do what in Kilnmark: what each role may do, the settings that limit people, every API token, and everyone who signs in with your identity provider, with their role, their groups, whether they see sensitive values, and when they last did anything. Review it as often as your policy asks, revoke what is no longer needed under API tokens, and keep the download as your evidence."
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
      {!review && !error && (
        <p role="status" className="muted">
          Loading…
        </p>
      )}
      {lines.length > 0 && (
        <Card title="What each role can do" titleId="review-roles-h" className="km-section">
          <p className="hint">
            Each role can do everything the one before it can. Seeing sensitive values unmasked is a grant, not a role:
            only a designer holds it, and admins see those values masked.
          </p>
          <DataTable label="What each role can do">
            <thead>
              <tr>
                <th scope="col">What</th>
                {ROLES.map((r) => (
                  <th scope="col" key={r}>
                    {ROLE_NAME[r]}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              <tr>
                <th scope="row">Given by these groups at your identity provider</th>
                {ROLES.map((r) => (
                  <td key={r} className="small">
                    {(groups[r] ?? []).length > 0 ? (groups[r] ?? []).join(", ") : "None set"}
                  </td>
                ))}
              </tr>
              {lines.map((l) => (
                <tr key={l.id} data-line={l.id}>
                  <th scope="row">{l.what}</th>
                  {ROLES.map((r) => (
                    <td key={r}>{cellWord(l.byRole[r])}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </DataTable>
          <p className="hint">
            {unmaskGroups.length > 0
              ? `The sensitive-data grant: a designer in ${unmaskGroups.join(", ")} at your identity provider, or a designer token made with it.`
              : "The sensitive-data grant: a designer token made with it. No group at your identity provider gives it (kilnmark.auth.oidc.unmask-groups is not set)."}
          </p>
        </Card>
      )}
      {rules.length > 0 && (
        <Card title="Rules in force" titleId="review-rules-h" className="km-section">
          <p className="hint">The settings on this server that limit people. They are changed in its settings, not here.</p>
          <dl className="facts">
            {rules.map((r) => (
              <div key={r.id} data-rule={r.id}>
                <dt>{r.label}</dt>
                <dd>
                  {r.value} <code className="small">{r.setting}</code>
                </dd>
              </div>
            ))}
          </dl>
        </Card>
      )}
      {review && rules.length === 0 && (
        <p className="muted">
          {review.tokenMaxLifetimeDays != null
            ? `Tokens on this server last at most ${review.tokenMaxLifetimeDays} days.`
            : "Tokens on this server may never expire: an administrator can require a longest lifetime (kilnmark.auth.token-max-lifetime)."}
        </p>
      )}
      <Card title="API tokens" titleId="review-tokens-h" className="km-section">
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
                  <td>
                    {access(e)}
                    {seesUnmasked(e) && (
                      <>
                        {" "}
                        <Unmasked />
                      </>
                    )}
                  </td>
                  <td>{e.status}</td>
                  <td className="small">{e.expiresAt ? formatTime(e.expiresAt) : "Never"}</td>
                  <td className="small">{lastSeen(e)}</td>
                  <td className="small">
                    <Who name={e.createdBy} />
                  </td>
                </tr>
              ))}
            </tbody>
          </DataTable>
        )}
      </Card>
      <Card title="People who sign in with your identity provider" titleId="review-people-h" className="km-section">
        {review && people.length === 0 && (
          <EmptyState title="No one yet" compact>
            No one has signed in with an identity provider and been given a role, and the control script has made no
            one.
          </EmptyState>
        )}
        {people.length > 0 && (
          <DataTable label="People who sign in with your identity provider">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">State</th>
                <th scope="col">Role</th>
                <th scope="col">Groups</th>
                <th scope="col">Sensitive values</th>
                <th scope="col">Last signed in</th>
              </tr>
            </thead>
            <tbody>
              {people.map((e, i) => (
                <tr key={`p${i}`} data-state={e.state ?? undefined}>
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
                  <td>
                    <StatusChip tone={stateTone(e)} kind="state">
                      {e.status}
                    </StatusChip>
                  </td>
                  <td>
                    {e.role ?? "No role"}
                    {notSignedIn(e) && e.role && <span className="small muted"> (once signed in)</span>}
                  </td>
                  <td className="small">{groupWords(e)}</td>
                  <td>
                    {sensitiveWords(e)}
                    {seesUnmasked(e) && (
                      <>
                        {" "}
                        <Unmasked />
                      </>
                    )}
                  </td>
                  <td className="small">{lastSeen(e)}</td>
                </tr>
              ))}
            </tbody>
          </DataTable>
        )}
        <p className="hint">
          Their role comes from their groups at your identity provider each time they sign in: to take it away, take
          them out of the group there. For someone who has not signed in yet, the role and grant shown are what their
          groups give them when they do.
        </p>
        <p className="hint" data-note="provider">
          {ONLY_AT_THE_PROVIDER}
        </p>
        {people.some((e) => e.example) && (
          <p className="warning" role="note">
            {EXAMPLE_ACCOUNTS}
          </p>
        )}
      </Card>
    </>
  );
}
