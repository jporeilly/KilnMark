import { useEffect, useSyncExternalStore } from "react";
import { isMissing, notificationsApi, type AppNotification } from "./api";

/**
 * One shared store for the bell and the home card, so marking read in one shows in the other.
 * `available` is null while unknown, false when the server has no notifications (404: hidden
 * entirely), true otherwise. The unread count is polled every 60 seconds while something shows it.
 */
type State = {
  available: boolean | null;
  items: AppNotification[] | null;
  unread: number;
  error: string | null;
};

let state: State = { available: null, items: null, unread: 0, error: null };
const listeners = new Set<() => void>();
const set = (change: Partial<State>) => {
  state = { ...state, ...change };
  listeners.forEach((l) => l());
};

let loading: Promise<void> | null = null;

/** Loads the latest six (and the unread count with them). */
export function refreshNotifications(): Promise<void> {
  if (state.available === false) return Promise.resolve();
  loading ??= notificationsApi
    .list(6)
    .then((p) => set({ available: true, items: p.items, unread: p.unread, error: null }))
    .catch((e: Error) => (isMissing(e) ? set({ available: false }) : set({ error: e.message })))
    .finally(() => {
      loading = null;
    });
  return loading;
}

async function pollUnread() {
  if (state.available !== true) return;
  try {
    const { unread } = await notificationsApi.unreadCount();
    // Something new or read elsewhere: fetch the list again so both views match the count.
    if (unread !== state.unread) await refreshNotifications();
  } catch (e) {
    if (isMissing(e)) set({ available: false });
  }
}

/** Marks the given notifications, or all of them, read; the views update at once. */
export async function markNotificationsRead(ids: string[] | "all"): Promise<void> {
  const before = state;
  const items = state.items?.map((n) => (ids === "all" || ids.includes(n.id) ? { ...n, read: true } : n)) ?? null;
  set({ items, unread: items ? items.filter((n) => !n.read).length : 0 });
  try {
    await notificationsApi.markRead(ids);
    await refreshNotifications();
  } catch (e) {
    set({ ...before, error: (e as Error).message });
    throw e;
  }
}

let watchers = 0;
let timer: number | undefined;

export function useNotifications(): State {
  useEffect(() => {
    watchers++;
    if (state.items === null) void refreshNotifications();
    if (watchers === 1) timer = window.setInterval(pollUnread, 60_000);
    return () => {
      watchers--;
      if (watchers === 0) window.clearInterval(timer);
    };
  }, []);
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => state,
    () => state,
  );
}
