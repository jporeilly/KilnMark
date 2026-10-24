import { useId, useMemo, useState } from "react";
import { formatNumber, type ContainerList } from "../api";

/** The names in a comma- or semicolon-separated setting. */
export const splitList = (value: string | undefined) =>
  (value ?? "")
    .split(/[,;]/)
    .map((s) => s.trim())
    .filter((s) => s !== "");

/**
 * Choose which containers (schemas, top-level folders) to scan from what the source holds, with
 * counts. A real list of labelled checkboxes: Tab reaches the filter, the select buttons and each
 * box; Space ticks. Nothing ticked means everything is scanned. `load` asks the server.
 */
export function ContainerPicker({
  label,
  value,
  onChange,
  load,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  load: () => Promise<ContainerList>;
}) {
  const id = useId();
  const [list, setList] = useState<ContainerList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [filter, setFilter] = useState("");
  const chosen = useMemo(() => new Set(splitList(value)), [value]);
  const lower = label.toLowerCase();

  const fetchList = async () => {
    setLoading(true);
    setError(null);
    try {
      setList(await load());
    } catch (e) {
      setList(null);
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  };

  const shown = (list?.containers ?? []).filter((c) => c.name.toLowerCase().includes(filter.trim().toLowerCase()));
  const set = (names: Set<string>) => onChange([...names].join(","));
  const toggle = (name: string, on: boolean) => {
    const next = new Set(chosen);
    if (on) next.add(name);
    else next.delete(name);
    set(next);
  };
  const tickShown = (on: boolean) => {
    if (!on && !filter.trim()) return set(new Set()); // also clears names no longer in the source
    const next = new Set(chosen);
    shown.forEach((c) => (on ? next.add(c.name) : next.delete(c.name)));
    set(next);
  };
  const display = (name: string) => (name === "/" ? "Top level (files not in a folder)" : name);
  const missing = [...chosen].filter((n) => list && !list.containers.some((c) => c.name.toLowerCase() === n.toLowerCase()));

  return (
    <div className="picker">
      <div className="buttons">
        <button type="button" onClick={fetchList} disabled={loading} aria-describedby={`${id}-why`}>
          {loading ? `Listing ${lower}…` : list ? `List ${lower} again` : `Choose ${lower} from the source`}
        </button>
      </div>
      <p id={`${id}-why`} className="hint">
        Connects to the source with the settings above and lists its {lower}, with how many{" "}
        {list?.counted ?? "tables or files"} each holds.
      </p>
      <div role="status" aria-live="polite" className="small">
        {list &&
          `${list.containers.length} ${lower} found. ${chosen.size === 0 ? `None ticked: every one is scanned.` : `${chosen.size} ticked.`}`}
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {list && (
        <fieldset className="picker-list">
          <legend>{label} to scan</legend>
          <div className="field-row">
            <div className="field">
              <label htmlFor={`${id}-filter`}>Filter {lower}</label>
              <input
                id={`${id}-filter`}
                type="search"
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
                autoComplete="off"
                spellCheck={false}
              />
            </div>
          </div>
          <div className="buttons">
            <button type="button" onClick={() => tickShown(true)} disabled={shown.length === 0}>
              Select all{filter.trim() ? " shown" : ""}
            </button>
            <button type="button" onClick={() => tickShown(false)} disabled={shown.length === 0}>
              Select none{filter.trim() ? " shown" : ""}
            </button>
          </div>
          {shown.length === 0 ? (
            <p className="hint">No {lower} match the filter.</p>
          ) : (
            <ul className="checklist">
              {shown.map((c) => {
                const box = `${id}-c-${c.name}`;
                return (
                  <li key={c.name}>
                    <input
                      type="checkbox"
                      id={box}
                      checked={chosen.has(c.name)}
                      onChange={(e) => toggle(c.name, e.target.checked)}
                    />
                    <label htmlFor={box}>
                      <span className="mono">{display(c.name)}</span>{" "}
                      <span className="muted">
                        ({formatNumber(c.count)} {list.counted})
                      </span>
                    </label>
                  </li>
                );
              })}
            </ul>
          )}
          {missing.length > 0 && (
            <p className="warning">
              Ticked before but not in the source any more: <span className="mono">{missing.join(", ")}</span>. Untick
              them with Select none, or edit the list above.
            </p>
          )}
        </fieldset>
      )}
    </div>
  );
}
