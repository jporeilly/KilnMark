// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import { afterAll, describe, expect, test, vi } from "vitest";
import type { AccessReview, AccessReviewEntry } from "../api";

const person = (e: Partial<AccessReviewEntry>): AccessReviewEntry => ({
  kind: "provider",
  name: "rita@bank.example",
  role: "viewer",
  unmask: false,
  status: "waiting for first sign-in",
  createdBy: "token 1 (admin)",
  createdAt: "2026-11-01T09:00:00Z",
  expiresAt: null,
  lastUsedAt: null,
  signIns: 0,
  example: false,
  groups: ["kilnmark-viewers"],
  sensitiveGroups: [],
  state: "waiting",
  ...e,
});

/** A review as the server sends it: a token with the grant, and people in each state. */
const REVIEW: AccessReview = {
  generatedAt: "2026-11-09T09:00:00Z",
  tokenMaxLifetimeDays: 90,
  entries: [
    {
      kind: "token",
      name: "ci",
      role: "designer",
      unmask: true,
      status: "active",
      createdBy: "token 1 (admin)",
      createdAt: "2026-11-01T09:00:00Z",
      expiresAt: null,
      lastUsedAt: null,
      signIns: null,
      example: false,
      groups: null,
      sensitiveGroups: null,
      state: null,
    },
    person({
      name: "sso:dana@bank.example (3f2a)",
      role: "designer",
      unmask: true,
      status: "signed in",
      state: "signed-in",
      groups: ["kilnmark-designers", "kilnmark-sensitive-data"],
      sensitiveGroups: ["kilnmark-sensitive-data"],
      lastUsedAt: "2026-11-08T09:00:00Z",
      signIns: 3,
    }),
    person({}),
    person({
      name: "sam@bank.example",
      role: "steward",
      status: "waiting to be activated",
      state: "not-activated",
      groups: ["kilnmark-sensitive-data", "kilnmark-stewards"],
      sensitiveGroups: ["kilnmark-sensitive-data"],
    }),
    person({
      name: "old@bank.example",
      role: "designer",
      unmask: true,
      status: "disabled: temporary password unused for 72 hours",
      state: "disabled",
      groups: ["kilnmark-designers", "kilnmark-sensitive-data"],
      sensitiveGroups: ["kilnmark-sensitive-data"],
    }),
  ],
  roles: [
    { id: "read", what: "Search and read the catalogue", byRole: { viewer: "yes", steward: "yes", designer: "yes", admin: "yes" } },
    { id: "unmask", what: "See values of columns marked sensitive unmasked", byRole: { viewer: "no", steward: "no", designer: "grant", admin: "no" } },
  ],
  groups: {
    viewer: ["kilnmark-viewers"],
    steward: ["kilnmark-stewards"],
    designer: ["kilnmark-designers"],
    admin: ["kilnmark-admins"],
    unmask: ["kilnmark-sensitive-data"],
  },
  rules: [
    { id: "token-lifetime", label: "Longest token lifetime", value: "90 days", setting: "kilnmark.auth.token-max-lifetime" },
    { id: "unmask-approval", label: "A second person approves the sensitive-data grant", value: "On", setting: "kilnmark.auth.unmask-approval" },
  ],
};

vi.mock("../api", async (original) => {
  const real = await original<typeof import("../api")>();
  return {
    ...real,
    accessReviewApi: { ...real.accessReviewApi, get: vi.fn(async () => REVIEW), csv: vi.fn(async () => "") },
  };
});

const { AccessReviewPage, ONLY_AT_THE_PROVIDER, cellWord, groupWords, lastSeen, seesUnmasked, sensitiveWords } =
  await import("./AccessReviewPage");

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
const host = document.createElement("div");
document.body.appendChild(host);
const root = createRoot(host);

afterAll(() => act(() => root.render(<></>)));

const render = async () => {
  await act(async () => root.render(<AccessReviewPage />));
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
};

