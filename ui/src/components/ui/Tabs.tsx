import { useId, useRef, type KeyboardEvent, type ReactNode } from "react";

export type TabItem = { id: string; label: ReactNode; count?: number };

/**
 * Tabs (the ARIA tabs pattern): arrow keys, Home and End move between tabs and select them; Tab
 * moves into the panel. The selected tab has an accent underline. `idBase` ties tabs to the panel
 * rendered by <TabPanel>. Pages can keep the selected tab in the address (the asset page does).
 */
export function Tabs({
  tabs,
  selected,
  onSelect,
  label,
  idBase,
}: {
  tabs: TabItem[];
  selected: string;
  onSelect: (id: string) => void;
  label: string;
  idBase: string;
}) {
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});
  const onKey = (e: KeyboardEvent, index: number) => {
    const to =
      e.key === "ArrowRight" ? (index + 1) % tabs.length
      : e.key === "ArrowLeft" ? (index - 1 + tabs.length) % tabs.length
      : e.key === "Home" ? 0
      : e.key === "End" ? tabs.length - 1
      : -1;
    if (to < 0) return;
    e.preventDefault();
    onSelect(tabs[to].id);
    refs.current[tabs[to].id]?.focus();
  };
  return (
    <div role="tablist" aria-label={label} className="tabs km-tabs">
      {tabs.map((t, i) => (
        <button
          key={t.id}
          ref={(el) => {
            refs.current[t.id] = el;
          }}
          type="button"
          role="tab"
          id={`${idBase}-tab-${t.id}`}
          aria-selected={selected === t.id}
          aria-controls={`${idBase}-panel`}
          tabIndex={selected === t.id ? 0 : -1}
          onClick={() => onSelect(t.id)}
          onKeyDown={(e) => onKey(e, i)}
        >
          {t.label}
          {t.count != null && <span className="km-tab-count num">{t.count}</span>}
        </button>
      ))}
    </div>
  );
}

export function TabPanel({ idBase, selected, children }: { idBase: string; selected: string; children: ReactNode }) {
  return (
    <div role="tabpanel" id={`${idBase}-panel`} aria-labelledby={`${idBase}-tab-${selected}`} className="km-tabpanel">
      {children}
    </div>
  );
}

/** A stable id base for a Tabs and TabPanel pair. */
export const useTabsId = () => useId().replace(/:/g, "");
