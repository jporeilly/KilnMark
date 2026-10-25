import { useSyncExternalStore } from "react";

/**
 * Each viewer's own appearance: theme, accent colour and density. Kept in localStorage for this
 * browser only; storage can be blocked or empty, so every access is guarded and the defaults
 * apply without it. Applied as data-theme, data-accent and data-density on <html> (tokens.css).
 */

export type Theme = "system" | "light" | "dark" | "contrast";
export type Accent = "orange" | "blue" | "green" | "purple" | "pink" | "teal";
export type Density = "comfortable" | "compact";
export type Appearance = { theme: Theme; accent: Accent; density: Density };

export const THEMES: { id: Theme; label: string; hint: string }[] = [
  { id: "system", label: "System", hint: "Light or dark, following your device." },
  { id: "light", label: "Light", hint: "Dark text on a light background." },
  { id: "dark", label: "Dark", hint: "Light text on a dark background." },
  {
    id: "contrast",
    label: "High contrast",
    hint: "The strongest contrast, with stronger borders. Light or dark, following your device.",
  },
];

/** Accent presets; each theme has its own shade of each, checked for contrast (tokens.css). */
export const ACCENTS: { id: Accent; label: string }[] = [
  { id: "orange", label: "Orange" },
  { id: "blue", label: "Blue" },
  { id: "green", label: "Green" },
  { id: "purple", label: "Purple" },
  { id: "pink", label: "Pink" },
  { id: "teal", label: "Teal" },
];

export const DENSITIES: { id: Density; label: string; hint: string }[] = [
  { id: "comfortable", label: "Comfortable", hint: "More space around rows and controls." },
  { id: "compact", label: "Compact", hint: "More rows on screen." },
];

export const DEFAULTS: Appearance = { theme: "system", accent: "orange", density: "comfortable" };

const KEY = "kilnmark.appearance";

function valid(a: unknown): Appearance {
  const v = (a ?? {}) as Partial<Appearance>;
  return {
    theme: THEMES.some((t) => t.id === v.theme) ? v.theme! : DEFAULTS.theme,
    accent: ACCENTS.some((t) => t.id === v.accent) ? v.accent! : DEFAULTS.accent,
    density: DENSITIES.some((t) => t.id === v.density) ? v.density! : DEFAULTS.density,
  };
}

function read(): Appearance {
  try {
    const raw = window.localStorage.getItem(KEY);
    return valid(raw ? JSON.parse(raw) : null);
  } catch {
    return DEFAULTS;
  }
}

let current: Appearance = typeof window === "undefined" ? DEFAULTS : read();
const listeners = new Set<() => void>();

/** Puts the choice on <html>, where tokens.css picks it up. */
export function apply(a: Appearance = current) {
  const root = document.documentElement;
  root.dataset.theme = a.theme;
  root.dataset.accent = a.accent;
  root.dataset.density = a.density;
}

export function setAppearance(change: Partial<Appearance>) {
  current = valid({ ...current, ...change });
  try {
    window.localStorage.setItem(KEY, JSON.stringify(current));
  } catch {
    // Storage blocked: the choice lasts until the page is reloaded.
  }
  apply();
  listeners.forEach((l) => l());
}

function subscribe(l: () => void) {
  listeners.add(l);
  // Another tab changed it.
  const onStorage = (e: StorageEvent) => {
    if (e.key !== KEY) return;
    current = read();
    apply();
    listeners.forEach((x) => x());
  };
  window.addEventListener("storage", onStorage);
  return () => {
    listeners.delete(l);
    window.removeEventListener("storage", onStorage);
  };
}

export function useAppearance(): Appearance {
  return useSyncExternalStore(subscribe, () => current, () => DEFAULTS);
}

const darkQuery = () => window.matchMedia?.("(prefers-color-scheme: dark)");

function subscribeScheme(l: () => void) {
  const q = darkQuery();
  q?.addEventListener("change", l);
  const off = subscribe(l);
  return () => {
    q?.removeEventListener("change", l);
    off();
  };
}

/** Whether the page is light or dark now, for widgets that need to be told (the schema diagram). */
export function useColourScheme(): "light" | "dark" {
  return useSyncExternalStore(
    subscribeScheme,
    () => {
      const t = current.theme;
      if (t === "light" || t === "dark") return t;
      return darkQuery()?.matches ? "dark" : "light";
    },
    () => "light",
  );
}
