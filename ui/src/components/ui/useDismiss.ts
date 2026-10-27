import { useEffect, type RefObject } from "react";

/**
 * Closes a popover, menu or drawer on Escape (focus goes back to its trigger) and on a click or
 * focus outside both the panel and the trigger.
 */
export function useDismiss(
  open: boolean,
  panel: RefObject<HTMLElement | null>,
  trigger: RefObject<HTMLElement | null>,
  close: (returnFocus: boolean) => void,
) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape" || e.defaultPrevented) return;
      e.preventDefault();
      close(true);
    };
    const outside = (e: Event) => {
      const t = e.target as Node | null;
      if (!t || panel.current?.contains(t) || trigger.current?.contains(t)) return;
      close(false);
    };
    document.addEventListener("keydown", onKey);
    document.addEventListener("mousedown", outside);
    document.addEventListener("focusin", outside);
    return () => {
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("mousedown", outside);
      document.removeEventListener("focusin", outside);
    };
  }, [open, panel, trigger, close]);
}
