// The viewer's theme before the first paint (src/theme/appearance.ts takes over from here). A file
// of its own, not inline in index.html, so the Content-Security-Policy allows only Kilnmark's own
// script files (ADR 0031).
try {
  var a = JSON.parse(localStorage.getItem("kilnmark.appearance") || "{}"), d = document.documentElement;
  if (/^(system|light|dark|contrast)$/.test(a.theme)) d.dataset.theme = a.theme;
  if (/^(orange|blue|green|purple|pink|teal)$/.test(a.accent)) d.dataset.accent = a.accent;
  if (/^(comfortable|compact)$/.test(a.density)) d.dataset.density = a.density;
} catch (e) {}
