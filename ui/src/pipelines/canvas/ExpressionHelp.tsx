import { useState } from "react";
import { docHref } from "../../docs/links";
import help from "./expression-help.json";

/**
 * What an operator chip puts into a condition at the caret, and how it is shown. Taken from the
 * expressions guide (docs/guide/expressions.md, Operators) and the parser: `!=` is read as `<>`,
 * `in`, `between` and `like` take `not` in front, and comparisons do not chain.
 */
export const OPERATOR_GROUPS: { label: string; ops: { text: string; insert: string; title: string }[] }[] = [
  {
    label: "Compare",
    ops: [
      { text: "=", insert: "= ", title: "Equal" },
      { text: "<>", insert: "<> ", title: "Not equal (!= works too)" },
      { text: "<", insert: "< ", title: "Less than" },
      { text: "<=", insert: "<= ", title: "Less than or equal" },
      { text: ">", insert: "> ", title: "Greater than" },
      { text: ">=", insert: ">= ", title: "Greater than or equal" },
    ],
  },
  {
    label: "Test",
    ops: [
      { text: "in (…)", insert: "in (", title: "One of a list: status in ('new', 'open')" },
      { text: "between … and …", insert: "between ", title: "A range, both ends included: amount between 10 and 99" },
      { text: "like", insert: "like ", title: "A text pattern: % is any run of characters, _ one character" },
      { text: "is null", insert: "is null ", title: "Empty" },
      { text: "is not null", insert: "is not null ", title: "Not empty" },
    ],
  },
  {
    label: "Combine",
    ops: [
      { text: "and", insert: "and ", title: "Both are true" },
      { text: "or", insert: "or ", title: "Either is true" },
      { text: "not", insert: "not ", title: "The opposite" },
      { text: "(", insert: "(", title: "Open a bracket, to group a test" },
      { text: ")", insert: ") ", title: "Close the bracket" },
    ],
  },
  {
    label: "Arithmetic",
    ops: [
      { text: "+", insert: "+ ", title: "Add" },
      { text: "-", insert: "- ", title: "Subtract" },
      { text: "*", insert: "* ", title: "Multiply" },
      { text: "/", insert: "/ ", title: "Divide (7 / 2 is 3.5)" },
      { text: "%", insert: "% ", title: "Remainder" },
      { text: "||", insert: "|| ", title: "Join text: first_name || ' ' || last_name" },
    ],
  },
];

/** The worked examples, each checked by the server's ExpressionHelpTest. */
export const EXAMPLES: { text: string; says: string }[] = help.examples;

/** Buttons that put an operator into a condition at the caret, in groups, as the column chips do. */
export function OperatorButtons({ onPick, label }: { onPick: (text: string) => void; label: string }) {
  return (
    <div className="cv-ops" role="group" aria-label={label}>
      {OPERATOR_GROUPS.map((g) => (
        <div key={g.label} className="cv-ops-group" role="group" aria-label={g.label}>
          <span className="cv-ops-label small muted" aria-hidden="true">
            {g.label}
          </span>
          {g.ops.map((o) => (
            <button
              key={o.text}
              type="button"
              className="cv-chip cv-chip-op mono"
              title={o.title}
              aria-label={`${o.text}: ${o.title}`}
              onClick={() => onPick(o.insert)}
            >
              {o.text}
            </button>
          ))}
        </div>
      ))}
    </div>
  );
}

/**
 * A short reference beside a condition or a computed column: the operators, how to nest, and worked
 * examples, with a link to the expressions guide. It starts open when the box is empty and folded when
 * it holds something; after that it stays as the reader leaves it, so typing never folds it away.
 */
export function ExpressionReference({ id, startOpen, condition }: { id: string; startOpen: boolean; condition: boolean }) {
  const [open] = useState(startOpen);
  return (
    <details className="cv-expr-help" id={id} open={open || undefined}>
      <summary>How to write it: operators, nesting and examples</summary>
      <ul className="small cv-expr-rules">
        <li>
          Compare with <code>=</code> <code>&lt;&gt;</code> <code>&lt;</code> <code>&lt;=</code> <code>&gt;</code> <code>&gt;=</code>: numbers,
          text in single quotes (<code>'active'</code>) and dates (<code>date '2024-01-31'</code>).
        </li>
        <li>
          Test with <code>x in (a, b)</code>, <code>x between a and b</code> (both ends included), <code>x like 'A%'</code> (
          <code>%</code> any run of characters, <code>_</code> one; case matters), <code>x is null</code> and <code>x is not null</code>.{" "}
          <code>not in</code>, <code>not between</code> and <code>not like</code> work too.
        </li>
        <li>
          Combine with <code>and</code>, <code>or</code> and <code>not</code>. <code>and</code> binds tighter than <code>or</code>, so{" "}
          <code>a or b and c</code> means <code>a or (b and c)</code>: put brackets round a test to nest it.
        </li>
        <li>
          Work out numbers with <code>+</code> <code>-</code> <code>*</code> <code>/</code> and <code>%</code> (remainder); <code>7 / 2</code> is
          3.5. Join text with <code>||</code>. Comparisons do not chain: write <code>a = b and b = c</code>.
        </li>
        {condition && <li>An empty (null) result counts as not true, so the row is not kept or sent that way.</li>}
      </ul>
      <p className="small cv-expr-examples-title">Examples</p>
      <ul className="cv-expr-examples">
        {EXAMPLES.map((e) => (
          <li key={e.text}>
            <code className="mono">{e.text}</code>
            <span className="small muted"> {e.says}</span>
          </li>
        ))}
      </ul>
      <p className="small">
        <a href={docHref("expressions", "operators")}>Expressions guide: operators</a> ·{" "}
        <a href={docHref("expressions", "functions")}>functions</a>
      </p>
    </details>
  );
}
