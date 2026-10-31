import { useCallback, useEffect, useRef, useState } from "react";
import {
  allows,
  ApiError,
  formatNumber,
  formatShare,
  formatTime,
  qualityApi,
  type AccessRole,
  type QualityRule,
  type RuleView,
} from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { QualityStatusBadge } from "../components/QualityPanel";
import { CHECKS, RuleForm } from "../components/RuleForm";
import { Icon } from "../components/icons";
import { Button, Card, DataTable, EmptyState, PageHeader, StatusChip, Switch } from "../components/ui";

/** "lab-db · crm.customers" from an asset URN, for people to read. */
const assetName = (urn: string) => {
  const m = /^urn:kilnmark:([^:]+):(.*)$/.exec(urn);
  return m ? { source: m[1], path: m[2] } : { source: "", path: urn };
};

/** Rules: every rule with its latest result; stewards add, preview, edit, run, switch off and delete them. */
export function RulesPage({ role, urn }: { role: AccessRole; urn: string }) {
  useTitle("Quality rules");
  const steward = allows(role, "steward");
  const [filter, setFilter] = useState(urn);
  const [rules, setRules] = useState<RuleView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [editing, setEditing] = useState<QualityRule | null>(null);
  const admin = allows(role, "admin");
  const designer = allows(role, "designer");

  // keepError: refresh the list but leave the message about what just went wrong showing.
  const load = useCallback((keepError?: boolean) => {
    qualityApi
      // Designers edit with the real values of sensitive columns; others get them held back ({"hidden": true}).
      .rules(filter.trim() ? { urn: filter.trim() } : {}, admin)
      .then((r) => {
        setRules(r);
        if (keepError !== true) setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [filter, admin]);

  useEffect(() => setFilter(urn), [urn]);
  useEffect(() => load(), [load]);

  // Editing moves focus to the form once it shows the rule; leaving it returns focus to the rule's Edit button.
  const focusForm = useRef(false);
  useEffect(() => {
    if (!focusForm.current) return;
    focusForm.current = false;
    const h = document.getElementById("rule-form-h");
    h?.scrollIntoView({ block: "start" });
    h?.focus();
  }, [editing]);
  const startEdit = (rule: QualityRule) => {
    focusForm.current = true;
    setEditing(rule);
    setNotice("");
  };
  const stopEdit = (id: number) => {
    setEditing(null);
    window.setTimeout(() => document.getElementById(`edit-rule-${id}`)?.focus(), 0);
  };

  const act = async (what: () => Promise<unknown>, done: string) => {
    try {
      await what();
      setNotice(done);
      load();
    } catch (e) {
      setError((e as Error).message);
      // Someone else changed the rule: show it as it is now.
      if (e instanceof ApiError && e.status === 409) load(true);
    }
  };

  return (
    <>
      <PageHeader
        title="Quality rules"
        meta={rules ? `${rules.length} ${rules.length === 1 ? "rule" : "rules"}${filter.trim() ? " on this asset" : ""}` : undefined}
        lead="Checks on assets, each tagged with a dimension. They run after every scan and on demand: as SQL in databases, and while streaming files."
        actions={
          steward && !editing ? (
            <Button
              variant="primary"
              icon={<Icon name="plus" size={16} />}
              onClick={() => {
                const h = document.getElementById("rule-form-h");
                h?.scrollIntoView({ block: "start" });
                h?.focus();
              }}
            >
              Add a rule
            </Button>
          ) : undefined
        }
      />
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      <Card title="Rules" titleId="rules-h" className="panel">
        <div className="field inline-field">
          <label htmlFor="rules-filter">Asset URN</label>
          <input id="rules-filter" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="All assets"
            aria-describedby="rules-filter-hint" autoComplete="off" />
          <p id="rules-filter-hint" className="hint">Leave empty to list the rules of every asset.</p>
        </div>
        {rules === null && !error && <p role="status" className="muted">Loading…</p>}
        {rules?.length === 0 && (
          <EmptyState compact icon={<Icon name="rules" size={22} />} title="No rules yet">
            {steward ? "Add one below, or tick recommended rules on an asset's Quality tab." : "Stewards add rules."}
          </EmptyState>
        )}
        {rules && rules.length > 0 && (
          <DataTable label="Rules table (scrolls sideways on small screens)">
              <thead>
                <tr>
                  <th scope="col">Rule</th>
                  <th scope="col">Asset</th>
                  <th scope="col">Check</th>
                  <th scope="col">Status</th>
                  <th scope="col">Failing rows</th>
                  {steward && <th scope="col">On</th>}
                  {steward && (
                    <th scope="col">
                      <span className="visually-hidden">Actions</span>
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {rules.map(({ rule, latest }) => (
                  <tr key={rule.id}>
                    <th scope="row">
                      {rule.name}
                      {!rule.enabled && <span className="tag tag-key">off</span>}
                      <div className="small muted">{rule.dimension}</div>
                    </th>
                    <td className="small">
                      <a href={href.asset(rule.assetUrn, "quality")} className="km-strong-link" title={rule.assetUrn}>
                        {assetName(rule.assetUrn).path}
                      </a>
                      <div className="muted">{assetName(rule.assetUrn).source}</div>
                      {rule.field && <span className="km-code">{rule.field}</span>}
                    </td>
                    <td className="small">
                      {CHECKS.find((t) => t.type === rule.type)?.label ?? rule.type}
                      <div className="muted">
                        fail above {rule.thresholdUnit === "share" ? formatShare(rule.failAbove) : formatNumber(rule.failAbove)}
                        {rule.warnAbove != null &&
                          `, warn above ${rule.thresholdUnit === "share" ? formatShare(rule.warnAbove) : formatNumber(rule.warnAbove)}`}
                      </div>
                    </td>
                    <td>
                      {latest ? <QualityStatusBadge status={latest.status} /> : <StatusChip>Not run</StatusChip>}
                      {latest && <div className="small muted">{formatTime(latest.observedAt)}</div>}
                      {latest?.estimate && <span className="tag">estimate</span>}
                      {latest?.errorDetail && <div className="small muted">{latest.errorDetail}</div>}
                    </td>
                    <td className="small">
                      {latest?.failing != null
                        ? `${formatNumber(latest.failing)} of ${formatNumber(latest.total)}`
                        : latest?.message ?? "–"}
                      {latest?.failingQuery && (
                        <details>
                          <summary>Query for the failing rows</summary>
                          <code className="mono query">{latest.failingQuery}</code>
                        </details>
                      )}
                    </td>
                    {steward && (
                      <td>
                        {/* Switching waits while the rule is open in the form, so a save cannot undo it. */}
                        <Switch
                          compact
                          label={`Rule ${rule.name} is on`}
                          checked={rule.enabled}
                          disabled={editing?.id === rule.id}
                          onChange={(on) =>
                            act(
                              () =>
                                qualityApi.updateRule(
                                  rule.id,
                                  { ...rule, enabled: on, actions: { webhook: rule.actions.webhook, task: rule.actions.task } },
                                  rule.updatedAt,
                                ),
                              `Rule "${rule.name}" switched ${on ? "on" : "off"}.`,
                            )
                          }
                        />
                      </td>
                    )}
                    {steward && (
                      <td>
                        <div className="buttons tight">
                          {(designer || rule.type !== "custom_sql") && (
                            <button type="button" id={`edit-rule-${rule.id}`} onClick={() => startEdit(rule)}
                              aria-label={`Edit rule ${rule.name}`}>
                              Edit
                            </button>
                          )}
                          <button type="button" onClick={() => act(() => qualityApi.runRule(rule.id),
                            `Rule "${rule.name}" queued to run.`)} aria-label={`Run rule ${rule.name} now`}>
                            Run now
                          </button>
                          <button type="button" className="danger" onClick={() => {
                            if (window.confirm(`Delete the rule "${rule.name}" and its results?`)) {
                              act(() => qualityApi.deleteRule(rule.id), `Rule "${rule.name}" deleted.`);
                            }
                          }} disabled={editing?.id === rule.id} aria-label={`Delete rule ${rule.name}`}>
                            Delete
                          </button>
                        </div>
                        {editing?.id === rule.id && <p className="hint">Open in the form below.</p>}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
          </DataTable>
        )}
      </Card>

      {steward ? (
        editing ? (
          <RuleForm
            key={`edit-${editing.id}-${editing.updatedAt}`}
            customSql={designer}
            urn={editing.assetUrn}
            rule={editing}
            onReload={() =>
              qualityApi
                .rule(editing.id, admin)
                .then((v) => {
                  focusForm.current = true;
                  setEditing(v.rule);
                  load();
                })
                .catch((e: Error) => setError(e.message))
            }
            onSaved={(name) => {
              setNotice(`Rule "${name}" saved. Its next run uses the new settings, or press Run now.`);
              load();
              stopEdit(editing.id);
            }}
            onCancel={() => stopEdit(editing.id)}
          />
        ) : (
          <RuleForm
            key="add"
            customSql={designer}
            urn={urn}
            onSaved={(name) => {
              setNotice(`Rule "${name}" added. It runs after the next scan, or press Run now.`);
              load();
            }}
          />
        )
      ) : (
        <p className="hint">Your role ({role}) can read rules. Ask an admin for a steward token to add or change them.</p>
      )}
    </>
  );
}
