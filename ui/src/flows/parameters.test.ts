import { describe, expect, it } from "vitest";
import type { FlowParameter } from "../api";
import { defaultText, defaultWords, runValues } from "./parameters";

const p = (name: string, type: FlowParameter["type"], defaultValue: unknown = null): FlowParameter => ({
  name,
  type,
  defaultValue,
  defaultVariable: null,
  required: defaultValue == null,
  description: null,
});

describe("run values", () => {
  it("leaves defaults to the server and sends what was changed, as its kind", () => {
    const params = [p("share", "number", 0.02), p("label", "text"), p("ids", "number list"), p("on", "boolean", true)];
    expect(runValues(params, { label: " nightly ", ids: "1, 2,3" })).toEqual({
      values: { label: "nightly", ids: [1, 2, 3] },
      problems: {},
    });
    expect(runValues(params, { label: "x", share: "0.1", on: "false" }).values).toEqual({
      label: "x",
      share: 0.1,
      on: false,
    });
  });

  it("asks for a value with no default, and refuses words where numbers go", () => {
    const params = [p("label", "text"), p("share", "number", 0.02), p("ids", "number list")];
    const { problems } = runValues(params, { share: "a lot", ids: "1, two" });
    expect(Object.keys(problems)).toEqual(["label", "share", "ids"]);
    expect(problems.label).toContain("no default");
  });

  it("leaves a server variable's default to the server, saying so", () => {
    const region: FlowParameter = { ...p("region", "text"), defaultVariable: "region", required: false };
    const on: FlowParameter = { ...p("on", "boolean"), defaultVariable: "flag", required: false };
    expect(defaultText(region)).toBe("");
    expect(defaultWords(region)).toContain("server variable region");
    expect(runValues([region, on], {})).toEqual({ values: {}, problems: {} });
    expect(runValues([region, on], { region: "APAC", on: "true" }).values).toEqual({ region: "APAC", on: true });
  });

  it("shows a list default comma-separated", () => {
    expect(defaultText(p("statuses", "text list", ["shipped", "delivered"]))).toBe("shipped, delivered");
    expect(runValues([p("statuses", "text list", ["shipped", "delivered"])], {}).values).toEqual({});
  });
});
