import type { FlowParameter } from "../api";
import { defaultWords } from "./parameters";

/** A description as a sentence, so the default's words after it read apart: "The statuses to keep." */
const sentence = (text: string | null) => (text && !/[.!?:]$/.test(text.trim()) ? `${text.trim()}.` : text);

/**
 * A field for each parameter, as the Run now dialog and the schedule form show them: a choice of
 * true or false for a boolean, a text field for the rest (lists separated by commas), each labelled
 * with its name and kind, and a hint with its description and default, or its problem.
 */
export function ParameterFields({
  idBase,
  params,
  typed,
  problems,
  onChange,
}: {
  idBase: string;
  params: FlowParameter[];
  typed: Record<string, string>;
  problems: Record<string, string>;
  onChange: (name: string, value: string) => void;
}) {
  const fieldId = (p: string) => `${idBase}-${p}`;
  return (
    <>
      {params.map((p) => (
        <div className="field" key={p.name}>
          <label htmlFor={fieldId(p.name)}>
            <span className="mono">{p.name}</span> ({p.type}
            {p.required ? ", needed" : ""})
          </label>
          {p.type === "boolean" ? (
            <select
              id={fieldId(p.name)}
              value={typed[p.name] ?? ""}
              onChange={(e) => onChange(p.name, e.target.value)}
              aria-invalid={problems[p.name] ? true : undefined}
              aria-describedby={`${fieldId(p.name)}-hint`}
            >
              {(p.required || p.defaultVariable) && (
                <option value="">{p.defaultVariable ? `The server variable ${p.defaultVariable}` : "Choose…"}</option>
              )}
              <option value="true">true</option>
              <option value="false">false</option>
            </select>
          ) : (
            <input
              id={fieldId(p.name)}
              value={typed[p.name] ?? ""}
              inputMode={p.type === "number" ? "decimal" : undefined}
              autoComplete="off"
              spellCheck={false}
              onChange={(e) => onChange(p.name, e.target.value)}
              aria-invalid={problems[p.name] ? true : undefined}
              aria-describedby={`${fieldId(p.name)}-hint`}
            />
          )}
          <p id={`${fieldId(p.name)}-hint`} className={problems[p.name] ? "hint pl-bad" : "hint"}>
            {problems[p.name] ?? [sentence(p.description), defaultWords(p)].filter(Boolean).join(" ")}
          </p>
        </div>
      ))}
    </>
  );
}
