import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from "react";
import { Icon } from "../components/icons";
import type { Route } from "../router";
import type { AssistantAnswer, AssistantCitation } from "./api";
import { pageHint, suggestions } from "./context";
import type { AssistantState, Exchange } from "./useAssistant";
import "./assistant.css";
import { plural } from "../api";

const MAX = 2000;

/** Answer text with each [n] turned into a link to its source; paragraphs kept. Plain text only, never HTML. */
function AnswerText({ answer }: { answer: AssistantAnswer }) {
  const byN = new Map<number, AssistantCitation>(answer.citations.map((c) => [c.n, c]));
  return (
    <>
      {answer.answer.split(/\n{2,}/).map((para, i) => {
        const parts: ReactNode[] = [];
        let last = 0;
        for (const m of para.matchAll(/\[(\d{1,3})\]/g)) {
          const c = byN.get(Number(m[1]));
          if (!c) continue;
          parts.push(para.slice(last, m.index));
          parts.push(
            <a key={`${i}-${m.index}`} href={c.href} className="assistant-cite" aria-label={`Source ${c.n}: ${c.title}`}>
              [{c.n}]
            </a>,
          );
          last = (m.index ?? 0) + m[0].length;
        }
        parts.push(para.slice(last));
        return <p key={i}>{parts}</p>;
      })}
    </>
  );
}

function Rating({ e, onRate }: { e: Exchange; onRate: (helpful: boolean) => void }) {
  if (e.rating != null) {
    return <p className="small muted">Thanks: your rating was recorded.</p>;
  }
  return (
    <div className="assistant-rate">
      <span className="small muted" id={`rate-${e.id}`}>
        Did this help?
      </span>
      <button type="button" className="link-button" aria-describedby={`rate-${e.id}`} onClick={() => onRate(true)}>
        Yes, it helped
      </button>
      <button type="button" className="link-button" aria-describedby={`rate-${e.id}`} onClick={() => onRate(false)}>
        No
      </button>
      {e.ratingError && (
        <span className="error small" role="alert">
          {e.ratingError}
        </span>
      )}
    </div>
  );
}

function ExchangeView({ e, state }: { e: Exchange; state: AssistantState }) {
  return (
    <li className="assistant-exchange">
      <p className="assistant-question">
        <span className="visually-hidden">You asked: </span>
        {e.question}
      </p>
      {e.state === "loading" && <p className="muted">Looking in the documentation…</p>}
      {e.state === "error" && (
        <div>
          <p className="error">Could not get an answer: {e.error}</p>
          <button type="button" onClick={() => state.retry(e.id)}>
            Try again
          </button>
        </div>
      )}
      {e.state === "done" && e.answer && (
        <div className="assistant-answer">
          <span className="visually-hidden">Answer: </span>
          <AnswerText answer={e.answer} />
          {!e.answer.fallback && e.answer.citations.length > 0 && (
            <>
              <h3 className="assistant-sources-h">Sources</h3>
              <ol className="assistant-sources">
                {e.answer.citations.map((c) => (
                  <li key={c.n} value={c.n}>
                    <a href={c.href}>{c.title}</a>
                  </li>
                ))}
              </ol>
            </>
          )}
          {e.answer.fallback && (e.answer.sections?.length ?? 0) > 0 && (
            <>
              <h3 className="assistant-sources-h">Matching sections</h3>
              <ul className="assistant-sources assistant-matches">
                {e.answer.sections!.map((s) => (
                  <li key={s.sectionId}>
                    <a href={s.href}>{s.title}</a>
                    <span className="small muted">{s.snippet}</span>
                  </li>
                ))}
              </ul>
            </>
          )}
          <Rating e={e} onRate={(helpful) => state.rate(e.id, helpful)} />
        </div>
      )}
    </li>
  );
}

/**
 * The Ask panel: a non-modal dialog at the side of every page. Answers come from the
 * documentation, with their sources as links that open the viewer in the main area while the
 * panel stays open. Escape closes it and returns focus to where it was.
 */
