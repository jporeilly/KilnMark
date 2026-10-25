import { useCallback, useRef, useState } from "react";
import { assistantApi, type AssistantAnswer, type AssistantTurn } from "./api";

/** One question and what came back. rating: true helpful, false not, null not rated yet. */
export type Exchange = {
  id: number;
  question: string;
  page: string | null;
  state: "loading" | "done" | "error";
  answer?: AssistantAnswer;
  error?: string;
  rating: boolean | null;
  ratingError?: string;
};

/**
 * Earlier turns sent with a question, as the server takes them: the last three questions a model
 * answered, with its answers. Lists of matching sections (fallbacks) are left out: they are not
 * answers, and would only fill the model's context. The server shortens them further to fit.
 */
function history(exchanges: Exchange[]): AssistantTurn[] {
  const out: AssistantTurn[] = [];
  for (const e of exchanges.filter((x) => x.state === "done" && x.answer && !x.answer.fallback).slice(-3)) {
    out.push({ role: "user", text: e.question.slice(0, 2000) });
    out.push({ role: "assistant", text: e.answer!.answer.slice(0, 4000) });
  }
  return out;
}

/**
 * The conversation with the assistant. It lives in the app shell, so it stays while the person
 * moves around the app and is gone when they sign out (the shell is unmounted) or reload.
 */
export function useAssistant() {
  const [exchanges, setExchanges] = useState<Exchange[]>([]);
  const next = useRef(1);
  // The latest list, for building history inside callbacks without re-creating them.
  const latest = useRef<Exchange[]>([]);
  latest.current = exchanges;

  const update = (id: number, change: Partial<Exchange>) =>
    setExchanges((list) => list.map((e) => (e.id === id ? { ...e, ...change } : e)));

  const ask = useCallback((question: string, page: string | null) => {
    const q = question.trim();
    if (!q) return;
    const id = next.current++;
    const turns = history(latest.current);
    setExchanges((list) => [...list, { id, question: q, page, state: "loading", rating: null }]);
    assistantApi
      .ask(q, page, turns)
      .then((answer) => update(id, { state: "done", answer }))
      .catch((e: Error) => update(id, { state: "error", error: e.message }));
  }, []);

  const retry = useCallback(
    (id: number) => {
      const e = latest.current.find((x) => x.id === id);
      if (!e) return;
      setExchanges((list) => list.filter((x) => x.id !== id));
      ask(e.question, e.page);
    },
    [ask],
  );

  const rate = useCallback((id: number, helpful: boolean) => {
    const e = latest.current.find((x) => x.id === id);
    if (!e?.answer || e.rating != null) return;
    update(id, { rating: helpful, ratingError: undefined });
    const a = e.answer;
    assistantApi
      .feedback({
        question: e.question,
        answerId: a.answerId,
        answer: a.answer,
        sectionIds: a.fallback ? (a.sections ?? []).map((s) => s.sectionId) : a.citations.map((c) => c.sectionId),
        helpful,
        page: e.page,
        fallback: a.fallback,
      })
      .catch((err: Error) => update(id, { rating: null, ratingError: err.message }));
  }, []);

  const clear = useCallback(() => setExchanges([]), []);

  return { exchanges, ask, retry, rate, clear };
}

export type AssistantState = ReturnType<typeof useAssistant>;
