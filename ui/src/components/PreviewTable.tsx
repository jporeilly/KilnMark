import { useId } from "react";
import { formatNumber, type Preview } from "../api";

/**
 * The first rows of a table or file: a real table with column headers and kinds, a caption naming
 * the asset and how many rows, its own sideways scroll, and nulls written as "null" in a style of
 * their own (text, not colour alone).
 */
export function PreviewTable({ preview, hint = "Read live from the source and not kept." }: { preview: Preview; hint?: string }) {
  const id = useId();
  return (
    <div className="preview">
      {Object.keys(preview.details).length > 0 && (
        <dl className="facts small">
          {Object.entries(preview.details).map(([k, v]) => (
            <div key={k}>
              <dt>{DETAIL[k] ?? k}</dt>
              <dd className="mono">{k === "delimiter" && v === "\\t" ? "tab" : v}</dd>
            </div>
          ))}
        </dl>
      )}
      <div className="table-scroll" role="region" aria-labelledby={`${id}-cap`} tabIndex={0}>
        <table className="preview-table">
          <caption id={`${id}-cap`}>
            <span className="mono">{preview.asset}</span>: first {formatNumber(preview.rowCount)}{" "}
            {preview.rowCount === 1 ? "row" : "rows"}
            {preview.truncated && " (stopped early: the preview reached its size limit)"}
          </caption>
          <thead>
            <tr>
              {preview.columns.map((c) => (
                <th scope="col" key={c.name}>
                  {c.name}
                  {c.kind && <span className="col-kind"> {c.kind}</span>}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {preview.rows.map((r, i) => (
              <tr key={i}>
                {r.map((v, j) => (
                  <td key={j} className="mono">
                    {v === null ? <span className="null-value">null</span> : v}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="hint">{hint}</p>
    </div>
  );
}

const DETAIL: Record<string, string> = {
  format: "Format",
  delimiter: "Delimiter",
  quote: "Quote",
  header: "Header row",
  encoding: "Encoding",
  "sep-line": "Excel sep= line",
  records: "Records",
};
