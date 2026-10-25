import { useEffect, useState } from "react";
import { PageHeader } from "../components/ui";
import { aiApi, formatNumber, formatTime, type AiUsage, type AiUsageRecent } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";

const OUTCOME: Record<AiUsageRecent["outcome"], string> = {
  ok: "Answered",
  refused: "Refused",
  failed: "Failed",
  timeout: "Timed out",
  busy: "Busy",
  cancelled: "Cancelled",
};

/** AI usage (admins): calls, tokens and speed per day and model, and the latest calls with their outcome. */
export function AiUsagePage() {
  useTitle("AI usage");
  const [days, setDays] = useState(30);
  const [usage, setUsage] = useState<AiUsage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    aiApi
      .usage(days)
      .then((u) => live && (setUsage(u), setError(null)))
      .catch((e: Error) => live && setError(e.message));
    return () => {
      live = false;
    };
  }, [days]);

  return (
    <>
      <PageHeader
        crumbs={[{ label: "Settings", href: href.settings() }, { label: "AI usage" }]}
        title="AI usage"
        lead={
          <>
            Every model call, including refused ones: which feature asked, which model answered, tokens and time. Prompts
        and answers are not kept. <a href={href.ai()}>Back to AI models</a>.
          </>
        }
      />
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <div className="field ai-days">
        <label htmlFor="usage-days">Period</label>
        <select id="usage-days" value={days} onChange={(e) => setDays(Number(e.target.value))}>
          <option value={7}>Last 7 days</option>
          <option value={30}>Last 30 days</option>
          <option value={90}>Last 90 days</option>
        </select>
      </div>

      <section aria-labelledby="totals-h" className="panel">
        <h2 id="totals-h">By day and model</h2>
        {usage === null && !error && <p>Loading…</p>}
        {usage?.totals.length === 0 && <p>No model calls in this period.</p>}
        {usage && usage.totals.length > 0 && (
          <div className="table-scroll" role="region" aria-label="Usage by day and model (scrolls sideways)" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Day</th>
                  <th scope="col">Model profile</th>
                  <th scope="col">Calls</th>
                  <th scope="col">Answered</th>
                  <th scope="col">Refused</th>
                  <th scope="col">Failed</th>
                  <th scope="col">Tokens in</th>
                  <th scope="col">Tokens out</th>
                  <th scope="col">Average time</th>
                </tr>
              </thead>
              <tbody>
                {usage.totals.map((t) => (
                  <tr key={`${t.day}-${t.profile}`}>
                    <th scope="row">{t.day}</th>
                    <td>{t.profile ?? "None"}</td>
                    <td>{formatNumber(t.calls)}</td>
                    <td>{formatNumber(t.ok)}</td>
                    <td>{formatNumber(t.refused)}</td>
                    <td>{formatNumber(t.failed)}</td>
                    <td>{formatNumber(t.tokensIn)}</td>
                    <td>{formatNumber(t.tokensOut)}</td>
                    <td>{t.ok ? `${formatNumber(t.averageLatencyMs)} ms` : "–"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section aria-labelledby="recent-h" className="panel">
        <h2 id="recent-h">Latest calls</h2>
        {usage && usage.recent.length === 0 && <p>No calls yet.</p>}
        {usage && usage.recent.length > 0 && (
          <div className="table-scroll" role="region" aria-label="Latest calls (scrolls sideways)" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">When</th>
                  <th scope="col">Feature</th>
                  <th scope="col">Model</th>
                  <th scope="col">Source</th>
                  <th scope="col">Outcome</th>
                  <th scope="col">Tokens</th>
                  <th scope="col">Time</th>
                  <th scope="col">Caller</th>
                </tr>
              </thead>
              <tbody>
                {usage.recent.map((r) => (
                  <tr key={r.id}>
                    <th scope="row" className="small">
                      {formatTime(r.at)}
                    </th>
                    <td>{r.feature}</td>
                    <td className="small">
                      {r.profile ?? "–"}
                      {r.model && <div className="muted mono">{r.model}</div>}
                    </td>
                    <td>{r.source ?? "–"}</td>
                    <td>
                      <span className={`badge ${r.outcome === "ok" ? "badge-succeeded" : r.outcome === "refused" ? "badge-running" : "badge-failed"}`}>
                        {OUTCOME[r.outcome]}
                      </span>
                      {r.detail && <div className="small muted">{r.detail}</div>}
                    </td>
                    <td className="small">
                      {r.tokensIn == null && r.tokensOut == null
                        ? "–"
                        : `${formatNumber(r.tokensIn ?? 0)} in, ${formatNumber(r.tokensOut ?? 0)} out`}
                    </td>
                    <td className="small">{formatNumber(r.latencyMs)} ms</td>
                    <td className="small">{r.caller}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
