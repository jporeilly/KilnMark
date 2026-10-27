import { useEffect, useRef, type KeyboardEvent, type ReactNode } from "react";

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"]), summary';

/**
 * A side sheet: a panel that slides over the page from the right, with a scrim behind it. It is a
 * modal dialog: Tab stays inside it, the page behind does not scroll, and a click on the scrim
 * asks the owner to close it (the owner also handles Escape, so it can ask before discarding
 * changes). Focus goes back where it was when the sheet closes, unless the owner moves it.
 */
export function SideSheet({
  labelledBy,
  onKeyDown,
  onScrim,
  children,
}: {
  labelledBy: string;
  onKeyDown?: (e: KeyboardEvent<HTMLElement>) => void;
  onScrim: () => void;
  children: ReactNode;
}) {
  const panel = useRef<HTMLElement>(null);

  useEffect(() => {
    const root = document.documentElement;
    root.classList.add("km-no-scroll");
    return () => root.classList.remove("km-no-scroll");
  }, []);

  const trap = (e: KeyboardEvent<HTMLElement>) => {
    onKeyDown?.(e);
    if (e.key !== "Tab" || !panel.current) return;
    const items = [...panel.current.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((el) => el.offsetParent !== null);
    if (items.length === 0) return;
    const first = items[0];
    const last = items[items.length - 1];
    if (e.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  };

  return (
    <div className="km-sheet-layer">
      <div className="km-scrim" aria-hidden="true" onClick={onScrim} />
      <section
        ref={panel}
        className="connection-panel km-sheet km-side-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelledBy}
        onKeyDown={trap}
        tabIndex={-1}
      >
        {children}
      </section>
    </div>
  );
}
