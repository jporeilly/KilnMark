import { describe, expect, test } from "vitest";
import type { SampleInfo, SamplePlaceholder, Source } from "../api";
import { addable, addedHref, chosenSources, comesWith, defaultSchema, fileTargets, sourceGroups, sourceLabel, tableTargets, using } from "./AddSamples";

const source = (name: string, category: string, over: Partial<Source> = {}): Source =>
  ({ name, displayName: name, category, kind: "x", settings: {}, assets: 0, formats: {}, ...over }) as Source;

const crm: SamplePlaceholder = { name: "crm-db", category: "databases", description: "A database" };
const files: SamplePlaceholder = { name: "files", category: "files", description: "File storage" };

describe("Add samples", () => {
  test("offers the sources of a placeholder's kind first", () => {
    const all = [source("pg", "databases"), source("bucket", "files"), source("maria", "databases")];
    expect(sourceGroups(crm, all).fitting.map((s) => s.name)).toEqual(["pg", "maria"]);
    expect(sourceGroups(crm, all).others.map((s) => s.name)).toEqual(["bucket"]);
    expect(sourceGroups(files, all).fitting.map((s) => s.name)).toEqual(["bucket"]);
    // Kilnmark's own sample data can be read, never written: it is not offered.
    const withData = [...all, source("sample-data", "files", { kind: "sample-data" })];
    expect(sourceGroups(files, withData).fitting.map((s) => s.name)).toEqual(["bucket"]);
  });

  test("says when a file source does not allow writing", () => {
    expect(sourceLabel(files, source("bucket", "files"))).toBe("bucket, does not allow writing");
    expect(sourceLabel(files, source("bucket", "files", { settings: { allow_writing: "true" } }))).toBe("bucket");
    expect(sourceLabel(crm, source("pg", "databases", { displayName: "Sales" }))).toBe("Sales (pg)");
  });

  test("leaves out placeholders not given a source", () => {
    expect(chosenSources({ "crm-db": "pg", "shop-db": "", files: "bucket" })).toEqual({ "crm-db": "pg", files: "bucket" });
  });

  test("counts only the samples whose every placeholder has a source", () => {
    const sample = (name: string, sources: string[], type: SampleInfo["type"] = "pipeline"): SampleInfo => ({
      file: `${name}.yaml`, name, type, description: null, sources, exists: false, explanation: null,
    });
    const all = [sample("a", ["crm-db", "files"]), sample("b", ["shop-db", "files"]), sample("f", [], "flow")];
    expect(addable(all, { "crm-db": "pg" })).toEqual([]);
    expect(addable(all, { "crm-db": "pg", files: "bucket" }).map((s) => s.name)).toEqual(["a"]);
  });

  test("says what comes with Kilnmark from the samples listed, flows included, never from fixed numbers", () => {
    const sample = (name: string, type: SampleInfo["type"], sources: string[] = []): SampleInfo => ({
      file: `${name}.yaml`, name, type, description: null, sources, exists: false, explanation: null,
    });
    const many = [
      ...Array.from({ length: 34 }, (_, i) => sample(`sample-p${i}`, "pipeline", ["files"])),
      ...Array.from({ length: 9 }, (_, i) => sample(`sample-f${i}`, "flow", i === 0 ? ["crm-db"] : [])),
    ];
    expect(comesWith(many)).toBe("34 small sample pipelines and 9 sample flows that run them");
    // Whatever the list holds: one more pipeline and one fewer flow change the words with it.
    expect(comesWith([...many.slice(0, 42), sample("sample-extra", "pipeline")])).toBe(
      "35 small sample pipelines and 8 sample flows that run them",
    );
    expect(comesWith([sample("a", "pipeline"), sample("f", "flow")])).toBe("1 small sample pipeline and 1 sample flow that runs them");
    expect(comesWith([sample("a", "pipeline")])).toBe("1 small sample pipeline");
    expect(comesWith([sample("f", "flow")])).toBe("1 sample flow");
    expect(comesWith([])).toBeNull();
    expect(comesWith(null)).toBeNull();
    // A placeholder's samples count the flows that name it too, and a flow just added links to its own page.
    expect(using(many, "crm-db").map((s) => s.name)).toEqual(["sample-f0"]);
    expect(using(many, "files")).toHaveLength(34);
    expect(addedHref(many, { name: "sample-f0", file: "sample-f0.yaml" })).toBe("#/flows/sample-f0");
    expect(addedHref(many, { name: "sample-p0", file: "sample-p0.yaml" })).toBe("#/pipelines/sample-p0");
  });

  test("the sample tables go in a database that allows writing, in its first schema or its kind's usual one", () => {
    const pg = source("pg", "databases", { kind: "postgresql", settings: { allow_writing: "true" } });
    const ms = source("ms", "databases", { kind: "sqlserver", settings: { allow_writing: "true", schemas: "sales; hr" } });
    const ro = source("ro", "databases", { kind: "postgresql" });
    expect(tableTargets([pg, ms, ro, source("bucket", "files", { settings: { allow_writing: "true" } })]).map((s) => s.name))
      .toEqual(["pg", "ms"]);
    expect(defaultSchema(pg)).toBe("public");
    expect(defaultSchema(ms)).toBe("sales");
    expect(defaultSchema(source("my", "databases", { kind: "mysql" }))).toBe("");
    expect(defaultSchema(undefined)).toBe("");
  });

  test("the starter pack writes its files to a file source that allows writing", () => {
    const ok = source("bucket", "files", { settings: { allow_writing: "true" } });
    expect(fileTargets([ok, source("share", "files"), source("pg", "databases", { settings: { allow_writing: "true" } })])
      .map((s) => s.name)).toEqual(["bucket"]);
  });
});
