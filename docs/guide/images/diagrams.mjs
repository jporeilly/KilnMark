// Draws the guides' diagrams as SVG files beside this script: `node docs/guide/images/diagrams.mjs`.
// Plain SVG with presentation attributes only (no <style>, no style=""), so the app's security policy
// lets them show; colours are fixed, on white, so they read the same in every theme. Change a
// diagram here and run the script again; never edit the SVG by hand.
import { writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const OUT = dirname(fileURLToPath(import.meta.url));
const C = {
  ink: "#1d1f22",
  quiet: "#5c6168",
  axis: "#b4aea2",
  tint: "#efede7",
  blue: "#2f72d8",
  orange: "#e8663d",
  green: "#17a179",
  navy: "#1f3d73",
  pink: "#d24578",
  violet: "#7b55c7",
  good: "#1c9a3b",
  bad: "#d23f3f",
  warn: "#c08217",
};
const FONT = "Inter, 'Segoe UI', Helvetica, Arial, sans-serif";

const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/** Text: size 13 by default; weight 600 for names; anchor start, middle or end. */
function text(x, y, words, o = {}) {
  const a = [`x="${x}"`, `y="${y}"`, `font-size="${o.size ?? 13}"`, `fill="${o.fill ?? C.ink}"`];
  if (o.weight) a.push(`font-weight="${o.weight}"`);
  if (o.anchor) a.push(`text-anchor="${o.anchor}"`);
  if (o.spacing) a.push(`letter-spacing="${o.spacing}"`);
  return `<text ${a.join(" ")}>${esc(words)}</text>`;
}

function rect(x, y, w, h, o = {}) {
  const a = [`x="${x}"`, `y="${y}"`, `width="${w}"`, `height="${h}"`, `rx="${o.rx ?? 8}"`];
  a.push(`fill="${o.fill ?? "none"}"`);
  if (o.fillOpacity != null) a.push(`fill-opacity="${o.fillOpacity}"`);
  if (o.stroke) a.push(`stroke="${o.stroke}"`, `stroke-width="${o.strokeWidth ?? 1.25}"`);
  if (o.dash) a.push(`stroke-dasharray="${o.dash}"`);
  return `<rect ${a.join(" ")}/>`;
}

const markers = new Set();
/** A line along points [[x, y], …] with an arrowhead in its own colour. */
function arrow(points, color = C.axis, o = {}) {
  markers.add(color);
  const d = points.map(([x, y], i) => `${i ? "L" : "M"}${x} ${y}`).join(" ");
  const dash = o.dash ? ` stroke-dasharray="${o.dash}"` : "";
  const end = o.noHead ? "" : ` marker-end="url(#a${color.slice(1)})"`;
  return `<path d="${d}" fill="none" stroke="${color}" stroke-width="${o.width ?? 1.5}" stroke-linejoin="round"${dash}${end}/>`;
}

/** Small line icons, about 16 units across, centred on cx, cy. */
function icon(kind, cx, cy, color) {
  const g = (inner, width = 1.6) =>
    `<g fill="none" stroke="${color}" stroke-width="${width}" stroke-linecap="round" stroke-linejoin="round">${inner}</g>`;
  switch (kind) {
    case "person":
      return g(`<circle cx="${cx}" cy="${cy - 3.5}" r="3.5"/><path d="M${cx - 6} ${cy + 8}a6 5 0 0 1 12 0"/>`);
    case "terminal":
      return g(`<path d="M${cx - 6} ${cy - 5}l4 4l-4 4"/><path d="M${cx + 1} ${cy + 4}h6"/>`);
    case "check":
      return g(`<path d="M${cx - 5} ${cy}l4 4l7 -8"/>`, 2);
    case "cross":
      return g(`<path d="M${cx - 6} ${cy - 6}l12 12M${cx + 6} ${cy - 6}l-12 12"/>`, 2);
    case "lock":
      return g(`<rect x="${cx - 7}" y="${cy - 2}" width="14" height="10" rx="2"/><path d="M${cx - 4} ${cy - 2}v-3a4 4 0 0 1 8 0v3"/>`);
    case "eye":
      return g(`<path d="M${cx - 8} ${cy}q8 -8 16 0q-8 8 -16 0"/><circle cx="${cx}" cy="${cy}" r="2.5"/>`);
    case "eye-off":
      return g(`<path d="M${cx - 8} ${cy}q8 -8 16 0q-8 8 -16 0"/><path d="M${cx - 8} ${cy + 7}l16 -14"/>`);
    case "key":
      return g(`<circle cx="${cx - 4}" cy="${cy}" r="5"/><path d="M${cx + 1} ${cy}h9M${cx + 7} ${cy}v4M${cx + 10} ${cy}v3"/>`);
    case "log":
      return g(`<path d="M${cx - 6} ${cy - 5}h12M${cx - 6} ${cy}h12M${cx - 6} ${cy + 5}h8"/>`);
    case "db":
      return g(`<ellipse cx="${cx}" cy="${cy - 5}" rx="7" ry="3"/><path d="M${cx - 7} ${cy - 5}v10a7 3 0 0 0 14 0v-10"/><path d="M${cx - 7} ${cy}a7 3 0 0 0 14 0"/>`);
    case "file":
      return g(`<path d="M${cx - 5} ${cy - 8}h6l5 5v11h-11z"/><path d="M${cx + 1} ${cy - 8}v5h5"/>`);
    case "bell":
      return g(`<path d="M${cx - 6} ${cy + 4}v-4a6 6 0 0 1 12 0v4l2 2h-16z"/><path d="M${cx - 2} ${cy + 8}a2 2 0 0 0 4 0"/>`);
    case "mail":
      return g(`<rect x="${cx - 8}" y="${cy - 6}" width="16" height="12" rx="2"/><path d="M${cx - 8} ${cy - 5}l8 6l8 -6"/>`);
    case "shield":
      return g(`<path d="M${cx} ${cy - 8}l7 3v5c0 4 -3 7 -7 8c-4 -1 -7 -4 -7 -8v-5z"/>`);
    case "gear":
      return g(`<circle cx="${cx}" cy="${cy}" r="3"/><circle cx="${cx}" cy="${cy}" r="7" stroke-dasharray="3 2.5"/>`);
    case "play":
      return g(`<path d="M${cx - 4} ${cy - 6}l10 6l-10 6z"/>`);
    case "pencil":
      return g(`<path d="M${cx - 6} ${cy + 6}l1 -4l8 -8l3 3l-8 8z"/>`);
    case "save":
      return g(`<rect x="${cx - 7}" y="${cy - 7}" width="14" height="14" rx="2"/><path d="M${cx - 4} ${cy - 7}v4h8v-4M${cx - 4} ${cy + 7}v-5h8v5"/>`);
    case "chip":
      return g(`<rect x="${cx - 6}" y="${cy - 6}" width="12" height="12" rx="2"/><path d="M${cx - 3} ${cy - 9}v3M${cx + 3} ${cy - 9}v3M${cx - 3} ${cy + 6}v3M${cx + 3} ${cy + 6}v3"/>`);
    case "chat":
      return g(`<path d="M${cx - 8} ${cy - 6}h16v10h-9l-4 4v-4h-3z"/>`);
    case "book":
      return g(`<path d="M${cx} ${cy - 5}c-2 -2 -6 -2 -8 -1v12c2 -1 6 -1 8 1c2 -2 6 -2 8 -1v-12c-2 -1 -6 -1 -8 1z"/><path d="M${cx} ${cy - 5}v12"/>`);
    case "funnel":
      return g(`<path d="M${cx - 8} ${cy - 6}h16l-6 7v6l-4 2v-8z"/>`);
    case "clock":
      return g(`<circle cx="${cx}" cy="${cy}" r="7"/><path d="M${cx} ${cy - 4}v4l3 2"/>`);
    default:
      return "";
  }
}

/** A disc with an icon in it. */
const badge = (kind, cx, cy, color) =>
  `<circle cx="${cx}" cy="${cy}" r="13" fill="${color}" fill-opacity="0.16" stroke="${color}" stroke-width="1.25"/>` + icon(kind, cx, cy, color);

/** A numbered disc. */
const num = (n, cx, cy, color) =>
  `<circle cx="${cx}" cy="${cy}" r="10" fill="${color}" fill-opacity="0.16" stroke="${color}" stroke-width="1.25"/>` +
  text(cx, cy + 4, n, { size: 11, weight: 600, anchor: "middle", fill: color });

/**
 * A card: a tinted box in its colour, with an icon (or a number) on the left, a name and a line
 * under it. `strong` draws it as the place things lead to.
 */
function card(x, y, w, o) {
  const h = o.h ?? 56;
  const color = o.color ?? C.axis;
  const neutral = color === C.axis;
  let s = rect(x, y, w, h, {
    fill: neutral ? C.tint : color,
    fillOpacity: neutral ? 1 : o.strong ? 0.13 : 0.08,
    stroke: color,
    strokeWidth: o.strong ? 2 : 1.25,
    dash: o.dash,
  });
  const cy = y + h / 2;
  const mark = neutral ? C.quiet : color;
  let tx = x + 14;
  if (o.icon) {
    s += badge(o.icon, x + 26, cy, mark);
    tx = x + 48;
  } else if (o.n) {
    s += num(o.n, x + 24, cy, mark);
    tx = x + 42;
  }
  if (o.sub) {
    s += text(tx, cy - 3, o.title, { weight: 600 });
    s += text(tx, cy + 14, o.sub, { size: 11.5, fill: C.quiet });
  } else {
    s += text(tx, cy + 4.5, o.title, { weight: 600 });
  }
  return s;
}

/** A band behind a row of cards, its name in small capitals at the top left. */
function lane(y, h, name, color) {
  return (
    rect(24, y, 712, h, { rx: 12, fill: color, fillOpacity: 0.06 }) +
    text(40, y + 19, name.toUpperCase(), { size: 10.5, weight: 700, fill: color, spacing: "0.08em" })
  );
}

/** The grey band along the foot, with an icon and one line. */
const foot = (y, kind, words) =>
  rect(24, y, 712, 34, { fill: C.tint, rx: 8 }) + icon(kind, 44, y + 17, C.quiet) + text(62, y + 21.5, words, { size: 11.5 });

const label = (x, y, words, color = C.quiet, anchor = "middle") => text(x, y, words, { size: 11.5, fill: color, anchor });

function svg(name, height, title, sub, body) {
  const defs = [...markers]
    .map(
      (c) =>
        `<marker id="a${c.slice(1)}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="${c}"/></marker>`,
    )
    .join("");
  markers.clear();
  const head = text(24, 34, title, { size: 16, weight: 650 }) + (sub ? text(24, 55, sub, { size: 12, fill: C.quiet }) : "");
  const out =
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 760 ${height}" width="760" height="${height}" font-family="${FONT}" role="img" aria-label="${esc(title)}">` +
    `<title>${esc(title)}</title><defs>${defs}</defs>` +
    rect(0, 0, 760, height, { fill: "#ffffff", rx: 0 }) +
    head +
    body +
    `</svg>\n`;
  writeFileSync(join(OUT, `${name}.svg`), out);
  console.log(name);
}

// Arrows must be drawn before svg() is called for their markers to be collected: each diagram
// builds its body first, then calls svg().

// ---- Building and running a pipeline ----
{
  const W = 208, X = [40, 276, 512];
  const y1 = 76, y2 = 188, y3 = 300;
  const b =
    lane(y1, 96, "Build", C.green) +
    card(X[0], y1 + 30, W, { n: "1", color: C.green, title: "Build it", sub: "on the canvas or in YAML" }) +
    card(X[1], y1 + 30, W, { n: "2", color: C.green, title: "Checked as you type", sub: "problems listed by line" }) +
    card(X[2], y1 + 30, W, { n: "3", color: C.green, title: "Save a version", sub: "a draft if it has problems" }) +
    arrow([[X[0] + W, y1 + 58], [X[1] - 2, y1 + 58]], C.green) +
    arrow([[X[1] + W, y1 + 58], [X[2] - 2, y1 + 58]], C.green) +
    lane(y2, 96, "Run", C.blue) +
    card(X[2], y2 + 30, W, { n: "4", color: C.blue, title: "Run now", sub: "checked again and queued" }) +
    card(X[1], y2 + 30, W, { n: "5", color: C.blue, title: "A worker takes it", sub: "when it has a free slot" }) +
    card(X[0], y2 + 30, W, { n: "6", color: C.blue, title: "Writes locked", sub: "no other run writes them" }) +
    arrow([[X[2] + W / 2, y1 + 86], [X[2] + W / 2, y2 + 28]], C.blue) +
    arrow([[X[2], y2 + 58], [X[1] + W + 2, y2 + 58]], C.blue) +
    arrow([[X[1], y2 + 58], [X[0] + W + 2, y2 + 58]], C.blue) +
    lane(y3, 152, "Finish", C.quiet) +
    card(X[0], y3 + 58, W, { n: "7", color: C.blue, title: "Nodes run", sub: "every row in one pass" }) +
    arrow([[X[0] + W / 2, y2 + 86], [X[0] + W / 2, y3 + 56]], C.blue) +
    card(X[1] + 30, y3 + 28, 222, { icon: "check", color: C.good, strong: true, title: "Succeeded", sub: "writes commit, then catalogued" }) +
    card(X[1] + 30, y3 + 92, 222, { icon: "cross", color: C.bad, title: "Failed or cancelled", sub: "the run's page says why" }) +
    arrow([[X[0] + W, y3 + 78], [X[1] + 6, y3 + 78], [X[1] + 6, y3 + 56], [X[1] + 28, y3 + 56]], C.good) +
    arrow([[X[0] + W, y3 + 94], [X[1] + 6, y3 + 94], [X[1] + 6, y3 + 120], [X[1] + 28, y3 + 120]], C.bad) +
    label(X[2] + 70, y3 + 60, "A stop gate, an error or", C.quiet, "start") +
    label(X[2] + 70, y3 + 76, "Cancel run ends a run.", C.quiet, "start") +
    label(X[2] + 70, y3 + 100, "Rows already written", C.quiet, "start") +
    label(X[2] + 70, y3 + 116, "stay written.", C.quiet, "start");
  svg("pipeline-run", 476, "From building a pipeline to a finished run", "What happens after Run now, and what you see at each step", b);
}

// ---- What a gate does with a failing row ----
{
  const gx = 196, gy = 168;
  const rows = [
    [96, C.good, "check", "Passes", "on to the next node, through out"],
    [168, C.bad, "cross", "on_fail: stop", "the run fails; writes after the gate do not commit"],
    [240, C.warn, "funnel", "on_fail: quarantine", "set aside on quarantine; the run goes on"],
    [312, C.blue, "eye", "on_fail: warn", "goes on through out; recorded with the run"],
  ];
  let b =
    card(24, gy - 28, 120, { icon: "db", color: C.blue, title: "Rows", sub: "from the read" }) +
    arrow([[144, gy], [gx - 2, gy]], C.axis) +
    `<polygon points="${gx},${gy} ${gx + 58},${gy - 44} ${gx + 116},${gy} ${gx + 58},${gy + 44}" fill="${C.tint}" stroke="${C.axis}" stroke-width="1.25"/>` +
    text(gx + 58, gy - 4, "Does the row", { size: 12.5, weight: 600, anchor: "middle" }) +
    text(gx + 58, gy + 12, "pass its rules?", { size: 12.5, weight: 600, anchor: "middle" });
  for (const [y, color, ic, name, sub] of rows) {
    b += arrow([[gx + 116, gy], [356, gy], [356, y], [404, y]], color, { dash: color === C.good ? undefined : "4 3" });
    b += card(406, y - 28, 330, { icon: ic, color, strong: color === C.good, title: name, sub });
  }
  b += label(366, 86, "yes", C.good, "start") + label(366, 208, "no:", C.quiet, "start") + label(366, 224, "on_fail", C.quiet, "start");
  svg("gate-on-fail", 384, "A gate decides what happens to each row that fails its rules", "on_fail is set on the gate: stop (the default), quarantine or warn", b);
}

// ---- Choosing a write mode ----
{
  const b =
    `<polygon points="196,110 296,72 396,110 296,148" fill="${C.tint}" stroke="${C.axis}" stroke-width="1.25"/>` +
    text(296, 106, "Does the table", { size: 12.5, weight: 600, anchor: "middle" }) +
    text(296, 122, "exist yet?", { size: 12.5, weight: 600, anchor: "middle" }) +
    arrow([[196, 110], [140, 110], [140, 178]], C.green) +
    label(150, 100, "no", C.green, "start") +
    card(40, 180, 200, { icon: "save", color: C.green, title: "create", sub: "made from the input, filled" }) +
    arrow([[396, 110], [520, 110], [520, 178]], C.blue) +
    label(410, 100, "yes, or it may", C.blue, "start") +
    `<polygon points="420,214 520,180 620,214 520,248" fill="${C.tint}" stroke="${C.axis}" stroke-width="1.25"/>` +
    text(520, 210, "Its rows should…", { size: 12.5, weight: 600, anchor: "middle" }) +
    text(520, 226, "", { size: 12 }) +
    arrow([[520, 248], [520, 268], [128, 268], [128, 300]], C.blue) +
    arrow([[520, 268], [376, 268], [376, 300]], C.blue) +
    arrow([[520, 268], [624, 268], [624, 300]], C.blue) +
    label(136, 290, "stay; add these", C.quiet, "start") +
    label(384, 290, "all be swapped for these", C.quiet, "start") +
    label(632, 290, "be matched by key", C.quiet, "start") +
    card(24, 302, 216, { icon: "pencil", color: C.blue, title: "append", sub: "rows added; columns must fit" }) +
    card(266, 302, 222, { icon: "save", color: C.blue, title: "replace", sub: "filled aside, swapped in at once" }) +
    card(514, 302, 222, { icon: "key", color: C.blue, title: "upsert", sub: "update by key, insert the rest" }) +
    foot(380, "shield", "Every mode takes a lock on the table first, so no other run writes it at the same time.");
  svg("write-modes", 430, "Choosing a write mode", "A write node's mode says what happens to the rows already in the table", b);
}

// ---- A restore's steps ----
{
  const L = 40, W = 330, R = 406, l2 = 330;
  const b =
    lane(72, 240, "While Kilnmark keeps running", C.blue) +
    card(L, 100, W, { n: "1", color: C.blue, title: "Files checked", sub: "each against SHA256SUMS" }) +
    card(L, 166, W, { n: "2", color: C.blue, title: "Says what it will do", sub: "run it again with --yes" }) +
    card(R, 100, W, { n: "3", color: C.blue, title: "Restored into kilnmark_restoring", sub: "a database of its own, beside yours" }) +
    arrow([[L + W / 2, 156], [L + W / 2, 164]], C.blue) +
    arrow([[L + W, 194], [R - 18, 194], [R - 18, 128], [R - 2, 128]], C.blue) +
    `<polygon points="${R},226 ${R + 75},196 ${R + 150},226 ${R + 75},256" fill="${C.tint}" stroke="${C.axis}" stroke-width="1.25"/>` +
    text(R + 75, 230, "Restored?", { size: 12.5, weight: 600, anchor: "middle" }) +
    arrow([[R + 75, 156], [R + 75, 194]], C.blue) +
    arrow([[R + 150, 226], [R + 188, 226]], C.bad, { dash: "4 3" }) +
    label(R + 160, 218, "no", C.bad, "start") +
    card(R + 190, 200, 140, { color: C.good, title: "Nothing changed", sub: "runs as before", h: 52 }) +
    arrow([[R + 75, 256], [R + 75, l2 + 26]], C.navy) +
    label(R + 85, 290, "yes", C.navy, "start") +
    lane(l2, 172, "Kilnmark stopped for the swap", C.navy) +
    card(R, l2 + 28, W, { n: "4", color: C.navy, title: "Your database kept", sub: "as kilnmark_before_<time>" }) +
    card(R, l2 + 94, W, { n: "5", color: C.navy, title: "Restored database put in place", sub: "as kilnmark" }) +
    card(L, l2 + 28, W, { n: "6", color: C.navy, title: "Keys swapped", sub: "the old ones kept in before-restore-<time>" }) +
    card(L, l2 + 94, W, { n: "7", color: C.navy, title: "Started, and waits until ready", sub: "then the audit log says Database restored" }) +
    arrow([[R + W / 2, l2 + 84], [R + W / 2, l2 + 92]], C.navy) +
    arrow([[R, l2 + 122], [R - 18, l2 + 122], [R - 18, l2 + 56], [L + W + 2, l2 + 56]], C.navy) +
    arrow([[L + W / 2, l2 + 84], [L + W / 2, l2 + 92]], C.navy) +
    foot(l2 + 188, "shield", "Afterwards, redo security changes made since the backup: revoked tokens work again.");
  svg("restore-steps", l2 + 238, "A restore never leaves you without a working database", "What restore.sh does, in order: your old database and keys are kept until you remove them", b);
}

// ---- Where an audit event goes ----
{
  const b =
    card(24, 92, 230, { icon: "shield", color: C.navy, title: "A security-relevant change", sub: "sign-in, token, source, grant…" }) +
    arrow([[254, 120], [286, 120], [286, 104], [318, 104]], C.navy) +
    arrow([[254, 120], [286, 120], [286, 200], [318, 200]], C.navy) +
    card(320, 76, 210, { icon: "db", color: C.blue, strong: true, title: "The audit log", sub: "rows only ever added" }) +
    card(320, 172, 210, { icon: "log", color: C.violet, title: "A JSON line", sub: "log category kilnmark.audit" }) +
    arrow([[530, 104], [566, 104]], C.blue) +
    arrow([[530, 200], [566, 200]], C.violet) +
    card(568, 76, 168, { icon: "gear", color: C.blue, title: "Audit log page", sub: "Settings, for admins" }) +
    card(568, 172, 168, { icon: "shield", color: C.violet, title: "Your SIEM", sub: "syslog or container logs" }) +
    foot(252, "lock", "No one can change or delete an event, not even an admin: the database refuses it.");
  svg("audit-log-flow", 302, "Every security-relevant change is recorded twice", "Once in Kilnmark, where it cannot be changed, and once for your SIEM", b);
}

// ---- How a notification reaches you ----
{
  const b =
    card(24, 80, 250, { icon: "bell", color: C.orange, title: "Something happens", sub: "a rule fails, a run fails, drift…" }) +
    arrow([[274, 108], [306, 108]], C.orange) +
    `<polygon points="308,108 388,72 468,108 388,144" fill="${C.tint}" stroke="${C.axis}" stroke-width="1.25"/>` +
    text(388, 104, "New, or worse", { size: 12.5, weight: 600, anchor: "middle" }) +
    text(388, 120, "than before?", { size: 12.5, weight: 600, anchor: "middle" }) +
    arrow([[468, 108], [530, 108]], C.quiet, { dash: "4 3" }) +
    label(476, 98, "no", C.quiet, "start") +
    card(532, 84, 204, { icon: "check", color: C.axis, title: "Nothing new sent", sub: "told once per problem", h: 48 }) +
    arrow([[388, 144], [388, 182]], C.orange) +
    label(396, 168, "yes", C.orange, "start") +
    card(240, 184, 296, { icon: "person", color: C.orange, title: "Everyone it concerns", sub: "whose choices include this kind and source" }) +
    arrow([[320, 240], [320, 256], [140, 256], [140, 282]], C.orange) +
    arrow([[456, 240], [456, 256], [612, 256], [612, 282]], C.orange) +
    card(40, 284, 220, { icon: "bell", color: C.orange, strong: true, title: "In Kilnmark", sub: "Settings › Notifications" }) +
    card(470, 284, 266, { icon: "mail", color: C.blue, title: "By email, when it is on", sub: "confirmed, kind ticked, severity reached" }) +
    label(612, 362, "at once, or in the daily digest", C.quiet, "middle");
  svg("notifications-flow", 380, "Told once per problem, in Kilnmark and by email", "You hear again only if it gets worse, and once more when it recovers", b);
}

// ---- A quality rule's cycle ----
{
  const b =
    card(24, 84, 220, { n: "1", color: C.green, title: "Suggested, or written", sub: "from the profile, or by you" }) +
    card(270, 84, 220, { n: "2", color: C.green, title: "Tried before saving", sub: "see the failing rows" }) +
    card(516, 84, 220, { n: "3", color: C.green, title: "Saved, with thresholds", sub: "Warn above, Fail above" }) +
    arrow([[244, 112], [268, 112]], C.green) +
    arrow([[490, 112], [514, 112]], C.green) +
    arrow([[626, 140], [626, 172]], C.blue) +
    card(458, 174, 278, { icon: "play", color: C.blue, strong: true, title: "Runs after every scan", sub: "or with Run rules now" }) +
    arrow([[458, 202], [424, 202]], C.blue) +
    `<polygon points="292,202 358,170 424,202 358,234" fill="${C.tint}" stroke="${C.axis}" stroke-width="1.25"/>` +
    text(358, 206, "Result", { size: 12.5, weight: 600, anchor: "middle" }) +
    arrow([[292, 202], [246, 202]], C.good) +
    label(280, 192, "Pass", C.good, "end") +
    card(24, 174, 220, { icon: "check", color: C.good, title: "Sets the score", sub: "share of rows that passed" }) +
    arrow([[358, 234], [358, 270]], C.bad, { dash: "4 3" }) +
    label(368, 258, "Warning, Fail or Could not run", C.bad, "start") +
    card(176, 272, 364, { icon: "bell", color: C.bad, title: "A task, a notification, a webhook", sub: "the task closes by itself once the rule passes" }) +
    foot(348, "log", "Each result keeps its failing-row count; database rules give the query for the failing rows.");
  svg("quality-rule-cycle", 398, "A rule runs after every scan and says what failed", "From a suggestion to a result, and what a failure sets off", b);
}

// ---- What the Ask panel sends ----
{
  const b =
    card(24, 80, 210, { icon: "chat", color: C.violet, title: "Your question", sub: "and the page you are on" }) +
    arrow([[234, 108], [266, 108]], C.violet) +
    card(268, 80, 220, { icon: "book", color: C.violet, title: "Matching guide sections", sub: "from these guides only" }) +
    arrow([[488, 108], [520, 108]], C.violet) +
    `<polygon points="522,108 628,72 734,108 628,144" fill="${C.tint}" stroke="${C.axis}" stroke-width="1.25"/>` +
    text(628, 104, "Hosted models", { size: 12.5, weight: 600, anchor: "middle" }) +
    text(628, 120, "allowed?", { size: 12.5, weight: 600, anchor: "middle" }) +
    arrow([[628, 144], [628, 176], [500, 176], [500, 196]], C.violet) +
    label(636, 166, "yes", C.violet, "start") +
    arrow([[628, 176], [656, 176], [656, 196]], C.quiet, { dash: "4 3" }) +
    card(268, 198, 292, { icon: "chip", color: C.violet, strong: true, title: "A hosted model gets only these", sub: "nothing from your catalogue" }) +
    card(576, 198, 160, { icon: "chip", color: C.axis, title: "A local model", sub: "or the sections listed" }) +
    label(664, 188, "no", C.quiet, "start") +
    card(24, 198, 220, { icon: "check", color: C.good, title: "An answer", sub: "with links to the sections" }) +
    arrow([[268, 226], [246, 226]], C.violet) +
    foot(278, "log", "Every call to a model is recorded under AI usage. Never paste passwords or personal data.");
  svg("assistant-privacy", 328, "The Ask panel sends your question and the guides, never your data", "What leaves Kilnmark when you ask a question", b);
}

// ---- Signing in: two ways in ----
{
  const yA = 128, yB = 224;
  const row = (cy, color, ic, a, as, b, bs, c, cs) =>
    rect(24, cy - 40, 572, 80, { rx: 12, fill: color, fillOpacity: 0.06 }) +
    card(40, cy - 28, 150, { icon: ic, color, title: a, sub: as }) +
    card(214, cy - 28, 176, { color, title: b, sub: bs }) +
    card(414, cy - 28, 166, { color, title: c, sub: cs }) +
    arrow([[190, cy], [212, cy]], color) +
    arrow([[390, cy], [412, cy]], color) +
    arrow([[580, cy], [614, cy]], color);
  let b =
    row(yA, C.orange, "terminal", "Script or CI", "a program", "Sends its API token", "km_… with every request", "Kilnmark checks it", "not revoked or expired") +
    row(yB, C.green, "person", "A person", "in a browser", "Organisation login", "your provider, with MFA", "Kilnmark checks", "groups, MFA, when") +
    rect(616, 88, 120, 176, { rx: 10, fill: C.blue, fillOpacity: 0.1, stroke: C.blue, strokeWidth: 2 }) +
    text(676, 110, "Role + grant", { weight: 600, anchor: "middle" });
  ["Admin", "Designer", "Steward", "Viewer"].forEach((r, i) => {
    b += rect(630, 120 + i * 26, 92, 22, { rx: 11, fill: C.blue, fillOpacity: 0.2, stroke: C.blue });
    b += text(676, 135 + i * 26, r, { size: 11.5, weight: 600, anchor: "middle" });
  });
  b += rect(630, 230, 92, 22, { rx: 11, stroke: C.navy, dash: "4 3" }) + text(676, 245, "+ unmask grant", { size: 11.5, anchor: "middle" });
  b += foot(280, "log", "Every sign-in, refusal and change is written to the audit log, which is never changed or deleted.");
  svg("signin-two-ways", 330, "Two ways in, one set of roles", "Scripts use API tokens; people can sign in with your organisation's login", b);
}

// ---- Signing in: roles ----
{
  const base = 300;
  const step = (x, y0, o, name, sub, dots) => {
    let s = rect(x, y0, 164, base - y0, { rx: 10, fill: C.blue, fillOpacity: o, stroke: C.blue });
    for (let i = 0; i < dots; i++) s += `<circle cx="${x + 16 + i * 11}" cy="${y0 + 16}" r="3.5" fill="${C.blue}"/>`;
    return s + text(x + 16, y0 + 40, name, { size: 14, weight: 600 }) + text(x + 16, y0 + 56, sub, { size: 11.5, fill: C.quiet });
  };
  const b =
    step(24, 236, 0.07, "Viewer", "reads the catalogue", 1) +
    step(198, 200, 0.11, "Steward", "also runs and scans", 2) +
    step(372, 164, 0.15, "Designer", "also builds and adds", 3) +
    step(546, 128, 0.19, "Admin", "also tokens, Settings", 4) +
    icon("eye-off", 570, 204, C.quiet) +
    text(586, 208, "sees values masked", { size: 11.5, fill: C.quiet }) +
    `<path d="M454 118V164" fill="none" stroke="${C.navy}" stroke-width="2" stroke-dasharray="5 4"/><circle cx="454" cy="164" r="4" fill="${C.navy}"/>` +
    rect(352, 66, 204, 52, { rx: 10, fill: C.navy, fillOpacity: 0.1, stroke: C.navy, strokeWidth: 2 }) +
    icon("key", 380, 92, C.navy) +
    text(408, 88, "Unmask grant", { weight: 600 }) +
    text(408, 105, "sees sensitive values", { size: 11.5 }) +
    arrow([[24, 322], [708, 322]], C.quiet, { width: 1.25 }) +
    label(710, 342, "more each role can do", C.quiet, "end");
  svg("signin-roles", 352, "Each role includes the one below; only designers can hold the grant", "", b);
}

// ---- Signing in: the three checks ----
{
  const y = 100, low = 192;
  let b =
    card(24, y - 28, 128, { icon: "person", color: C.green, title: "Back from", sub: "your provider" }) +
    card(608, y - 28, 128, { icon: "check", color: C.good, strong: true, title: "Role given", sub: "+ grant if set" }) +
    rect(176, low, 408, 48, { fill: C.bad, fillOpacity: 0.08, stroke: C.bad }) +
    icon("cross", 200, low + 24, C.bad) +
    text(396, low + 29, "No role: the page says why, the audit log records it", { anchor: "middle" });
  const checks = [[176, "1", "MFA used?"], [320, "2", "Within 8 h?"], [464, "3", "In a group?"]];
  for (const [x, n, q] of checks) {
    b += rect(x, y - 28, 120, 56, { fill: C.tint, stroke: C.axis }) + num(n, x + 20, y - 10, C.blue) + text(x + 66, y + 16, q, { weight: 600, anchor: "middle" });
    b += arrow([[x + 60, y + 28], [x + 60, low - 2]], C.bad, { dash: "4 3" }) + label(x + 68, y + 64, "no", C.bad, "start");
  }
  for (const [x1, x2] of [[152, 174], [296, 318], [440, 462], [584, 606]]) {
    b += arrow([[x1, y], [x2, y]], C.good) + label((x1 + x2) / 2, y - 10, "yes", C.good);
  }
  svg("signin-checks", 260, "A sign-in gets a role only if all three checks pass", "", b);
}

// ---- The Library: samples stay apart, templates become your own ----
{
  const W = 208, X = [40, 276, 512];
  const y1 = 76, y2 = 188, y3 = 300;
  const b =
    lane(y1, 96, "Samples", C.orange) +
    card(X[0], y1 + 30, W, { n: "1", color: C.orange, title: "Add samples", sub: "in the Library, Samples tab" }) +
    card(X[1], y1 + 30, W, { n: "2", color: C.orange, title: "Marked as samples", sub: "they run like any other" }) +
    card(X[2], y1 + 30, W, { icon: "book", color: C.orange, strong: true, title: "Listed in the Library", sub: "with their last runs" }) +
    arrow([[X[0] + W, y1 + 58], [X[1] - 2, y1 + 58]], C.orange) +
    arrow([[X[1] + W, y1 + 58], [X[2] - 2, y1 + 58]], C.orange) +
    lane(y2, 96, "Templates", C.green) +
    card(X[0], y2 + 30, W, { n: "1", color: C.green, title: "Use this template", sub: "in the Library, Templates tab" }) +
    card(X[1], y2 + 30, W, { n: "2", color: C.green, title: "Opens in the editor", sub: "name it; fill in each <…>" }) +
    card(X[2], y2 + 30, W, { n: "3", color: C.green, title: "Save it", sub: "now yours, not marked" }) +
    arrow([[X[0] + W, y2 + 58], [X[1] - 2, y2 + 58]], C.green) +
    arrow([[X[1] + W, y2 + 58], [X[2] - 2, y2 + 58]], C.green) +
    lane(y3, 96, "Your own work", C.blue) +
    card(X[1], y3 + 30, W + 236, { icon: "play", color: C.blue, strong: true, title: "The Pipelines and Flows pages", sub: "only your team's own development and production work" }) +
    arrow([[X[2] + W / 2, y2 + 86], [X[2] + W / 2, y3 + 28]], C.blue) +
    foot(412, "book", "A sample never shows on the Pipelines or Flows page, and using a template never changes it.");
  svg("library", 462, "The Library keeps samples apart from your own work", "Samples stay in the Library; a template, once saved, becomes one of your own", b);
}

// ---- Signing in: two people for sensitive values ----
{
  const y = 108;
  const b =
    card(24, y - 28, 160, { icon: "person", color: C.green, title: "Alice asks", sub: "via your provider" }) +
    card(208, y - 28, 196, { icon: "lock", color: C.axis, title: "Designer token made", sub: "values masked meanwhile" }) +
    card(428, y - 28, 168, { icon: "person", color: C.pink, title: "Bob approves", sub: "a different person" }) +
    card(620, y - 28, 116, { icon: "eye", color: C.blue, strong: true, title: "Unmasked" }) +
    arrow([[184, y], [206, y]]) +
    arrow([[404, y], [426, y]]) +
    arrow([[596, y], [618, y]]) +
    `<path d="M512 ${y + 28}V180" fill="none" stroke="${C.bad}" stroke-width="1.5" stroke-dasharray="4 3"/>` +
    rect(324, 180, 376, 52, { fill: C.bad, fillOpacity: 0.07, stroke: C.bad }) +
    icon("cross", 348, 206, C.bad) +
    text(524, 202, "Cannot take Bob's place", { weight: 600, anchor: "middle" }) +
    text(524, 220, "Alice herself, under any name · any API token", { size: 11.5, fill: C.quiet, anchor: "middle" }) +
    foot(252, "log", "Each step is in the audit log; the access review shows who approved each grant.");
  svg("signin-two-people", 302, "No one person can give access to sensitive values", "With KILNMARK_UNMASK_APPROVAL on: both people sign in with your identity provider", b);
}

// ---- A flow node's timeout ----
{
  const W = 208, X = [40, 276, 512];
  const y1 = 76, y2 = 188, y3 = 300;
  const dx = X[1] + W / 2, dy = y2 + 58;
  const b =
    lane(y1, 96, "Not counted", C.quiet) +
    card(X[0], y1 + 30, W, { n: "1", title: "Node ready", sub: "its connections allow it" }) +
    card(X[1], y1 + 30, W, { n: "2", title: "Waits its turn", sub: "for a worker or a source" }) +
    card(X[2], y1 + 30, W, { n: "3", color: C.blue, title: "A worker takes it", sub: "the timeout starts now" }) +
    arrow([[X[0] + W, y1 + 58], [X[1] - 2, y1 + 58]]) +
    arrow([[X[1] + W, y1 + 58], [X[2] - 2, y1 + 58]]) +
    lane(y2, 96, "Counted: timeout: 2h", C.blue) +
    card(X[2], y2 + 30, W, { icon: "play", color: C.blue, title: "Runs", sub: "its pipeline run, or its SQL" }) +
    arrow([[X[2] + W / 2, y1 + 86], [X[2] + W / 2, y2 + 28]], C.blue) +
    arrow([[X[2], dy], [dx + 92, dy]], C.blue) +
    `<polygon points="${dx - 90},${dy} ${dx},${dy - 36} ${dx + 90},${dy} ${dx},${dy + 36}" fill="${C.tint}" stroke="${C.axis}" stroke-width="1.25"/>` +
    text(dx, dy - 4, "Ended within", { size: 12.5, weight: 600, anchor: "middle" }) +
    text(dx, dy + 12, "its timeout?", { size: 12.5, weight: 600, anchor: "middle" }) +
    arrow([[dx - 90, dy], [X[0] + W + 2, dy]], C.good) +
    label(dx - 104, dy - 8, "yes", C.good, "end") +
    card(X[0], y2 + 30, W, { icon: "check", color: C.good, strong: true, title: "Ends as it ended", sub: "succeeded or failed" }) +
    lane(y3, 96, "Past it", C.bad) +
    arrow([[dx, dy + 36], [dx, y3 + 28]], C.bad, { dash: "4 3" }) +
    label(dx + 8, dy + 52, "no", C.bad, "start") +
    card(X[1], y3 + 30, W, { icon: "clock", color: C.bad, dash: "4 3", title: "Stopped", sub: "run cancelled, or rolled back" }) +
    arrow([[X[1] + W, y3 + 58], [X[2] - 2, y3 + 58]], C.bad) +
    card(X[2], y3 + 30, W, { icon: "cross", color: C.bad, title: "Node fails", sub: "\"Took longer than 2h\"" }) +
    arrow([[X[2] + W / 2, y3 + 86], [X[2] + W / 2, y3 + 112], [X[0] + W / 2, y3 + 112], [X[0] + W / 2, y3 + 88]], C.bad) +
    card(X[0], y3 + 30, W, { icon: "bell", color: C.bad, title: "failure connection", sub: "taken: notify, or stop" }) +
    foot(y3 + 128, "gear", "A for-each node gives each item's run the whole timeout; an item stopped for it counts as failed.");
  svg("flow-timeout", 482, "What a node's timeout does", "On run-pipeline, for-each and sql nodes: the time counts only while the work runs", b);
}

// ---- Which waiting run starts first ----
{
  const rows = [
    ["4", "Run queued 4th", "priority: high", C.orange, "taken 1st"],
    ["2", "Run queued 2nd", "priority: normal", C.blue, "taken 2nd"],
    ["3", "Run queued 3rd", "priority: normal", C.blue, "taken 3rd"],
    ["1", "Run queued 1st", "priority: low", C.axis, "taken 4th"],
  ];
  const top = 106, step = 66, cy = top + (3 * step + 56) / 2;
  let b = lane(76, 4 * step + 22, "Waiting for a worker", C.quiet);
  rows.forEach(([n, title, sub, color, taken], i) => {
    const y = top + i * step;
    b += card(40, y, 290, { n, color, title, sub });
    b += arrow([[330, y + 28], [420, y + 28], [420, cy], [500, cy]], color, { noHead: i !== 0 });
    b += label(374, y + 20, taken, color === C.axis ? C.quiet : color);
  });
  b += card(502, cy - 28, 218, { icon: "gear", color: C.green, strong: true, title: "A free worker", sub: "takes them in this order" });
  b += foot(4 * step + 116, "shield", "Priority never passes a source's limit, a flow's max_parallel or a for-each node's.");
  svg("flow-priority", 4 * step + 166, "Which waiting run starts first", "Highest priority first; of the same priority, the one queued first", b);
}

// ---- What a check-source node does ----
{
  const y1 = 96, y2 = 196, y3 = 296;
  const b =
    card(40, y1 - 28, 230, { n: "1", color: C.blue, title: "Connect", sub: "as the source's Test does" }) +
    arrow([[270, y1], [318, y1]], C.blue) +
    card(320, y1 - 28, 230, { n: "2", color: C.blue, title: "Look up each name", sub: "names and columns, no rows" }) +
    arrow([[550, y1], [598, y1]], C.blue) +
    card(600, y1 - 28, 136, { icon: "check", color: C.good, strong: true, title: "All there", sub: "missing: 0" }) +
    arrow([[155, y1 + 28], [155, y2 - 30]], C.bad, { dash: "4 3" }) +
    label(165, y1 + 52, "cannot reach it", C.bad, "start") +
    card(40, y2 - 28, 230, { icon: "cross", color: C.bad, title: "Fails", sub: "\"Could not reach warehouse\"" }) +
    arrow([[435, y1 + 28], [435, y2 - 30]], C.bad, { dash: "4 3" }) +
    label(445, y1 + 52, "some not there: missing 2", C.bad, "start") +
    card(320, y2 - 28, 260, { icon: "cross", color: C.bad, title: "Fails, naming them", sub: "\"2 of 3 tables are missing ...\"" }) +
    arrow([[668, y1 + 28], [668, y3 - 30]], C.good) +
    label(678, y1 + 120, "next node", C.good, "start") +
    arrow([[155, y2 + 28], [155, y3 - 2], [300, y3 - 2]], C.bad) +
    arrow([[528, y2 + 28], [528, y3 - 2], [442, y3 - 2]], C.bad) +
    card(302, y3 - 30, 140, { icon: "bell", color: C.bad, title: "failure", sub: "a notice, a stop" }) +
    card(600, y3 - 30, 136, { icon: "play", color: C.good, title: "The work", sub: "runs" }) +
    foot(y3 + 52, "shield", "Its messages name only what the flow wrote: never the source's settings or its driver's words.");
  svg("flow-check-source", y3 + 104, "What a check-source node does", "Before the work starts: can the source be reached, and is everything it needs there?", b);
}

// ---- How a wait-for node waits ----
{
  const y1 = 96, y2 = 206;
  const b =
    card(40, y1 - 28, 200, { n: "1", color: C.blue, title: "Look", sub: "the files, or the query" }) +
    arrow([[240, y1], [298, y1]], C.blue) +
    `<polygon points="300,${y1} 380,${y1 - 34} 460,${y1} 380,${y1 + 34}" fill="${C.tint}" stroke="${C.axis}" stroke-width="1.25"/>` +
    text(380, y1 + 4, "Ready?", { size: 12.5, weight: 600, anchor: "middle" }) +
    arrow([[460, y1], [552, y1]], C.good) +
    label(506, y1 - 8, "yes", C.good, "middle") +
    card(554, y1 - 28, 182, { icon: "check", color: C.good, strong: true, title: "Succeeds", sub: "found, waited_s" }) +
    arrow([[380, y1 + 34], [380, y2 - 30]], C.blue) +
    label(390, y1 + 56, "no", C.quiet, "start") +
    card(270, y2 - 28, 220, { icon: "lock", color: C.blue, title: "Wait its every", sub: "no worker, no connection" }) +
    arrow([[270, y2], [140, y2], [140, y1 + 30]], C.blue) +
    label(150, y2 - 8, "look again", C.blue, "start") +
    arrow([[490, y2], [552, y2]], C.bad, { dash: "4 3" }) +
    card(554, y2 - 28, 182, { icon: "cross", color: C.bad, title: "Gives up", sub: "past its timeout" }) +
    foot(y2 + 52, "shield", "A query only reads, for at most 30 seconds, and what it returns is never kept or shown.");
  svg("flow-wait-for", y2 + 104, "How a wait-for node waits", "It looks at once, then every so often, until it is ready or its timeout passes", b);
}

// ---- How a schedule fires ----
{
  const W = 208, X = [40, 276, 512];
  const y1 = 76, y2 = 188;
  const b =
    lane(y1, 96, "One short transaction", C.navy) +
    card(X[0], y1 + 30, W, { n: "1", color: C.navy, title: "Due schedules found", sub: "every 30 s, scheduler role" }) +
    card(X[1], y1 + 30, W, { n: "2", color: C.navy, title: "Each one locked", sub: "others are skipped" }) +
    card(X[2], y1 + 30, W, { n: "3", color: C.navy, title: "Next fire moved on", sub: "past now; then it commits" }) +
    arrow([[X[0] + W, y1 + 58], [X[1] - 2, y1 + 58]], C.navy) +
    arrow([[X[1] + W, y1 + 58], [X[2] - 2, y1 + 58]], C.navy) +
    lane(y2, 96, "After it commits", C.blue) +
    card(X[2], y2 + 30, W, { n: "4", color: C.blue, title: "The run is queued", sub: "latest version, as Run now" }) +
    card(X[1], y2 + 30, W, { n: "5", color: C.blue, title: "Acts as the schedule", sub: "as steward or designer" }) +
    card(X[0], y2 + 30, W, { icon: "check", color: C.good, strong: true, title: "Outcome recorded", sub: "started, skipped, failed" }) +
    arrow([[X[2] + W / 2, y1 + 86], [X[2] + W / 2, y2 + 28]], C.blue) +
    arrow([[X[2], y2 + 58], [X[1] + W + 2, y2 + 58]], C.blue) +
    arrow([[X[1], y2 + 58], [X[0] + W + 2, y2 + 58]], C.blue) +
    foot(300, "shield", "Two schedulers never fire one schedule twice. A crash between claim and queue loses that fire, never doubles it.");
  svg("schedule-fire", 350, "How a schedule fires", "The claim commits before the run is queued, so each fire happens at most once", b);
}

// ---- What happens to a fire ----
{
  const dx = 330;
  const diamond = (cy, a, z) =>
    `<polygon points="${dx - 82},${cy} ${dx},${cy - 34} ${dx + 82},${cy} ${dx},${cy + 34}" fill="${C.tint}" stroke="${C.axis}" stroke-width="1.25"/>` +
    text(dx, cy - 4, a, { size: 12.5, weight: 600, anchor: "middle" }) +
    text(dx, cy + 12, z, { size: 12.5, weight: 600, anchor: "middle" });
  const d1 = 112, d2 = 220, d3 = 328, d4 = 436;
  const side = (cy, word, color, icon, title, sub, dash) =>
    arrow([[dx + 82, cy], [520, cy]], color, dash ? { dash: "4 3" } : {}) +
    label(dx + 92, cy - 10, word, color, "start") +
    card(522, cy - 28, 214, { icon, color, dash: dash ? "4 3" : undefined, strong: !dash, title, sub });
  const down = (from, to, word) => arrow([[dx, from + 34], [dx, to - 36]], C.blue) + label(dx + 8, from + 54, word, C.quiet, "start");
  const b =
    card(24, d1 - 28, 200, { icon: "clock", color: C.blue, title: "A fire is due", sub: "found by the scheduler" }) +
    arrow([[224, d1], [dx - 84, d1]], C.blue) +
    diamond(d1, "Due more than", "an hour ago?") +
    side(d1, "yes", C.warn, "clock", "missed", "the server was not running", true) +
    down(d1, d2, "no") +
    diamond(d2, "Its setter still", "has the role?") +
    side(d2, "no", C.warn, "lock", "paused", "and in the audit log", true) +
    down(d2, d3, "yes") +
    diamond(d3, "A run of it", "still going?") +
    side(d3, "yes", C.warn, "play", "skipped", "a run was still going", true) +
    down(d3, d4, "no") +
    diamond(d4, "Can the latest", "version run?") +
    side(d4, "yes", C.good, "check", "started", "the run is queued", false) +
    arrow([[dx - 82, d4], [206, d4]], C.bad, { dash: "4 3" }) +
    label(227, d4 - 10, "no", C.bad) +
    card(24, d4 - 28, 180, { icon: "cross", color: C.bad, title: "failed", sub: "the server's reason" }) +
    foot(492, "gear", "A paused schedule waits for a designer or admin to resume it. Missed and skipped times are not made up.");
  svg("schedule-outcomes", 542, "What happens when a schedule is due", "Each fire ends in one of five outcomes, shown on the schedule", b);
}


// ---- What deleting a flow does ----
{
  const W = 208, X = [40, 276, 512];
  const y1 = 76, y2 = 188;
  const b =
    lane(y1, 96, "Deleted with the flow", C.bad) +
    card(X[0], y1 + 30, W, { icon: "file", color: C.bad, dash: "4 3", title: "The flow", sub: "and every version of it" }) +
    card(X[1], y1 + 30, W, { icon: "play", color: C.bad, dash: "4 3", title: "Its runs", sub: "with what each node did" }) +
    card(X[2], y1 + 30, W, { icon: "clock", color: C.bad, dash: "4 3", title: "Its schedules", sub: "each one in the audit log" }) +
    lane(y2, 96, "Kept", C.good) +
    card(X[0], y2 + 30, W, { icon: "play", color: C.good, strong: true, title: "Its pipeline runs", sub: "in their pipelines' history" }) +
    card(X[1], y2 + 30, W, { icon: "db", color: C.good, strong: true, title: "What they wrote", sub: "tables and files stay" }) +
    card(X[2], y2 + 30, W, { icon: "log", color: C.good, strong: true, title: "Lineage and quality", sub: "in the catalogue" }) +
    foot(300, "lock", "Refused while a run of the flow is queued or running: cancel it, or let it finish, then delete.");
  svg("flow-delete", 350, "What deleting a flow does", "Designers and admins delete a flow; what its pipeline runs did stays", b);
}
