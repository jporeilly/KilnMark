import { useEffect } from "react";

/** Sets the browser tab's title, so screen-reader users hear where they are after each move. */
export function useTitle(title: string) {
  useEffect(() => {
    document.title = title ? `${title} · Kilnmark` : "Kilnmark";
  }, [title]);
}
