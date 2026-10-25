// Checks the colour tokens in src/tokens.css against WCAG 2.2 AA for every theme and accent:
// text 4.5:1 (7:1 for the main text colours in high contrast), and 3:1 for borders of controls,
// focus rings, indicators and bar fills against their track (1.4.11). Covers the page and cards,
// the dark sidebar and hero banner, status text, chips and tiles (tinted backgrounds), chart and bar
// fills, and each accent preset's indicator, button fill, link colour and tint. Tokens may refer to
// other tokens with var(); they are resolved within the theme. Also checks that the dark set's two
// copies (chosen Dark, and System when the OS is dark) agree. Run by `npm run build`, or alone with
// `npm run check:contrast`.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const css = readFileSync(fileURLToPath(new URL("../src/tokens.css", import.meta.url)), "utf8").replace(
  /\/\*[\s\S]*?\*\//g,
  "",
);

/** Top-level and @media rules as { media, selector, vars }. */
function rules(text) {
  const out = [];
  let i = 0;
  const walk = (media) => {
    while (i < text.length) {
      const open = text.indexOf("{", i);
      const close = text.indexOf("}", i);
      if (close !== -1 && (open === -1 || close < open)) {
        i = close + 1;
        return;
      }
      if (open === -1) return;
      const head = text.slice(i, open).trim();
      i = open + 1;
      if (head.startsWith("@media")) {
        walk(head);
      } else {
        const end = text.indexOf("}", i);
        const body = text.slice(i, end);
        i = end + 1;
        const vars = {};
        for (const m of body.matchAll(/(--km-[\w-]+)\s*:\s*([^;]+);/g)) vars[m[1]] = m[2].trim();
        out.push({ media: media ?? "", selector: head.replace(/\s+/g, " "), vars });
      }
    }
  };
  walk(null);
  return out;
}

const all = rules(css);
const find = (selector, media = "") => {
  const r = all.find((x) => x.selector === selector && x.media === media && x.vars["--km-ink"]);
  if (!r) throw new Error(`tokens.css: no colour set for ${media} ${selector}`);
  return r.vars;
};
const DARK = "@media (prefers-color-scheme: dark)";
const themes = {
  light: find(":root"),
  dark: find(':root[data-theme="dark"]'),
  "contrast (light)": find(':root[data-theme="contrast"]'),
  "contrast (dark)": find(':root[data-theme="contrast"]', DARK),
};
const failures = [];
const darkSystem = find(':root:not([data-theme]), :root[data-theme="system"]', DARK);
if (JSON.stringify(darkSystem) !== JSON.stringify(themes.dark)) {
  failures.push("the dark set for System differs from the chosen dark set: keep the two copies the same");
}

// The accent selections (:root and :root[data-accent=...]): each maps --km-accent, -fill, -ink,
// -tint, -on-dark and --km-link to one preset's tokens.
const accents = all
  .filter((r) => r.vars["--km-accent"] && r.selector.startsWith(":root"))
  .map((r) => ({ name: r.vars["--km-accent"].match(/var\(--km-accent-([\w]+)\)/)[1], vars: r.vars }));

