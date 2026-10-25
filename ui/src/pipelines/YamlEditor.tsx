import { forwardRef, useImperativeHandle, useMemo, useRef } from "react";

export type YamlEditorHandle = {
  /** Puts the caret at the start of a line (from 1) and focuses the editor. */
  goToLine: (line: number, column?: number) => void;
};

/**
 * A plain text editor for pipeline YAML: a monospace textarea with line numbers beside it (the
 * numbers are decoration, hidden from screen readers; the problems list gives each line in
 * words). Lines with a problem are marked in the gutter with "!" as well as colour. Tab inserts two
 * spaces; Escape then Tab leaves the editor, as in other code editors.
 */
export const YamlEditor = forwardRef<
  YamlEditorHandle,
  {
    id: string;
    value: string;
    onChange: (v: string) => void;
    readOnly?: boolean;
    problemLines: Set<number>;
    describedBy?: string;
    label: string;
  }
>(function YamlEditor({ id, value, onChange, readOnly, problemLines, describedBy, label }, ref) {
  const area = useRef<HTMLTextAreaElement>(null);
  const gutter = useRef<HTMLDivElement>(null);
  const escaped = useRef(false);
  const lines = useMemo(() => Math.max(1, value.split("\n").length), [value]);

  useImperativeHandle(ref, () => ({
    goToLine(line: number, column = 1) {
      const t = area.current;
      if (!t) return;
      const parts = t.value.split("\n");
      let at = 0;
      for (let i = 0; i < Math.min(line - 1, parts.length); i++) at += parts[i].length + 1;
      at += Math.max(0, Math.min(column - 1, (parts[line - 1] ?? "").length));
      t.focus();
      t.setSelectionRange(at, at);
      // Scroll the line into view: about the line's height times its number.
      const lineHeight = parseFloat(getComputedStyle(t).lineHeight) || 20;
      t.scrollTop = Math.max(0, (line - 3) * lineHeight);
    },
  }));

  return (
    <div className="pl-editor">
      <div className="pl-gutter" ref={gutter} aria-hidden="true">
        {Array.from({ length: lines }, (_, i) => (
          <div key={i} className={problemLines.has(i + 1) ? "pl-gutter-line has-problem" : "pl-gutter-line"}>
            {problemLines.has(i + 1) ? "! " : ""}
            {i + 1}
          </div>
        ))}
      </div>
      <textarea
        id={id}
        ref={area}
        className="pl-text mono"
        value={value}
        readOnly={readOnly}
        spellCheck={false}
        autoCapitalize="off"
        autoComplete="off"
        wrap="off"
        aria-label={label}
        aria-describedby={describedBy}
        aria-invalid={problemLines.size > 0 ? true : undefined}
        onScroll={(e) => {
          if (gutter.current) gutter.current.scrollTop = e.currentTarget.scrollTop;
        }}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Escape") {
            escaped.current = true;
            return;
          }
          if (e.key === "Tab" && !e.shiftKey && !escaped.current && !readOnly) {
            e.preventDefault();
            const t = e.currentTarget;
            const { selectionStart: s, selectionEnd: end } = t;
            const next = t.value.slice(0, s) + "  " + t.value.slice(end);
            onChange(next);
            requestAnimationFrame(() => t.setSelectionRange(s + 2, s + 2));
            return;
          }
          escaped.current = false;
        }}
      />
    </div>
  );
});
