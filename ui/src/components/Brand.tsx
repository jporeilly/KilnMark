import { useState } from "react";
import { useBranding } from "../theme/looks";

/** The first character as people see it (a grapheme: an accented letter or an emoji stays whole). */
function firstCharacter(text: string): string {
  if (typeof Intl !== "undefined" && "Segmenter" in Intl) {
    const first = new Intl.Segmenter(undefined, { granularity: "grapheme" }).segment(text)[Symbol.iterator]().next();
    if (!first.done) return first.value.segment;
  }
  return Array.from(text)[0] ?? "";
}

/** The product name people see: the organisation's own from the branding folder, or Kilnmark. */
export function useProductName(): string {
  return useBranding().productName ?? "Kilnmark";
}

/**
 * The mark and name at the top of every page. With org branding, the operator's logo (an <img>,
 * so nothing in it can run) and name; a logo that fails to load falls back to the built-in mark.
 * The logo is decorative: the name beside it says who it is.
 */
export function Brand() {
  const branding = useBranding();
  const [broken, setBroken] = useState<string | null>(null);
  const logo = branding.logo && broken !== branding.logo ? branding.logo : null;
  return (
    <>
      {logo ? (
        <img className="brand-logo" src={logo} alt="" onError={() => setBroken(logo)} />
      ) : (
        <span className="brand-mark" aria-hidden="true">
          {firstCharacter(branding.productName ?? "K").toUpperCase()}
        </span>
      )}
      {branding.productName ?? "Kilnmark"}
    </>
  );
}
