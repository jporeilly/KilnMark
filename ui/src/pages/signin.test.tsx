// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, test, vi } from "vitest";
import type { AuthStatus } from "../api";

let status: AuthStatus = { provider: false, providerName: "your organisation", signedIn: false, refusal: null };

vi.mock("../api", async (original) => {
  const real = await original<typeof import("../api")>();
  return { ...real, api: { ...real.api, authStatus: vi.fn(async () => status) } };
});

const { SignInPage } = await import("./SignInPage");

const host = document.createElement("div");
document.body.appendChild(host);
const root = createRoot(host);

afterEach(() => {
  act(() => root.render(<></>));
});

const settle = () =>
  act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });

async function show(s: AuthStatus) {
  status = s;
  await act(async () => root.render(<SignInPage notice={null} onSignedIn={() => {}} />));
  await settle();
}

/** The sign-in page asks the server whether an identity provider is set up (ADR 0036). */
describe("the sign-in page", () => {
  test("offers the identity provider by name, with API tokens below", async () => {
    await show({ provider: true, providerName: "Example SSO", signedIn: false, refusal: null });
    const link = [...host.querySelectorAll("a")].find((a) => a.textContent === "Sign in with Example SSO");
    expect(link?.getAttribute("href")).toBe("/api/v1/auth/login");
    expect(host.querySelector("h2")?.textContent).toBe("Or use an API token");
    expect(host.querySelector("#token")).not.toBeNull();
  });

  test("says why a provider sign-in has no role", async () => {
    await show({ provider: true, providerName: "Example SSO", signedIn: true, refusal: "No second factor was used." });
    expect(host.querySelector('[role="alert"]')?.textContent).toBe("No second factor was used.");
  });

  test("shows only the token form without a provider", async () => {
    await show({ provider: false, providerName: "your organisation", signedIn: false, refusal: null });
    expect(host.querySelector("#token")).not.toBeNull();
    expect([...host.querySelectorAll("a")].some((a) => a.textContent?.startsWith("Sign in with"))).toBe(false);
  });
});
