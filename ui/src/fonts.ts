/**
 * The two typefaces, bundled so Kilnmark looks the same offline: Inter for text (400, 500, 600)
 * and Manrope for headings and numbers (700, 800). Latin subset only, from the @fontsource
 * packages (SIL Open Font License 1.1; see docs/legal/third-party-ui.md). Nothing is fetched from
 * the internet: Vite copies the woff2 files into the build.
 */
import "@fontsource/inter/latin-400.css";
import "@fontsource/inter/latin-500.css";
import "@fontsource/inter/latin-600.css";
import "@fontsource/manrope/latin-700.css";
import "@fontsource/manrope/latin-800.css";
