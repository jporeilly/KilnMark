// Checks the colour tokens in src/tokens.css against WCAG 2.2 AA for every theme and accent:
// text 4.5:1 (7:1 for the main text colours in high contrast), and 3:1 for borders of controls,
// focus rings, indicators and bars (1.4.11). Also checks that the dark set's two copies (chosen
// Dark, and System when the OS is dark) agree. Run by `npm run build`, or alone with `npm run check:contrast`.
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

const accents = all
  .filter((r) => r.vars["--km-accent"] && r.selector.startsWith(":root"))
  .map((r) => r.vars["--km-accent"].match(/var\((--km-accent-[\w]+)\)/)[1]);

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

let checked = 0;
for (const [name, t] of Object.entries(themes)) {
  const hc = name.startsWith("contrast");
  const need = (fg, bg, min, why) => {
    checked++;
    const r = ratio(t[fg], t[bg]);
    if (r < min) failures.push(`${name}: ${fg} on ${bg} is ${r.toFixed(2)}:1, needs ${min}:1 (${why})`);
  };
  for (const bg of ["--km-surface", "--km-ground"]) {
    need("--km-ink", bg, hc ? 7 : 4.5, "text");
    need("--km-muted", bg, hc ? 7 : 4.5, "secondary text");
    need("--km-link", bg, hc ? 7 : 4.5, "links");
    for (const s of ["--km-good", "--km-warn", "--km-bad"]) need(s, bg, 4.5, "status text, and bars");
    need("--km-border-control", bg, 3, "borders of controls");
    need("--km-focus", bg, 3, "focus ring");
    for (const a of accents) need(a, bg, 3, "accent indicators");
  }
  // Bars (fill, top values, heat, score): every fill against the track, which is --km-ground with
  // a --km-border-control edge (app.css, browse.css).
  for (const fill of ["--km-good", "--km-warn", "--km-bad", "--km-link"]) need(fill, "--km-ground", 3, "bar fill vs track");
  need("--km-border-control", "--km-ground", 3, "bar track edge");
  for (const a of accents) need("--km-on-accent", a, 4.5, "text on accent buttons");
  for (let n = 1; n <= 8; n++) need(`--km-tile-${n}-fg`, `--km-tile-${n}-bg`, 4.5, "monogram letters");
}

if (failures.length) {
  console.error(`Contrast check failed:\n  ${failures.join("\n  ")}`);
  process.exit(1);
}
console.log(`Contrast check: ${checked} colour pairs pass in ${Object.keys(themes).length} themes, ${accents.length} accents.`);