const rowOf = (name: string) =>
  [...host.querySelectorAll("tbody tr")].find((tr) => tr.querySelector("td")?.textContent?.startsWith(name)) as
    | HTMLTableRowElement
    | undefined;

describe("the access review page", () => {
  test("shows what each role can do, with the groups that give it", async () => {
    await render();
    const table = host.querySelector('[aria-label="What each role can do"]');
    expect(table).not.toBeNull();
    const heads = [...table!.querySelectorAll("thead th")].map((th) => th.textContent);
    expect(heads).toEqual(["What", "Viewer", "Steward", "Designer", "Admin"]);
    const unmask = table!.querySelector('[data-line="unmask"]')!;
    expect([...unmask.querySelectorAll("td")].map((td) => td.textContent)).toEqual(["No", "No", "With the grant", "No"]);
    expect(table!.textContent).toContain("kilnmark-admins");
    expect(host.textContent).toContain("a designer in kilnmark-sensitive-data");
  });

  test("lists the rules in force, read-only, with their setting", async () => {
    await render();
    const rule = host.querySelector('[data-rule="token-lifetime"]')!;
    expect(rule.textContent).toContain("90 days");
    expect(rule.textContent).toContain("kilnmark.auth.token-max-lifetime");
    expect(host.querySelector('[data-rule="unmask-approval"]')!.textContent).toContain("On");
    // read-only: nothing to change there
    expect(host.querySelectorAll("#review-rules-h ~ * input, #review-rules-h ~ * select").length).toBe(0);
  });

  test("lists people before their first sign-in, with their state and groups", async () => {
    await render();
    const waiting = rowOf("rita@bank.example")!;
    expect(waiting.dataset.state).toBe("waiting");
    expect(waiting.textContent).toContain("waiting for first sign-in");
    expect(waiting.textContent).toContain("kilnmark-viewers");
    expect(waiting.textContent).toContain("Not yet");
    expect(rowOf("sam@bank.example")!.textContent).toContain("waiting to be activated");
    expect(rowOf("old@bank.example")!.textContent).toContain("disabled: temporary password unused for 72 hours");
    expect(host.textContent).toContain(ONLY_AT_THE_PROVIDER);
  });

  test("marks only those who see sensitive values unmasked now", async () => {
    await render();
    const marked = (name: string) => rowOf(name)!.querySelector('[data-kind="unmasked"]') !== null;
    expect(marked("ci")).toBe(true);
    expect(marked("sso:dana@bank.example")).toBe(true);
    // a designer who has not signed in yet, or is disabled, sees nothing yet; a steward in the group never does
    expect(marked("old@bank.example")).toBe(false);
    expect(marked("sam@bank.example")).toBe(false);
    expect(rowOf("sam@bank.example")!.textContent).toContain("but not a designer: sees them masked");
  });
});

describe("the access review's words", () => {
  test("say what a role may do, and a person's grant and groups", () => {
    expect(cellWord("yes")).toBe("Yes");
    expect(cellWord("grant")).toBe("With the grant");
    expect(cellWord("no")).toBe("No");
    expect(sensitiveWords(person({ role: "designer", unmask: true, state: "signed-in" }))).toBe("Sees them unmasked");
    expect(sensitiveWords(person({ role: "designer", unmask: true }))).toBe("Will see them unmasked once signed in");
    expect(sensitiveWords(person({ groups: null, sensitiveGroups: null }))).toBe("Not recorded");
    expect(groupWords(person({ groups: [] }))).toBe("None that gives a role");
    expect(lastSeen(person({ createdAt: null }))).toBe("Not yet");
  });

  test("never mark a revoked token, or someone who has not signed in, as seeing values", () => {
    expect(seesUnmasked(person({ kind: "token", unmask: true, status: "revoked", state: null }))).toBe(false);
    expect(seesUnmasked(person({ unmask: true, state: "waiting" }))).toBe(false);
    expect(seesUnmasked(person({ unmask: true, state: "signed-in" }))).toBe(true);
  });
});