function luminance(hex) {
  const h = hex.replace("#", "");
  if (!/^[0-9a-f]{6}$/i.test(h)) throw new Error(`not a #rrggbb colour: ${hex}`);
  const [r, g, b] = [0, 2, 4].map((k) => {
    const c = parseInt(h.slice(k, k + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
const ratio = (a, b) => {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
};

const TINTS = ["sky", "blue", "violet", "green", "amber", "orange", "red", "pink", "teal", "slate"];
const FILLS = ["--km-fill-sky", "--km-fill-violet", "--km-fill-green", "--km-fill-amber", "--km-fill-red"];
const NAV = ["sky", "mint", "violet", "pink", "green", "red", "amber", "slate"].map((n) => `--km-nav-${n}`);

let checked = 0;
for (const [name, base] of Object.entries(themes)) {
  const hc = name.startsWith("contrast");
  for (const accent of accents) {
    // The theme's tokens with this accent selected, every var() resolved.
    const raw = { ...base, ...accent.vars };
    const value = (token, seen = new Set()) => {
      const v = raw[token];
      if (v == null) throw new Error(`${name}: ${token} is not defined`);
      const ref = v.match(/^var\((--km-[\w-]+)\)$/);
      if (!ref) return v;
      if (seen.has(token)) throw new Error(`${name}: ${token} refers to itself`);
      return value(ref[1], new Set([...seen, token]));
    };
    const label = accents.length > 1 ? `${name}, ${accent.name} accent` : name;
    const need = (fg, bg, min, why) => {
      checked++;
      const r = ratio(value(fg), value(bg));
      if (r < min) failures.push(`${label}: ${fg} on ${bg} is ${r.toFixed(2)}:1, needs ${min}:1 (${why})`);
    };
    // Accent-dependent pairs, for every accent.
    for (const bg of ["--km-surface", "--km-ground", "--km-surface-2"]) {
      need("--km-link", bg, hc ? 7 : 4.5, "links and accent text");
      need("--km-accent", bg, 3, "accent indicators");
    }
    need("--km-link", "--km-accent-tint", 4.5, "accent text on a selected row");
    need("--km-ink", "--km-accent-tint", hc ? 7 : 4.5, "text on a selected row");
    need("--km-accent", "--km-track", 3, "accent bar fill vs track");
    need("--km-on-accent", "--km-accent-fill", 4.5, "text on main buttons");
    need("--km-accent-fill", "--km-surface", 3, "main button edge");
    need("--km-accent-on-dark", "--km-side-bg", 3, "active item icon in the sidebar");
    need("--km-accent-on-dark", "--km-side-active", 3, "active item icon in the sidebar");
    need("--km-accent-on-dark", "--km-side-bg", 4.5, "What's new link in the sidebar");
    need("--km-accent-on-dark", "--km-hero-bg", 3, "hero accents");
    if (accent !== accents[0]) continue;
    // Pairs that do not depend on the accent, once per theme.
    for (const bg of ["--km-surface", "--km-ground", "--km-surface-2"]) {
      need("--km-ink", bg, hc ? 7 : 4.5, "text");
      need("--km-ink-strong", bg, hc ? 7 : 4.5, "headings and numbers");
      need("--km-ink-soft", bg, hc ? 7 : 4.5, "secondary text");
      need("--km-muted", bg, hc ? 7 : 4.5, "muted text");
      for (const s of ["--km-good", "--km-warn", "--km-bad", "--km-info"]) need(s, bg, 4.5, "status text");
      need("--km-border-control", bg, 3, "borders of controls");
      need("--km-focus", bg, 3, "focus ring");
    }
    need("--km-ink", "--km-surface-3", hc ? 7 : 4.5, "text on a hovered or code background");
    need("--km-muted", "--km-surface-3", 4.5, "muted text on a hovered background");
    for (const fill of FILLS) {
      need(fill, "--km-track", 3, "bar and chart fill vs track");
      need(fill, "--km-surface", 3, "chart line vs card");
    }
    for (const fill of ["--km-good", "--km-warn", "--km-bad"]) need(fill, "--km-track", 3, "status bar fill vs track");
    for (const t of TINTS) {
      need(`--km-tint-${t}-fg`, `--km-tint-${t}-bg`, 4.5, "chip, icon square and tile letters");
    }
    // The dark sidebar.
    for (const bg of ["--km-side-bg", "--km-side-active", "--km-side-hover"]) {
      need("--km-side-ink", bg, hc ? 7 : 4.5, "sidebar text");
      need("--km-side-ink-strong", bg, hc ? 7 : 4.5, "sidebar current item");
      need("--km-side-focus", bg, 3, "focus ring in the sidebar");
    }
    need("--km-side-muted", "--km-side-bg", 4.5, "sidebar version line");
    need("--km-side-badge-fg", "--km-side-badge-bg", 4.5, "issue count badge");
    for (const icon of NAV) need(icon, "--km-side-bg", 3, "sidebar icons");
    // The hero banner.
    need("--km-hero-ink", "--km-hero-bg", hc ? 7 : 4.5, "hero heading");
    need("--km-hero-muted", "--km-hero-bg", 4.5, "hero text");
    need("--km-hero-ink", "--km-hero-button", 4.5, "hero secondary button");
  }
}

if (failures.length) {
  console.error(`Contrast check failed:\n  ${failures.join("\n  ")}`);
  process.exit(1);
}
console.log(
  `Contrast check: ${checked} colour pairs pass in ${Object.keys(themes).length} themes, ${accents.length} accents.`,
);
