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
  // Names compare ignoring case, as the server does.
  const chosen = useMemo(() => new Set(splitList(value).map((n) => n.toLowerCase())), [value]);
  const isChosen = (name: string) => chosen.has(name.toLowerCase());
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
  // The setting is a comma-separated list, so a name holding a comma or semicolon cannot be chosen.
  const listable = (name: string) => !/[,;]/.test(name);
  /** The chosen names as written, keeping the case of the list where it has them. */
  const update = (change: (names: Map<string, string>) => void) => {
    const names = new Map(splitList(value).map((n) => [n.toLowerCase(), n] as [string, string]));
    change(names);
    onChange([...names.values()].join(","));
  };
  const toggle = (name: string, on: boolean) =>
    update((names) => (on ? names.set(name.toLowerCase(), name) : names.delete(name.toLowerCase())));
  const tickShown = (on: boolean) => {
    if (!on && !filter.trim()) return onChange(""); // also clears names no longer in the source
    update((names) =>
      shown.filter((c) => listable(c.name)).forEach((c) =>
        on ? names.set(c.name.toLowerCase(), c.name) : names.delete(c.name.toLowerCase()),
      ),
    );
  };
  const display = (name: string) => (name === "/" ? "Top level (files not in a folder)" : name);
  const missing = splitList(value).filter(
    (n) => list && !list.containers.some((c) => c.name.toLowerCase() === n.toLowerCase()),
  );

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
                      checked={isChosen(c.name)}
                      disabled={!listable(c.name)}
                      aria-describedby={listable(c.name) ? undefined : `${box}-why`}
                      onChange={(e) => toggle(c.name, e.target.checked)}
                    />
                    <label htmlFor={box}>
                      <span className="mono">{display(c.name)}</span>{" "}
                      <span className="muted">
                        ({formatNumber(c.count)} {list.counted})
                      </span>
                      {!listable(c.name) && (
                        <span id={`${box}-why`} className="small">
                          {" "}
                          Cannot be chosen: its name holds a comma or semicolon. Leave every box empty to scan it
                          with the rest, or rename it.
                        </span>
                      )}
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