export function AssistantPanel({
  route,
  state,
  onClose,
  focusSignal,
  covering,
}: {
  route: Route;
  state: AssistantState;
  /** returnFocus false when a link was followed, so the page it opened keeps focus. */
  onClose: (returnFocus?: boolean) => void;
  /** Changes whenever the panel should take focus (opened, or the shortcut pressed while open). */
  focusSignal: number;
  /** The panel covers the page (narrow screens): it is modal, and following a link closes it. */
  covering: boolean;
}) {
  const [text, setText] = useState("");
  const input = useRef<HTMLTextAreaElement>(null);
  const log = useRef<HTMLOListElement>(null);
  const [announce, setAnnounce] = useState("");
  const page = pageHint(route);
  const { exchanges } = state;

  useEffect(() => {
    input.current?.focus();
  }, [focusSignal]);

  // When the window narrows so the panel covers the page, the page goes inert: bring focus in here.
  const panel = useRef<HTMLElement>(null);
  useEffect(() => {
    if (covering && !panel.current?.contains(document.activeElement)) input.current?.focus();
  }, [covering]);

  // Announce each answer once, politely, and keep the newest exchange in view.
  const announced = useRef(new Set<number>());
  useEffect(() => {
    for (const e of exchanges) {
      if (e.state === "loading" || announced.current.has(e.id)) continue;
      announced.current.add(e.id);
      setAnnounce(e.state === "done" ? `Answer: ${e.answer?.answer ?? ""}` : `Could not get an answer: ${e.error}`);
    }
    log.current?.lastElementChild?.scrollIntoView?.({ block: "nearest" });
  }, [exchanges]);

  const send = (question: string) => {
    if (!question.trim() || question.length > MAX) return;
    state.ask(question, page);
    setAnnounce("Looking in the documentation…");
    setText("");
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    send(text);
  };

  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      send(text);
    }
  };

  return (
    <section
      ref={panel}
      id="assistant"
      className="assistant"
      role="dialog"
      aria-modal={covering ? "true" : "false"}
      aria-labelledby="assistant-h"
      aria-describedby="assistant-about"
      onKeyDown={(e) => {
        if (e.key === "Escape") {
          // Ours alone: no other Escape handler (React's or one on window) acts on it as well.
          e.stopPropagation();
          e.nativeEvent.stopImmediatePropagation();
          onClose();
        }
      }}
      onClick={(e) => {
        // On a narrow screen the viewer opens behind the panel, so following a link closes it.
        if (covering && e.target instanceof Element && e.target.closest("a[href^='#/']")) onClose(false);
      }}
    >
      <div className="assistant-head">
        <h2 id="assistant-h">
          <Icon name="ask" size={18} className="km-violet-icon" />
          Ask Kilnmark
        </h2>
        {exchanges.length > 0 && (
          <button type="button" className="link-button" onClick={state.clear}>
            Clear
          </button>
        )}
        <button type="button" className="km-icon-button km-close" onClick={() => onClose()} aria-keyshortcuts="Escape" aria-label="Close Ask Kilnmark">
          <Icon name="close" size={20} />
        </button>
      </div>
      <p id="assistant-about" className="hint">
        Answers come from the Kilnmark documentation, with links to the sections they use.{" "}
        <a href="#/docs/assistant">About the Ask panel</a>
      </p>
      <p className="visually-hidden" aria-live="polite" role="status">
        {announce}
      </p>
      <div className="assistant-body">
        {exchanges.length === 0 ? (
          <div className="assistant-suggest">
            <h3 className="assistant-sources-h">Try asking</h3>
            <ul>
              {suggestions(route).map((s) => (
                <li key={s}>
                  <button type="button" className="link-button" onClick={() => send(s)}>
                    {s}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ) : (
          <ol className="assistant-log" ref={log} aria-label="Conversation">
            {exchanges.map((e) => (
              <ExchangeView key={e.id} e={e} state={state} />
            ))}
          </ol>
        )}
      </div>
      <form className="assistant-form" onSubmit={submit}>
        <label htmlFor="assistant-q">Your question</label>
        <textarea
          id="assistant-q"
          ref={input}
          rows={3}
          value={text}
          maxLength={MAX}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={onKey}
          aria-describedby="assistant-q-hint"
        />
        <div className="assistant-form-row">
          <span id="assistant-q-hint" className="hint">
            Enter to ask, Shift+Enter for a new line.
            {text.length > MAX - 200 && ` ${plural(MAX - text.length, "character")} left.`}
          </span>
          <button type="submit" className="primary" aria-disabled={!text.trim() ? "true" : undefined}>
            Ask
          </button>
        </div>
      </form>
    </section>
  );
}
