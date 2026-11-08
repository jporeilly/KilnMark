import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from "react";
import type { Capability, Connector, ConnectorCatalogue, PlannedConnector } from "../api";
import { plural } from "../api";
import { ConnectorTile, plannedTileProps, tileProps } from "./ConnectorTile";
import { href } from "../router";
import { Icon } from "./icons";

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
 * unavailable and do nothing. Each card has the connector's tile: letters on a tint, or the
 * operator's own logo from the branding folder; never a vendor logo shipped with Kilnmark.
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
  const countText = `${plural(shown.length, "connector")}${
    q ? ` match "${query.trim()}"` : ""
  }, ${built} available now.`;
  // Announced once typing pauses, not at every key.
  const [announced, setAnnounced] = useState(countText);
  useEffect(() => {
    const t = window.setTimeout(() => setAnnounced(countText), 600);
    return () => window.clearTimeout(t);
  }, [countText]);

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

  // Within a category, one grid: available connectors first, then planned ones; the family is a
  // label on each card, so a family with one connector does not take a row of its own.
  const groups = (tab === "all" ? catalogue.categories.map((c) => c.id) : [tab]).map((cat) => ({
    cat,
    label: catalogue.categories.find((c) => c.id === cat)?.label ?? cat,
    cards: shown.filter((c) => c.category === cat).sort((a, b) => Number(b.built) - Number(a.built)),
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
      <p className="small" aria-hidden="true">
        {countText}
      </p>
      <p role="status" aria-live="polite" className="visually-hidden">
        {announced}
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
            g.cards.length > 0 && (
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
                <ul className="cards">
                  {g.cards.map((c) => (
                    <li key={c.name} className={c.built && needsDriver(c.connector) ? "card-with-link" : undefined}>
                      {c.built ? (
                        <button
                          type="button"
                          className="card"
                          aria-pressed={chosen === c.connector.kind}
                          onClick={(e) => onChoose(c.connector, e.currentTarget)}
                        >
                          <ConnectorTile {...tileProps(c.connector)} size="lg" />
                          <span className="card-text">
                            <span className="card-name">{c.name}</span>
                            <span className="card-family">{c.family}</span>
                            {needsDriver(c.connector) && <span className="planned-label">Needs its driver</span>}
                          </span>
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
                          <ConnectorTile {...plannedTileProps(c.planned)} size="lg" />
                          <span className="card-text">
                            <span className="card-name">{c.name}</span>
                            <span className="card-family">{c.family}</span>
                            <span className="planned-label">
                              Planned · {/^\d/.test(c.planned.wave) ? `wave ${c.planned.wave}` : c.planned.wave}
                            </span>
                          </span>
                          <Badges caps={c.capabilities} formats={[]} />
                        </button>
                      )}
                      {c.built && needsDriver(c.connector) && (
                        <a className="small card-driver-link" href={href.drivers()}>
                          Install the {c.name} driver<span className="visually-hidden"> (admins, Settings › Drivers)</span>
                        </a>
                      )}
                    </li>
                  ))}
                </ul>
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

/** Whether a connector's driver, which Kilnmark cannot ship, still has to be installed. */
export function needsDriver(c: Connector) {
  return c.driver !== undefined && c.driver !== "bundled" && c.driver !== "ready";
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
