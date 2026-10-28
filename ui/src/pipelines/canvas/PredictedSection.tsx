import { formatNumber } from "../../api";
import { dimensionWord, percent, RULE_STATUS_WORDS } from "../quality";
import { ESTIMATE, predictedWords, rowsWords, type PredictedQuality } from "../prediction";

const DECISION: Record<string, string> = {
  passed: "pass every row",
  warned: "warn and let every row through",
  quarantined: "set rows aside",
  stopped: "stop the run",
};

/**
 * A write's or gate's predicted quality in the settings drawer: the estimate, per dimension, what
 * a gate is expected to do with each rule, and what could not be told or was assumed. Labelled as
 * an estimate throughout.
 */
export function PredictedSection({ p, id }: { p: PredictedQuality; id: string }) {
  const g = p.gate;
  return (
    <section className="cv-section cv-predicted" aria-labelledby={id}>
      <h4 id={id}>Predicted quality {ESTIMATE}</h4>
      <p className="small">
        {predictedWords(p)}, from the catalogue's metadata about what this step reads; no data was read. {rowsWords(p.rows)}.
      </p>
      {Object.keys(p.dimensions).length > 0 && (
        <ul className="small cv-quality-dims" aria-label="Predicted score per dimension">
          {Object.entries(p.dimensions).map(([d, v]) => (
            <li key={d}>
              {dimensionWord(d)}: {percent(v)}
            </li>
          ))}
        </ul>
      )}
      {g && (
        <>
          <p className="small">
            Expected to {g.decision ? DECISION[g.decision] ?? g.decision : "do something the metadata cannot tell"}
            {g.rowsPassed != null && g.rowsQuarantined != null && g.rowsQuarantined > 0
              ? `: about ${formatNumber(g.rowsPassed)} passing, ${formatNumber(g.rowsQuarantined)} set aside`
              : ""}
            .
          </p>
          {g.rules.length > 0 && (
            <ul className="small cv-quality-rules" aria-label="The rules the gate is expected to check">
              {g.rules.map((r, i) => (
                <li key={`${i}-${r.origin}`}>
                  {r.name}:{" "}
                  {r.failingShare == null
                    ? "unknown"
                    : `${RULE_STATUS_WORDS[r.status] ?? r.status}, about ${percent(r.failingShare)} of rows failing (from ${r.basis})`}
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      {(p.unknown.length > 0 || p.assumptions.length > 0) && (
        <details className="small cv-predicted-more">
          <summary>What the estimate could not tell, and assumed</summary>
          {p.unknown.length > 0 && (
            <>
              <p className="muted">Not known:</p>
              <ul>
                {p.unknown.map((u, i) => (
                  <li key={i}>{u}</li>
                ))}
              </ul>
            </>
          )}
          {p.assumptions.length > 0 && (
            <>
              <p className="muted">Assumed:</p>
              <ul>
                {p.assumptions.map((u, i) => (
                  <li key={i}>{u}</li>
                ))}
              </ul>
            </>
          )}
        </details>
      )}
    </section>
  );
}
