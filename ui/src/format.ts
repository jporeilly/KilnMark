/** Times in words for lists and tiles: "12 min ago", "3 h ago", "yesterday", then a date. */
const days = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short" });
const daysYear = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric" });

export function formatAgo(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return "–";
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return "–";
  const s = Math.round((now - t) / 1000);
  if (s < 0) return "just now";
  if (s < 60) return "just now";
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} h ago`;
  const d = Math.floor(h / 24);
  if (d === 1) return "yesterday";
  if (d < 7) return `${d} days ago`;
  const date = new Date(t);
  return date.getFullYear() === new Date(now).getFullYear() ? days.format(date) : daysYear.format(date);
}

/** Good morning, afternoon or evening, by the viewer's clock. */
export function greeting(now = new Date()): string {
  const h = now.getHours();
  return h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
}

/** Only in-app links ("#/…") are followed from server-sent text; anything else is not a link. */
export const safeAppLink = (link: string | null | undefined) => (link && /^#\/[^\s]*$/.test(link) ? link : null);
