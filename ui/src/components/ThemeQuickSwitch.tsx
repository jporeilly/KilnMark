import { setAppearance, THEMES, useAppearance, type Theme } from "../theme/appearance";
import { Icon } from "./icons";

/** The theme switch in the top bar. Accent and density are in Settings, Appearance. */
export function ThemeQuickSwitch() {
  const { theme } = useAppearance();
  return (
    <span className="theme-quick">
      <Icon name={`theme-${theme}`} size={18} />
      <label htmlFor="theme-quick" className="visually-hidden">
        Theme
      </label>
      <select id="theme-quick" value={theme} onChange={(e) => setAppearance({ theme: e.target.value as Theme })}>
        {THEMES.map((t) => (
          <option key={t.id} value={t.id}>
            {t.label}
          </option>
        ))}
      </select>
    </span>
  );
}
