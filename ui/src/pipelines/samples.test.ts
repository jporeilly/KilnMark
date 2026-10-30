import { describe, expect, test } from "vitest";
import type { SamplePlaceholder, Source } from "../api";
import { chosenSources, sourceGroups, sourceLabel } from "./AddSamples";

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
  });

  test("says when a file source does not allow writing", () => {
    expect(sourceLabel(files, source("bucket", "files"))).toBe("bucket, does not allow writing");
    expect(sourceLabel(files, source("bucket", "files", { settings: { allow_writing: "true" } }))).toBe("bucket");
    expect(sourceLabel(crm, source("pg", "databases", { displayName: "Sales" }))).toBe("Sales (pg)");
  });

  test("leaves out placeholders not given a source", () => {
    expect(chosenSources({ "crm-db": "pg", "shop-db": "", files: "bucket" })).toEqual({ "crm-db": "pg", files: "bucket" });
  });
});
