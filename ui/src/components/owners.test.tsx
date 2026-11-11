// @vitest-environment jsdom
import { afterEach, describe, expect, test, vi } from "vitest";
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import type { Owners } from "../api";
import { OwnersSection } from "./RunTries";

// A pipeline's or flow's Owners card: each owner by a readable name, and, for designers and admins,
// a change laid out as the app's other forms (label, box, hint, buttons, one under the other).

type Fs = { readFileSync(path: string, encoding: "utf8"): string };
const fs = (globalThis as unknown as { process: { getBuiltinModule(id: "node:fs"): Fs } }).process.getBuiltinModule("node:fs");
// Relative to ui/, where the tests run (in jsdom a module URL is not a file URL).
const appCss = fs.readFileSync("src/app.css", "utf8");

const OWNERS: Owners = {
  owners: [
    { entry: "token 1 (KILNMARK_ADMIN_TOKEN)", kind: "person", label: "token 1 (KILNMARK_ADMIN_TOKEN)" },
    { entry: "sso:dana (2f6c41)", kind: "person", label: "sso:dana (2f6c41)" },
    { entry: "data-team@example.com", kind: "team", label: "data-team@example.com" },
  ],
  none: false,
  note: null,
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

let root: Root | null = null;
let host: HTMLElement | null = null;
afterEach(async () => {
  if (root) await act(async () => root!.unmount());
  host?.remove();
  root = null;
  vi.unstubAllGlobals();
});

async function mount(node: React.ReactNode) {
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root!.render(node));
  await act(async () => {}); // the owners arrive
  return host;
}

describe.each(["pipeline", "flow"] as const)("the %s's owners", (kind) => {
  test("show each owner by a readable name, the full record on hover", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json(OWNERS)));
    const el = await mount(<OwnersSection kind={kind} name="orders" role="viewer" />);
    const items = [...el.querySelectorAll(".owners-list li")];
    expect(items.map((li) => li.querySelector(".km-who")!.textContent)).toEqual(["KILNMARK_ADMIN_TOKEN", "sso:dana", "data-team@example.com"]);
    expect(items[0].querySelector(".km-who")!.getAttribute("title")).toBe("token 1 (KILNMARK_ADMIN_TOKEN)");
    // An API token is not a person: KILNMARK_ADMIN_TOKEN is labelled as the token it is.
    expect(items.map((li) => li.querySelector(".muted")!.textContent)).toEqual(["API token", "Person", "Team address"]);
    expect(el.textContent).not.toContain("Change owners"); // a viewer
  });

  test("are changed in a form laid out as the app's others, one entry a line", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json(OWNERS)));
    const el = await mount(<OwnersSection kind={kind} name="orders" role="designer" />);
    const change = [...el.querySelectorAll("button")].find((b) => b.textContent === "Change owners")!;
    await act(async () => change.click());
    const form = el.querySelector(".owners .owners-form")!;
    expect(form.classList.contains("form")).toBe(false); // .form stops at 640px; this box goes across the card
    const field = form.querySelector(".field")!;
    // Label, then box, then hint, in that order inside one field; the buttons after the field.
    expect([...field.children].map((c) => c.tagName)).toEqual(["LABEL", "TEXTAREA", "P"]);
    expect(field.querySelector("label")!.getAttribute("for")).toBe("owners-edit");
    expect(field.querySelector("textarea")!.getAttribute("aria-describedby")).toBe("owners-edit-hint");
    expect(field.nextElementSibling!.className).toBe("buttons");
    expect(field.querySelector("textarea")!.value).toBe("token 1 (KILNMARK_ADMIN_TOKEN)\nsso:dana (2f6c41)\ndata-team@example.com");
  });
});

test("the owners' list and the form have their own styles", () => {
  expect(appCss).toMatch(/\.owners-list\s*\{[^}]*list-style:\s*none/);
  expect(appCss).toMatch(/\.owners-form textarea\s*\{[^}]*width:\s*100%/);
  expect(appCss).not.toMatch(/\.owners-form\s*\{[^}]*max-width/);
});

test("a team address hidden from the reader is not called a team address twice", async () => {
  const hidden: Owners = { owners: [{ entry: null, kind: "team", label: "A team address" }], none: false, note: null };
  vi.stubGlobal("fetch", vi.fn(async () => json(hidden)));
  const el = await mount(<OwnersSection kind="pipeline" name="orders" role="viewer" />);
  expect(el.querySelector(".owners-list li")!.textContent).toBe("A team address");
});

test("the Samples card on Settings is spaced from the admin tiles as the page's sections are", () => {
  // The tiles' grid gap is --km-gap, and so is the gap between the page's sections (.km-section).
  expect(appCss).toMatch(/\.km-section\s*\{[^}]*margin-top:\s*var\(--km-gap\)/);
  expect(appCss).toMatch(/\.settings-list \+ \.samples-switch\s*\{[^}]*margin-top:\s*var\(--km-gap\)/);
});
