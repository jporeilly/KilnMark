/** Calls to the assistant (docs/design/assistant.md): ask, the section search and feedback. */
import { call } from "../api";

export type AssistantCitation = { n: number; sectionId: string; page: string; title: string; href: string };
export type AssistantSection = { sectionId: string; page: string; title: string; href: string; snippet: string };

/** fallback: not a model's answer but the best matching sections, with a short message in answer. */
export type AssistantAnswer = {
  answerId: string;
  answer: string;
  citations: AssistantCitation[];
  domains: string[];
  model: string | null;
  fallback: boolean;
  sections: AssistantSection[] | null;
};

export type AssistantTurn = { role: "user" | "assistant"; text: string };

export const assistantApi = {
  ask: (question: string, page: string | null, history: AssistantTurn[]) =>
    call<AssistantAnswer>("/api/v1/assistant/ask", {
      method: "POST",
      body: JSON.stringify({ question, page, history }),
    }),
  feedback: (f: {
    question: string;
    answerId: string;
    answer: string;
    sectionIds: string[];
    helpful: boolean;
    page: string | null;
    fallback: boolean;
  }) => call<{ id: number }>("/api/v1/assistant/feedback", { method: "POST", body: JSON.stringify(f) }),
};
