import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { formatClock, type FlowRunDetail } from "../api";
import { retrying } from "../components/RunTries";
import { runSentence } from "./FlowRunTimeline";

// A browser set to American English, where the browser's own clock reads "5:17:06 PM".
const own = Date.prototype.toLocaleTimeString;
beforeEach(() => {
  Date.prototype.toLocaleTimeString = function (locales?: Intl.LocalesArgument, options?: Intl.DateTimeFormatOptions) {
    return own.call(this, locales ?? "en-US", options);
  };
});
afterEach(() => {
  Date.prototype.toLocaleTimeString = own;
});

const at = "2026-11-08T17:17:06Z";

describe("run times use the app's one clock, 24-hour", () => {
  test("the time alone, with seconds for a timeline", () => {
    expect(formatClock(at, true)).toMatch(/^\d{2}:17:06$/);
    expect(formatClock(at)).toMatch(/^\d{2}:17$/);
    expect(formatClock(null)).toBe("–");
  });

  test("a flow run's timeline and a run's next try say it the same way", () => {
    const detail = { run: { flow: "sample-retries", startedAt: at, finishedAt: "2026-11-08T17:17:09Z", status: "succeeded" } } as unknown as FlowRunDetail;
    const sentence = runSentence(detail, Date.parse(at));
    expect(sentence).toContain(`started ${formatClock(at, true)},`);
    expect(sentence).not.toMatch(/AM|PM/);
    const next = retrying({ status: "queued", retryAt: at, tryNumber: 2, tries: 4 } as Parameters<typeof retrying>[0]);
    expect(next).toBe(`Retrying: try 2 of 4, next at ${formatClock(at)}`);
    expect(next).not.toMatch(/AM|PM/);
  });
});
