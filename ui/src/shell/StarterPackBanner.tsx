import { useEffect, useState } from "react";
import { starterPackApi, type StarterPackState } from "../api";

/** The event Settings › Starter Pack sends when it turns the pack on or off, so the banner follows at once. */
export const STARTER_PACK_CHANGED = "kilnmark:starter-pack";

/**
 * The marking every role sees on a Starter Pack install (ADR 0051): the server says the install's
 * mode, so no one can take a training and development install for a production one, and whether
 * the pack's example data is on. Nothing on a production install. Read again on every page change
 * and when an admin turns the pack on or off.
 */
export function StarterPackBanner({ refresh }: { refresh?: unknown }) {
  const [state, setState] = useState<StarterPackState | null>(null);

  useEffect(() => {
    let live = true;
    const read = () =>
      starterPackApi
        .state()
        .then((s) => live && setState(s))
        .catch(() => live && setState(null));
    read();
    window.addEventListener(STARTER_PACK_CHANGED, read);
    return () => {
      live = false;
      window.removeEventListener(STARTER_PACK_CHANGED, read);
    };
  }, [refresh]);

  if (!state || state.mode !== "starter-pack") return null;
  return <StarterPackNotice on={state.on} />;
}

/** The banner's words, on their own for tests and for the Starter Pack page. */
export function StarterPackNotice({ on }: { on: boolean }) {
  return (
    <div className="km-starter-banner" role="status">
      <strong>Starter Pack:</strong> training and development only.
      {on ? " Its example data and accounts are on." : ""}
    </div>
  );
}
