import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from "react";

/** One action; a disabled one stays focusable and says why in `note`. */
export type StepMenuItem = { id: string; label: string; onSelect: () => void; disabled?: boolean; note?: string };

/**
 * A step's context menu on the canvas, opened by a right-click or, from the keyboard, Shift+F10 or
 * the context-menu key on a step. A real menu: focus moves to its first item, the arrow keys, Home
 * and End move between items, Enter or Space chooses, and Escape, Tab or a click outside closes it
 * (focus then goes back to the step).
 */
export function StepMenu({
  step,
  at,
  items,
  onClose,
}: {
  step: string;
  at: { x: number; y: number };
  items: StepMenuItem[];
  onClose: (returnFocus: boolean) => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const [pos, setPos] = useState(at);

  // Kept inside the window, however near its edge the step was.
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const x = Math.max(8, Math.min(at.x, window.innerWidth - r.width - 8));
    const y = Math.max(8, Math.min(at.y, window.innerHeight - r.height - 8));
    setPos({ x, y });
  }, [at]);

  useEffect(() => {
    refs.current.find((b) => b != null)?.focus();
  }, []);

  useEffect(() => {
    const outside = (e: Event) => {
      if (box.current && !box.current.contains(e.target as Node)) onClose(false);
    };
    document.addEventListener("mousedown", outside);
    document.addEventListener("focusin", outside);
    return () => {
      document.removeEventListener("mousedown", outside);
      document.removeEventListener("focusin", outside);
    };
  }, [onClose]);

  const move = (from: number, by: number) => {
    const live = refs.current.map((b, i) => (b ? i : -1)).filter((i) => i >= 0);
    if (live.length === 0) return;
    const at = live.indexOf(from);
    const next = by === Infinity ? live[live.length - 1] : by === -Infinity ? live[0] : live[(at + by + live.length) % live.length];
    refs.current[next]?.focus();
  };

  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const i = refs.current.findIndex((b) => b === document.activeElement);
    const k = e.key;
    if (k === "ArrowDown" || k === "ArrowUp" || k === "Home" || k === "End") {
      e.preventDefault();
      e.stopPropagation();
      move(i, k === "ArrowDown" ? 1 : k === "ArrowUp" ? -1 : k === "Home" ? -Infinity : Infinity);
    } else if (k === "Escape" || (k === "F10" && e.shiftKey) || k === "ContextMenu") {
      e.preventDefault();
      e.stopPropagation();
      onClose(true);
    } else if (k === "Tab") {
      e.preventDefault();
      onClose(true);
    } else {
      // Letters and other keys belong to the menu while it is open, not to the canvas's shortcuts.
      e.stopPropagation();
    }
  };

  return (
    <div
      ref={box}
      className="cv-menu"
      role="menu"
      aria-label={`Actions for step ${step}`}
      style={{ left: pos.x, top: pos.y }}
      onKeyDown={onKey}
      onContextMenu={(e) => e.preventDefault()}
    >
      {items.map((it, i) => (
        <button
          key={it.id}
          ref={(el) => {
            refs.current[i] = el;
          }}
          type="button"
          role="menuitem"
          tabIndex={-1}
          aria-disabled={it.disabled || undefined}
          className="cv-menu-item"
          onClick={() => {
            if (it.disabled) return;
            onClose(false);
            it.onSelect();
          }}
        >
          <span>{it.label}</span>
          {it.note && <span className="cv-menu-note">{it.note}</span>}
        </button>
      ))}
    </div>
  );
}
