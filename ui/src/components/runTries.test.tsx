// @vitest-environment jsdom
import { afterEach, describe, expect, test } from "vitest";
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { retrying, RunTriesList, saysNext, triedWords } from "./RunTries";

let root: Root | null = null;

afterEach(async () => {
  if (root) await act(async () => root!.unmount());
  root = null;
  document.body.innerHTML = "";
});

async function show(node: React.ReactNode): Promise<HTMLDivElement> {
  const host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root!.render(node));
  return host;
}

describe("a run's tries (ADR 0053)", () => {
  const at = "2026-11-07T14:05:00Z";

  test("says when a waiting run tries again", () => {
    expect(retrying({ status: "queued", tryNumber: 2, tries: 4, retryAt: at })).toMatch(
      /^Retrying: try 2 of 4, next at \d{1,2}[:.]\d{2}/,
    );
    expect(retrying({ status: "running", tryNumber: 2, tries: 4, retryAt: null })).toBeNull();
    expect(retrying({ status: "queued", tryNumber: 1, tries: 1, retryAt: null })).toBeNull();
  });

  test("says on which try a run ended, when it needed more than one", () => {
    expect(triedWords({ status: "succeeded", tryNumber: 3, tries: 4 })).toBe("Succeeded on try 3");
    expect(triedWords({ status: "failed", tryNumber: 4, tries: 4 })).toBe("Failed on try 4");
    expect(triedWords({ status: "succeeded", tryNumber: 1, tries: 4 })).toBeNull();
    expect(triedWords({ status: "succeeded" })).toBeNull();
  });

  test("lists each ended try with how it ended and what came next", async () => {
    const host = await show(
      <RunTriesList
        run={{ status: "succeeded", tryNumber: 2, tries: 3 }}
        tries={[
          { number: 1, status: "lost", startedAt: at, finishedAt: at, message: null, next: "Tried again", askedBy: null },
          { number: 2, status: "succeeded", startedAt: at, finishedAt: at, message: null, next: null, askedBy: null },
        ]}
      />,
    );
    expect(host.textContent).toContain("Try 1: Lost with its worker");
    expect(host.textContent).toContain("Try 2: Succeeded");
    expect(host.textContent).toContain("Tried again");
  });

  test("says once why a run was not tried again, when its last try's message says it already", async () => {
    // As the server sends a failed run's last try: the run's error ends with why there were no more
    // tries, and what came next is the same words.
    const why = "Not tried again: all 2 tries were made";
    const host = await show(
      <RunTriesList
        run={{ status: "failed", tryNumber: 2, tries: 2 }}
        tries={[
          { number: 1, status: "failed", startedAt: at, finishedAt: at, message: "Node load failed", next: "Tried again", askedBy: null },
          { number: 2, status: "failed", startedAt: at, finishedAt: at, message: `Node load failed, after 2 tries. ${why}`, next: why, askedBy: null },
        ]}
      />,
    );
    expect(host.textContent!.split(why).length - 1).toBe(1);
    expect(host.textContent).toContain("Tried again");
    expect(saysNext({ message: `x. ${why}`, next: why })).toBe(true);
    // A message that does not say it (or one held back from the reader) leaves what came next shown.
    expect(saysNext({ message: "Node load failed", next: why })).toBe(false);
    expect(saysNext({ message: null, next: why })).toBe(false);
  });

  test("shows nothing for a run tried once that is not waiting", async () => {
    const host = await show(<RunTriesList run={{ status: "running", tryNumber: 1, tries: 1 }} tries={[]} />);
    expect(host.textContent).toBe("");
  });
});
