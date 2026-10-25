import { useEffect, useSyncExternalStore } from "react";
import { api, type Branding, type Connector } from "../api";

/**
 * Shared, load-once stores for how things look: the operator's branding (public, read before
 * sign-in) and the connectors' tile hints (monogram, icon), so every list and tree shows the same
 * tile for a kind of source without each page asking the server.
 */

const EMPTY: Branding = { productName: null, logo: null, connectors: {} };

function store<T>(initial: T, load: () => Promise<T>) {
  let value = initial;
  let started = false;
  const listeners = new Set<() => void>();
  const start = () => {
    if (started) return;
    started = true;
    load()
      .then((v) => {
        value = v;
        listeners.forEach((l) => l());
      })
      .catch(() => {
        // Not available (offline, or not signed in yet): keep the built-in look, try again later.
        started = false;
      });
  };
  return {
    start,
    use(): T {
      useEffect(start, []);
      return useSyncExternalStore(
        (l) => {
          listeners.add(l);
          return () => listeners.delete(l);
        },
        () => value,
        () => initial,
      );
    },
  };
}

const branding = store<Branding>(EMPTY, () => api.branding());
const connectors = store<Record<string, Connector>>({}, () =>
  api.connectors().then((list) => Object.fromEntries(list.map((c) => [c.kind, c]))),
);

/** The operator's branding: product name and logo, and connector looks. Empty until loaded, or when none is set. */
export const useBranding = branding.use;

/** Connectors by kind, for their tile hints. Needs a signed-in viewer. */
export const useConnectorInfo = connectors.use;
