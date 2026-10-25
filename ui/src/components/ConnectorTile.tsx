import { useState } from "react";
import type { Connector, PlannedConnector } from "../api";
import { useBranding, useConnectorInfo } from "../theme/looks";
import { useAppearance } from "../theme/appearance";
import { familyIcon, Icon } from "./icons";

/** Tints in tokens.css (sky, green, orange, violet, pink, teal, amber, red), each checked for contrast in every theme. */
const TINTS = 8;

/** The same connector always gets the same tint: FNV-1a over its id. */
export function tintFor(id: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < id.length; i++) {
    h ^= id.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return ((h >>> 0) % TINTS) + 1;
}

/** Two or three letters for a product the server gave none for: "IBM", "Sn" for Snowflake. */
export function deriveMonogram(name: string): string {
  const first = name.replace(/\(.*?\)/g, " ").split(/[\s/,]+/).find(Boolean) ?? "?";
  if (/^[A-Z0-9]{2,3}$/.test(first)) return first;
  if (/^[A-Z0-9]{4,}$/.test(first)) return first.slice(0, 2);
  return first.charAt(0).toUpperCase() + first.charAt(1).toLowerCase();
}

function luminance(hex: string) {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/**
 * Black or white letters on an operator's brand colour, whichever contrasts more. One of the two
 * always reaches 4.5:1 (the worst case, mid grey, gives 4.6:1 with black).
 */
export function lettersOn(hex: string): "#000000" | "#ffffff" {
  const l = luminance(hex);
  return (l + 0.05) / 0.05 >= 1.05 / (l + 0.05) ? "#000000" : "#ffffff";
}

export type TileSize = "sm" | "md" | "lg";

/**
 * A connector's tile: the operator's logo from the branding folder if there is a valid one, else
 * letters on a tint ("Pg", "SQL"), else, for generic connectors such as a folder, the family's
 * icon on the tint. Never a vendor logo shipped with Kilnmark.
 *
 * Decorative by default (its name is always written next to it). Pass `labelled` where the tile
 * is the only label, and it is announced by the connector's name.
 */
export function ConnectorTile({
  id,
  name,
  monogram,
  icon,
  family,
  size = "md",
  planned = false,
  labelled = false,
}: {
  /** The connector's kind, or a planned connector's id: picks the tint and the branding entry. */
  id: string;
  name: string;
  /** Letters from the server; null for a generic connector (shows `icon`); undefined to derive from the name. */
  monogram?: string | null;
  /** Icon name for the tile of a generic connector. */
  icon?: string;
  /** Icon name shown small in the corner of large letter tiles. */
  family?: string;
  size?: TileSize;
  planned?: boolean;
  labelled?: boolean;
}) {
  const branding = useBranding();
  const look = branding.connectors[id];
  // High contrast keeps its own black and white tiles: operator brand colours are not used there.
  const colour = useAppearance().theme === "contrast" ? null : look?.color ?? null;
  // The operator's label names the logo: the tile's accessible name where it stands alone, and a tooltip.
  const label = look?.label ?? null;
  const [broken, setBroken] = useState<string | null>(null);
  const a11y = labelled
    ? { role: "img" as const, "aria-label": label ?? name, title: label ?? name }
    : { "aria-hidden": true as const, ...(label ? { title: label } : {}) };
  const base = `tile tile-${size}${planned ? " tile-planned" : ""}`;

  if (look?.image && broken !== look.image) {
    return (
      <span
        className={`${base} tile-image`}
        style={colour ? { background: colour } : undefined}
        {...a11y}
      >
        {/* An <img>, never inline SVG: nothing in an operator's file can run. */}
        <img src={look.image} alt="" onError={() => setBroken(look.image)} draggable={false} />
      </span>
    );
  }

  const letters = monogram === null && icon ? null : (monogram ?? deriveMonogram(name)).slice(0, 3);
  // A planned connector without a logo keeps its neutral "not yet" tile; its colour is not used.
  const brand = colour && !planned ? { background: colour, color: lettersOn(colour) } : undefined;
  const tint = brand ? " tile-brand" : planned ? "" : ` tile-tint-${tintFor(id)}`;
  return (
    <span className={`${base}${tint}${letters ? ` letters-${letters.length}` : ""}`} style={brand} {...a11y}>
      {letters ? (
        <>
          <span className="tile-letters">{letters}</span>
          {size === "lg" && family && <Icon name={family} size={12} className="tile-family" />}
        </>
      ) : (
        <Icon name={icon ?? "other"} size={size === "lg" ? 22 : size === "md" ? 16 : 14} />
      )}
    </span>
  );
}

/** The tile for a built connector, from what the server says about it. */
export function tileProps(c: Connector) {
  return { id: c.kind, name: c.displayName, monogram: c.monogram, icon: c.icon, family: familyIcon(c.family, c.category) };
}

/** The tile for a planned connector. */
export function plannedTileProps(p: PlannedConnector) {
  const family = familyIcon(p.family, p.category);
  return { id: p.id, name: p.name, monogram: p.monogram, icon: family, family, planned: true };
}

/** The tile for a source, by its kind: the connector's own hints once they have loaded. */
export function SourceTile({ kind, size = "md", labelled }: { kind: string; size?: TileSize; labelled?: boolean }) {
  const info = useConnectorInfo()[kind];
  if (info) return <ConnectorTile {...tileProps(info)} size={size} labelled={labelled} />;
  // Before the hints arrive (or for a kind this server no longer has): a neutral icon tile, no guessing.
  const icon = kind === "files" ? "folder" : kind === "s3" ? "bucket" : "database";
  return <ConnectorTile id={kind} name={kind} monogram={null} icon={icon} size={size} labelled={labelled} />;
}
