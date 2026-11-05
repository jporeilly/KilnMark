// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, test } from "vitest";
import type { Me } from "../api";
import { UserMenu } from "./TopBar";

const host = document.createElement("div");
document.body.appendChild(host);
const root = createRoot(host);

afterEach(() => {
  act(() => root.render(<></>));
});

const base: Me = {
  name: "sso:alex.morgan@example.com (7fa04543-0000)",
  displayName: "Alex Morgan",
  role: "admin",
  tokenId: null,
  expiresAt: null,
  signInRequired: true,
  unmask: false,
  via: "provider",
};

function show(me: Me) {
  act(() => root.render(<UserMenu me={me} />));
  return host.querySelector<HTMLButtonElement>(".user-button")!;
}

/** The top bar greets people by name; the menu also shows the name Kilnmark records them by. */
describe("the user menu", () => {
  test("shows a provider sign-in by the person's name, with their initials", () => {
    const button = show(base);
    expect(button.querySelector(".user-name strong")?.textContent).toBe("Alex Morgan");
    expect(button.querySelector(".avatar")?.textContent).toBe("AM");
    expect(button.textContent).not.toContain("7fa04543");
  });

  test("the open menu also shows the recorded sso: name", () => {
    const button = show(base);
    act(() => button.click());
    expect(host.querySelector(".km-popover-recorded")?.textContent).toBe(base.name);
  });

  test("a token, or an older server with no displayName, shows the name as before", () => {
    const button = show({ ...base, name: "ci-bot", displayName: undefined, via: "token" });
    expect(button.querySelector(".user-name strong")?.textContent).toBe("ci-bot");
    act(() => button.click());
    expect(host.querySelector(".km-popover-recorded")).toBeNull();
  });
});
