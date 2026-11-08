import { describe, expect, test } from "vitest";
import { plural, type AccessReviewEntry } from "./api";
import { lastSeen } from "./pages/AccessReviewPage";
import { runWords } from "./pipelines/canvas/NodeCard";

describe("every count reads right for one", () => {
  test("one helper: singular for exactly one", () => {
    expect(plural(1, "row")).toBe("1 row");
    expect(plural(0, "row")).toBe("0 rows");
    expect(plural(1204, "sign-in")).toBe("1,204 sign-ins");
    expect(plural(1, "address", "addresses")).toBe("1 address");
    expect(plural(2, "address", "addresses")).toBe("2 addresses");
  });

  test("the access review's sign-ins and a node's rows", () => {
    const one: AccessReviewEntry = {
      kind: "provider",
      name: "sso:dana",
      role: "designer",
      unmask: false,
      status: "active",
      createdBy: "",
      createdAt: "2026-11-01T09:00:00Z",
      expiresAt: null,
      lastUsedAt: "2026-11-02T10:00:00Z",
      signIns: 1,
    };
    expect(lastSeen(one)).toMatch(/\(1 sign-in\)$/);
    expect(runWords({ rowsIn: 1, rowsOut: null, outcome: null, error: null } as unknown as Parameters<typeof runWords>[0])).toContain("1 row in");
  });

  // Every source file, as text: a count printed straight before a plural noun, without the helper,
  // is the fault this guards against ("1 rows in", "errors · 1 rows", "(1 sign-ins)").
  const sources = import.meta.glob(["./**/*.ts", "./**/*.tsx", "!./**/*.test.*"], {
    query: "?raw",
    import: "default",
    eager: true,
  }) as Record<string, string>;
  const NOUNS =
    "rows|columns|tables|nodes|edges|runs|tries|items|values|rules|issues|sources|samples|tokens|schedules|versions|" +
    "pipelines|flows|events|errors|problems|files|assets|characters|sign-ins|kinds|addresses|connectors|relationships|matches|results";
  const BARE = new RegExp(`(?:formatNumber\\([^()]*\\)|\\.length|\\b(?:count|total|n))\\}\\s+(?:${NOUNS})\\b`, "g");

  test("the sweep reads the sources", () => {
    expect(Object.keys(sources).length).toBeGreaterThan(50);
  });

  test("no count is printed before a plural noun without the helper", () => {
    const found = Object.entries(sources).flatMap(([file, text]) => [...text.matchAll(BARE)].map((m) => `${file}: ${m[0]}`));
    expect(found).toEqual([]);
  });
});
