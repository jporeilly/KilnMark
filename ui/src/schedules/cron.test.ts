import { describe, expect, test } from "vitest";
import type { Schedule } from "../api";
import { scheduleChange } from "../api";
import { cronWords, ordinal, outcomeOf, pausedBySetter, pipelineParameters, presetCron, readPreset, timeZones, valuesText } from "./cron";
import { parse } from "../router";

describe("the schedule presets", () => {
  test("each fills in the cron expression it stands for", () => {
    expect(presetCron({ kind: "daily", time: "02:00" })).toBe("0 2 * * *");
    expect(presetCron({ kind: "weekdays", time: "06:30" })).toBe("30 6 * * MON-FRI");
    expect(presetCron({ kind: "hourly", minute: 0 })).toBe("0 * * * *");
    expect(presetCron({ kind: "hourly", minute: 15 })).toBe("15 * * * *");
    expect(presetCron({ kind: "minutes", every: 15 })).toBe("*/15 * * * *");
    expect(presetCron({ kind: "minutes", every: 1 })).toBe("* * * * *");
    expect(presetCron({ kind: "monthly", day: 1, time: "01:00" })).toBe("0 1 1 * *");
    expect(presetCron({ kind: "monthly", day: 31, time: "23:59" })).toBe("59 23 31 * *");
  });

  test("give nothing for a part that is not right yet", () => {
    expect(presetCron({ kind: "daily", time: "" })).toBeNull();
    expect(presetCron({ kind: "daily", time: "24:00" })).toBeNull();
    expect(presetCron({ kind: "hourly", minute: 60 })).toBeNull();
    expect(presetCron({ kind: "minutes", every: 7 })).toBeNull();
    expect(presetCron({ kind: "monthly", day: 0, time: "01:00" })).toBeNull();
    expect(presetCron({ kind: "monthly", day: 32, time: "01:00" })).toBeNull();
    expect(presetCron({ kind: "custom" })).toBeNull();
  });

  test("an expression opens on the preset it is, so a saved schedule is edited the way it was made", () => {
    for (const p of [
      { kind: "daily", time: "02:00" },
      { kind: "weekdays", time: "06:30" },
      { kind: "hourly", minute: 15 },
      { kind: "minutes", every: 15 },
      { kind: "minutes", every: 1 },
      { kind: "monthly", day: 28, time: "01:05" },
    ] as const) {
      expect(readPreset(presetCron(p)!)).toEqual(p);
    }
    expect(readPreset("0 2 * * 1-5")).toEqual({ kind: "weekdays", time: "02:00" });
    expect(readPreset("0 9-17 * * 1-5")).toEqual({ kind: "custom" });
    expect(readPreset("*/7 * * * *")).toEqual({ kind: "custom" });
    expect(readPreset("0 0 1 * MON")).toEqual({ kind: "custom" });
    expect(readPreset("@daily")).toEqual({ kind: "custom" });
  });
});

