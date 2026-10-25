import type { ReactNode } from "react";

export type FilterOption = { id: string; label: ReactNode; count?: number; tone?: "violet" | "sky" | "green" | "amber" | "red" | "orange" | "teal" };

/**
 * A row of filter chips: toggle buttons (aria-pressed) in a labelled group, one selected at a
 * time. The selected chip is dark; others keep their tint, so groups can be told apart, with the
 * name and count always in words.
 */
export function FilterChips({
  label,
  options,
  selected,
  onSelect,
}: {
  label: string;
  options: FilterOption[];
  selected: string;
  onSelect: (id: string) => void;
}) {
  return (
    <div role="group" aria-label={label} className="km-filters">
      {options.map((o) => (
        <button
          key={o.id}
          type="button"
          className={`km-filter${o.tone ? ` km-filter--${o.tone}` : ""}`}
          aria-pressed={selected === o.id}
          onClick={() => onSelect(o.id)}
        >
          {o.label}
          {o.count != null && <span className="num"> {o.count}</span>}
        </button>
      ))}
    </div>
  );
}
