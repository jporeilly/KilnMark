/**
 * The two typefaces, bundled so Kilnmark looks the same offline: Inter for text (400, 500, 600)
 * and Manrope for headings and numbers (700, 800). Latin subset only, woff2 only (every browser
 * Kilnmark supports reads woff2, so the woff copies are not shipped), from the @fontsource
 * packages (SIL Open Font License 1.1; see docs/legal/third-party-ui.md). Nothing is fetched from
 * the internet: Vite copies the woff2 files into the build.
 */
import "./fonts.css";