describe("a schedule in words", () => {
  test("says the common shapes in words, and leaves the rest to the expression", () => {
    expect(cronWords("0 2 * * *")).toBe("Every day at 02:00");
    expect(cronWords("30 6 * * MON-FRI")).toBe("Weekdays (Monday to Friday) at 06:30");
    expect(cronWords("0 * * * *")).toBe("Every hour, on the hour");
    expect(cronWords("5 * * * *")).toBe("Every hour at 5 minutes past");
    expect(cronWords("*/15 * * * *")).toBe("Every 15 minutes");
    expect(cronWords("0 1 1 * *")).toBe("On the 1st of each month at 01:00");
    expect(cronWords("@weekly")).toBe("Every Sunday at 00:00");
    expect(cronWords("0 9-17 * * 1-5")).toBeNull();
    expect([1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31].map(ordinal)).toEqual([
      "1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st",
    ]);
  });

  test("an outcome has a word as well as a tone", () => {
    expect(outcomeOf("started")).toEqual({ word: "Started", tone: "good", reason: null });
    expect(outcomeOf("skipped: a run was still going")).toEqual({ word: "Skipped", tone: "warn", reason: "a run was still going" });
    expect(outcomeOf("missed: the server was not running").word).toBe("Missed");
    expect(outcomeOf("failed: Version 2 of orders cannot run")).toEqual({
      word: "Failed",
      tone: "bad",
      reason: "Version 2 of orders cannot run",
    });
    expect(outcomeOf("failed")).toEqual({ word: "Failed", tone: "bad", reason: null });
    expect(outcomeOf(null).word).toBe("Not run yet");
  });

  test("a schedule Kilnmark paused reads apart from one a person paused", () => {
    expect(outcomeOf("paused: the person who set it no longer has the role")).toEqual({
      word: "Paused: setter lost the role",
      tone: "warn",
      reason: null,
    });
    // Viewers get just "paused".
    expect(outcomeOf("paused")).toEqual({ word: "Paused by Kilnmark", tone: "warn", reason: null });
    expect(outcomeOf("paused: some other reason")).toEqual({ word: "Paused by Kilnmark", tone: "warn", reason: "some other reason" });
    const s = (enabled: boolean, lastOutcome: string | null) => ({ enabled, lastOutcome }) as Schedule;
    expect(pausedBySetter(s(false, "paused: the person who set it no longer has the role"))).toBe(true);
    expect(pausedBySetter(s(false, "paused"))).toBe(true);
    // Paused by a person, or resumed since: not in the filter.
    expect(pausedBySetter(s(false, "started"))).toBe(false);
    expect(pausedBySetter(s(true, "paused: the person who set it no longer has the role"))).toBe(false);
  });

  test("values in a line, and none for viewers", () => {
    expect(valuesText({ region: "EMEA", statuses: ["shipped", "delivered"] })).toBe("region = EMEA; statuses = shipped, delivered");
    expect(valuesText({})).toBe("The defaults");
    expect(valuesText(null)).toBe("");
  });

  test("time zones include UTC and any zone a schedule already has", () => {
    const zones = timeZones("Mars/Olympus");
    expect(zones).toContain("UTC");
    expect(zones).toContain("Mars/Olympus");
  });
});

describe("a pipeline's parameters for the form", () => {
  test("are read from its YAML, a server variable's default named, not shown", () => {
    const yaml = [
      "kilnmark: pipeline/v1",
      "name: orders",
      "parameters:",
      "  statuses: { type: text list, default: [delivered], description: The statuses to keep }",
      "  min_fee: { type: number, default: 0 }",
      '  region: { type: text, default: "${vars.sample_region}" }',
      "  odd: { type: colour, default: red }",
      "nodes: []",
    ].join("\n");
    expect(pipelineParameters(yaml)).toEqual([
      { name: "statuses", type: "text list", defaultValue: ["delivered"], defaultVariable: null, required: false, description: "The statuses to keep" },
      { name: "min_fee", type: "number", defaultValue: 0, defaultVariable: null, required: false, description: null },
      { name: "region", type: "text", defaultValue: null, defaultVariable: "sample_region", required: false, description: null },
    ]);
    expect(pipelineParameters("name: x\nnodes: []\n")).toEqual([]);
    expect(pipelineParameters(": : not yaml [")).toEqual([]);
  });
});

describe("the schedules page", () => {
  test("is in the address, with its filter", () => {
    expect(parse("#/schedules")).toEqual({ page: "schedules", show: "all" });
    expect(parse("#/schedules?show=failed")).toEqual({ page: "schedules", show: "failed" });
    expect(parse("#/schedules?show=setter")).toEqual({ page: "schedules", show: "setter" });
    expect(parse("#/schedules?show=nonsense")).toEqual({ page: "schedules", show: "all" });
  });

  test("pausing sends everything back, as the API replaces all of it", () => {
    const s: Schedule = {
      id: 3,
      pipeline: "orders",
      flow: null,
      cron: "0 2 * * *",
      zone: "Europe/London",
      parameters: { region: "EMEA" },
      runAs: "steward",
      enabled: true,
      description: "Nightly",
      createdBy: "dana",
      createdAt: "2026-11-01T10:00:00Z",
      updatedBy: "dana",
      updatedAt: "2026-11-01T10:00:00Z",
      nextAt: "2026-11-07T02:00:00Z",
      lastAt: null,
      lastOutcome: null,
      lastPipelineRun: null,
      lastFlowRun: null,
    };
    expect(scheduleChange(s)).toEqual({
      pipeline: "orders",
      flow: null,
      cron: "0 2 * * *",
      zone: "Europe/London",
      parameters: { region: "EMEA" },
      runAs: "steward",
      enabled: true,
      description: "Nightly",
    });
  });
});
