import { useEffect, useState } from "react";
import { starterPackApi } from "../api";

/** The event Settings › Starter Pack sends when it turns the pack on or off, so the banner follows at once. */
export const STARTER_PACK_CHANGED = "kilnmark:starter-pack";

/**
 * The banner every role sees while the Starter Pack is on (ADR 0051): the server says whether it
 * is, so no one can miss that this install holds example data and accounts. Read again on every
 * page change and when an admin turns the pack on or off.
 */
export function StarterPackBanner({ refresh }: { refresh?: unknown }) {
  const [on, setOn] = useState(false);

  useEffect(() => {
    let live = true;
    const read = () =>
      starterPackApi
        .state()
        .then((s) => live && setOn(s.on))
        .catch(() => live && setOn(false));
    read();
    window.addEventListener(STARTER_PACK_CHANGED, read);
    return () => {
      live = false;
      window.removeEventListener(STARTER_PACK_CHANGED, read);
    };
  }, [refresh]);

  if (!on) return null;
  return <StarterPackNotice />;
}

/** The banner's words, on their own for tests and for the Starter Pack page. */
export function StarterPackNotice() {
  return (
    <div className="km-starter-banner" role="status">
      <strong>Starter Pack:</strong> example data and accounts, not for production.
    </div>
  );
}
