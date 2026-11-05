import { forwardRef, useCallback, useId, useRef, useState, type FormEvent, type ReactNode, type RefObject } from "react";
import type { Me } from "../api";
import { Icon } from "../components/icons";
import { ThemeQuickSwitch } from "../components/ThemeQuickSwitch";
import { useDismiss } from "../components/ui";
import { href } from "../router";

/** The search box (Ctrl+K or / from anywhere). Enter searches; on the Ask page it asks. */
export function SearchForm({
  query,
  setQuery,
  onSubmit,
  inputRef,
}: {
  query: string;
  setQuery: (q: string) => void;
  onSubmit: (e: FormEvent) => void;
  inputRef: RefObject<HTMLInputElement | null>;
}) {
  return (
    <form role="search" className="searchbar" onSubmit={onSubmit}>
      <label htmlFor="q" className="visually-hidden">
        Search the catalogue
      </label>
      <button type="submit" className="searchbar-go" aria-label="Search">
        <Icon name="search" size={18} />
      </button>
      <input
        id="q"
        ref={inputRef}
        type="search"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="Search tables, files and columns"
        autoComplete="off"
        aria-describedby="q-hint"
      />
      <span id="q-hint" className="kbd-hint">
        <span className="visually-hidden">Shortcut: </span>
        <kbd>Ctrl</kbd> <kbd>K</kbd>
      </span>
    </form>
  );
}

/** The "Ask Kilnmark" button: opens the assistant panel (Alt+A). */
export const AskButton = forwardRef<HTMLButtonElement, { open: boolean; onClick: () => void }>(function AskButton(
  { open, onClick },
  ref,
) {
  return (
    <button
      type="button"
      ref={ref}
      className="km-button ask ask-button"
      aria-expanded={open}
      aria-controls={open ? "assistant" : undefined}
      aria-keyshortcuts="Alt+A"
      title="Ask a question about Kilnmark (Alt+A)"
      onClick={onClick}
    >
      <Icon name="ask" size={16} />
      <span className="ask-label">Ask Kilnmark</span>
    </button>
  );
});

/** Two letters for the avatar: the first of two words ("Ana Silva": AS), or the first two of one ("Jp": JP). */
const initials = (name: string) => {
  const parts = name.split(/[\s._-]+/).filter(Boolean);
  const letters = parts.length > 1 ? parts[0]![0]! + parts[1]![0]! : (parts[0] ?? "?").slice(0, 2);
  return letters.toUpperCase();
};

/**
 * Who is signed in: an avatar with initials, the name and role, and a small menu with the theme,
 * Settings and Sign out. Escape or a click outside closes it; focus returns to the button.
 */
export function UserMenu({ me, extra }: { me: Me; extra?: ReactNode }) {
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const id = useId();
  const close = useCallback((returnFocus: boolean) => {
    setOpen(false);
    if (returnFocus) button.current?.focus();
  }, []);
  useDismiss(open, panel, button, close);
  const shown = me.displayName || me.name;
  return (
    <div className="km-popover-wrap who">
      <button
        ref={button}
        type="button"
        className="user-button"
        aria-expanded={open}
        aria-controls={open ? `${id}-menu` : undefined}
        onClick={() => setOpen(!open)}
      >
        <span className="avatar" aria-hidden="true">
          {initials(shown)}
        </span>
        <span className="user-name">
          {me.signInRequired ? <span className="visually-hidden">Signed in as </span> : null}
          <strong>{shown}</strong> <span className="user-role">· {me.role}</span>
        </span>
        <Icon name="chevron-down" size={16} />
      </button>
      {open && (
        <div ref={panel} id={`${id}-menu`} className="km-popover km-popover--menu" role="group" aria-label="Your account">
          <p className="km-popover-who">
            <strong>{shown}</strong>
            <span className="muted"> · {me.role}</span>
            {shown !== me.name && (
              <span className="muted km-popover-recorded" title="The name Kilnmark records your changes under">
                {me.name}
              </span>
            )}
          </p>
          <div className="km-popover-row">
            <ThemeQuickSwitch />
          </div>
          {extra}
          <a className="km-menu-link" href={href.settings()} onClick={() => close(false)}>
            <Icon name="settings" size={16} /> Settings
          </a>
          {me.signInRequired && (
            <a className="km-menu-link" href={href.signOut()} onClick={() => close(false)}>
              <Icon name="sign-out" size={16} /> Sign out
            </a>
          )}
        </div>
      )}
    </div>
  );
}
