import { useEffect, useRef, useState } from "react";
import { IconPlus, IconRefresh, IconSparkles } from "@tabler/icons-react";
import { Button, StatusChip, Switch } from "../../components/ui";
import { changedDimensions, effectWords, predictionApi, type Advice, type Suggestion } from "../prediction";

/**
 * Suggestions for raising the predicted quality, a tab of the panel under the canvas (loaded when first shown).
 * Kilnmark's own come from the catalogue's metadata with no model; when an AI model is set for
 * pipeline suggestions it ranks, explains and adds to them. Each shows its predicted effect as an
 * estimate; Add inserts its nodes through the canvas, as one change to undo.
 */
export default function SuggestionsPanel({
  yaml,
  readOnly,
  onAdd,
  focusAsk = 0,
  onFocused,
}: {
  yaml: string;
  readOnly: boolean;
  /** Adds the suggestion's nodes; returns why not, or null. */
  onAdd: (s: Suggestion) => string | null;
  /** Above 0: move the focus to the heading (Suggestions on the toolbar asked for them), then say so. */
  focusAsk?: number;
  onFocused?: () => void;
}) {
  const [advice, setAdvice] = useState<Advice | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [useAi, setUseAi] = useState(true);
  const [ask, setAsk] = useState(0);
  const [added, setAdded] = useState<string | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);

  // Only when asked: choosing the tab itself keeps the focus on the tab.
  useEffect(() => {
    if (focusAsk <= 0) return;
    heading.current?.focus();
    onFocused?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusAsk]);

  // The model is asked when the panel opens, on Ask again, and when it is switched on; after an edit
  // (or an Add) only Kilnmark's own suggestions are worked out again, a moment later.
  const asked = useRef<{ ask: number; useAi: boolean } | null>(null);
  useEffect(() => {
    const ctl = new AbortController();
    const fresh = !asked.current || asked.current.ask !== ask || asked.current.useAi !== useAi;
    asked.current = { ask, useAi };
    const withModel = useAi && fresh;
    const t = window.setTimeout(() => {
      setBusy(true);
      predictionApi
        .suggest(yaml, withModel, ctl.signal)
        .then((a) => {
          setAdvice(a);
          setError(null);
        })
        .catch((e: Error) => {
          if (ctl.signal.aborted) return;
          setError(/403|steward/i.test(e.message) ? "Suggestions are for stewards and admins." : e.message);
        })
        .finally(() => !ctl.signal.aborted && setBusy(false));
    }, fresh ? 0 : 900);
    return () => {
      ctl.abort();
      window.clearTimeout(t);
    };
  }, [yaml, useAi, ask]);

  return (
    <section className="cv-suggest" aria-labelledby="cv-suggest-h" aria-busy={busy}>
      <h3 id="cv-suggest-h" className="cv-side-title" tabIndex={-1} ref={heading}>
        <IconSparkles size={18} stroke={1.8} aria-hidden="true" /> Suggestions
      </h3>
      <p className="small muted">
        Nodes that would raise the predicted quality of a write. Every score here is an estimate from the catalogue's
        metadata; no data is read.
      </p>
      <Switch
        label="Ask the AI model too"
        help="It sees column names, types, shares and value shapes of sources whose AI rule allows it, never values."
        checked={useAi}
        onChange={setUseAi}
        compact={false}
      />
      <div className="cv-suggest-tools">
        <Button size="sm" icon={<IconRefresh size={16} stroke={1.8} aria-hidden="true" />} onClick={() => setAsk((n) => n + 1)} disabled={busy}>
          Ask again
        </Button>
        <span className="small muted" role="status">
          {busy ? "Working out suggestions…" : ""}
        </span>
      </div>
      {error && (
        <p className="error small" role="alert">
          {error}
        </p>
      )}
      {advice && (
        <p className={`small cv-suggest-ai cv-suggest-ai-${advice.ai.state}`}>
          <StatusChip tone={advice.ai.state === "used" ? "violet" : advice.ai.state === "unavailable" ? "warn" : "neutral"}>
            {advice.ai.state === "used" ? "AI ranked" : advice.ai.state === "unavailable" ? "AI unavailable" : "Kilnmark's own"}
          </StatusChip>{" "}
          {advice.ai.message}
          {advice.ai.state === "not_asked" && useAi ? " Ask again to have the model rank them." : ""}
        </p>
      )}
      {advice && advice.suggestions.length === 0 && !busy && (
        <p className="small">No suggestions: nothing the metadata shows would raise a write's predicted score.</p>
      )}
      {added && (
        <p className="small notice" role="status">
          Added: {added}. Undo takes it out again.
        </p>
      )}
      <ul className="cv-suggest-list">
        {advice?.suggestions.map((s) => {
          const dims = changedDimensions(s.effect);
          return (
            <li key={s.id} className="cv-suggest-item">
              <div className="cv-suggest-head">
                <strong>{s.title}</strong>
                <StatusChip tone={s.source === "ai" ? "violet" : "neutral"}>{s.source === "ai" ? "AI" : "Kilnmark"}</StatusChip>
              </div>
              <p className="small">{s.why}</p>
              <p className="small cv-suggest-effect">
                {effectWords(s.effect)}
                {dims.length > 0 ? `: ${dims.join(", ")}` : ""}
              </p>
              {s.effect?.note && <p className="small cv-suggest-rows">{s.effect.note} (estimate).</p>}
              <p className="small muted">
                Adds {s.nodes.map((n) => `${n.id} (${n.type})`).join(" and ")} before {s.placement.before}.
              </p>
              {!readOnly && (
                <Button
                  size="sm"
                  variant="subtle"
                  icon={<IconPlus size={16} stroke={1.8} aria-hidden="true" />}
                  onClick={() => {
                    const why = onAdd(s);
                    if (why) setError(why);
                    else {
                      setError(null);
                      setAdded(s.title);
                    }
                  }}
                  aria-label={`Add: ${s.title}`}
                >
                  Add
                </Button>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
