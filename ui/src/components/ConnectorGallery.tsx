import { useId, useMemo, useRef, useState, type KeyboardEvent } from "react";
import type { Capability, Connector, ConnectorCatalogue, PlannedConnector } from "../api";
import { Icon } from "./Icon";

const CAPABILITY: Record<Capability, string> = {
  discover: "Discover",
  read: "Read",
  write: "Write",
  stream: "Stream",
};

type Card =
  | { built: true; connector: Connector; name: string; category: string; family: string; capabilities: Capability[] }
  | { built: false; planned: PlannedConnector; name: string; category: string; family: string; capabilities: Capability[] };

/**
 * "Add connection": tabs by category (the ARIA tabs pattern: arrow keys move between tabs, Tab
 * goes into the panel), a search box whose result count is announced, family subheadings, and a
 * card per connector. Cards are buttons; planned ones say "Planned · wave N", are announced as
 * unavailable and do nothing. Icons are generic, never vendor logos.
 */
export function ConnectorGallery({
  catalogue,
  chosen,
  onChoose,
}: {
  catalogue: ConnectorCatalogue;
  chosen: string | null;
  onChoose: (connector: Connector, card: HTMLButtonElement) => void;
}) {
  const id = useId();
  const tabs = [{ id: "all", label: "All" }, ...catalogue.categories.map((c) => ({ id: c.id, label: c.label }))];
  const [tab, setTab] = useState("all");
  const [query, setQuery] = useState("");
  const tabRefs = useRef<Record<string, HTMLButtonElement | null>>({});

  const cards = useMemo<Card[]>(
    () =>
      catalogue.categories.flatMap((cat) => [
        ...cat.connectors.map(
          (c): Card => ({
            built: true,
            connector: c,
            name: c.displayName,
            category: cat.id,
            family: c.family,
            capabilities: c.capabilities,
          }),
        ),
        ...cat.planned.map(
          (p): Card => ({
            built: false,
            planned: p,
            name: p.name,
            category: cat.id,
            family: p.family,
            capabilities: p.capabilities,
          }),
        ),
      ]),
    [catalogue],
  );

  const q = query.trim().toLowerCase();
  const shown = cards.filter(
    (c) =>
      (tab === "all" || c.category === tab) &&
      (!q ||
        c.name.toLowerCase().includes(q) ||
        c.family.toLowerCase().includes(q) ||
        (c.built && c.connector.formats.some((f) => f.includes(q)))),
  );
  const built = shown.filter((c) => c.built).length;

  const onTabKey = (e: KeyboardEvent, index: number) => {
    const to =
      e.key === "ArrowRight" ? (index + 1) % tabs.length
      : e.key === "ArrowLeft" ? (index - 1 + tabs.length) % tabs.length
      : e.key === "Home" ? 0
      : e.key === "End" ? tabs.length - 1
      : -1;
    if (to < 0) return;
    e.preventDefault();
    setTab(tabs[to].id);
    tabRefs.current[tabs[to].id]?.focus();
  };

  const groups = (tab === "all" ? catalogue.categories.map((c) => c.id) : [tab]).map((cat) => ({
    cat,
    label: catalogue.categories.find((c) => c.id === cat)?.label ?? cat,
    families: [...new Set(shown.filter((c) => c.category === cat).map((c) => c.family))],
  }));

  return (
    <div className="gallery">
      <div className="field">
        <label htmlFor={`${id}-search`}>Search connectors</label>
        <input
          id={`${id}-search`}
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          autoComplete="off"
          placeholder="postgres, parquet, bucket…"
        />
      </div>
      <p role="status" aria-live="polite" className="small">
        {shown.length} {shown.length === 1 ? "connector" : "connectors"}
        {q ? ` match "${query.trim()}"` : ""}, {built} available now.
      </p>
      <div role="tablist" aria-label="Connector categories" className="tabs">
        {tabs.map((t, i) => (
          <button
            key={t.id}
            ref={(el) => {
              tabRefs.current[t.id] = el;
            }}
            type="button"
            role="tab"
            id={`${id}-tab-${t.id}`}
            aria-selected={tab === t.id}
            aria-controls={`${id}-panel`}
            tabIndex={tab === t.id ? 0 : -1}
            onClick={() => setTab(t.id)}
            onKeyDown={(e) => onTabKey(e, i)}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`${id}-panel`} aria-labelledby={`${id}-tab-${tab}`} className="tabpanel">
        {shown.length === 0 && <p>No connector matches. Try another word, or the All tab.</p>}
        {groups.map(
          (g) =>
            g.families.length > 0 && (
              <section key={g.cat} aria-labelledby={`${id}-cat-${g.cat}`}>
                {tab === "all" ? (
                  <h3 id={`${id}-cat-${g.cat}`} className="gallery-category">
                    <Icon name={g.cat} size={20} /> {g.label}
                  </h3>
                ) : (
                  <h3 id={`${id}-cat-${g.cat}`} className="visually-hidden">
                    {g.label}
                  </h3>
                )}
                {g.families.map((family) => (
                  <div key={family}>
                    <h4 className="gallery-family">{family}</h4>
                    <ul className="cards">
                      {shown
                        .filter((c) => c.category === g.cat && c.family === family)
                        .map((c) => (
                          <li key={c.name}>
                            {c.built ? (
                              <button
                                type="button"
                                className="card"
                                aria-pressed={chosen === c.connector.kind}
                                onClick={(e) => onChoose(c.connector, e.currentTarget)}
                              >
                                <Icon name={c.connector.icon} />
                                <span className="card-name">{c.name}</span>
                                <Badges caps={c.capabilities} formats={c.connector.formats} />
                              </button>
                            ) : (
                              <button
                                type="button"
                                className="card planned"
                                aria-disabled="true"
                                aria-describedby={`${id}-unavailable`}
                                onClick={(e) => e.preventDefault()}
                              >
                                <Icon name={c.category} />
                                <span className="card-name">{c.name}</span>
                                <span className="planned-label">Planned · wave {c.planned.wave}</span>
                                <Badges caps={c.capabilities} formats={[]} />
                              </button>
                            )}
                          </li>
                        ))}
                    </ul>
                  </div>
                ))}
              </section>
            ),
        )}
        <p id={`${id}-unavailable`} className="visually-hidden">
          Not available yet.
        </p>
      </div>
    </div>
  );
}

function Badges({ caps, formats }: { caps: Capability[]; formats: string[] }) {
  return (
    <span className="badges">
      {caps.map((c) => (
        <span key={c} className="tag">
          {CAPABILITY[c]}
        </span>
      ))}
      {formats.length > 0 && <span className="small muted card-formats">{formats.join(", ")}</span>}
    </span>
  );
}
