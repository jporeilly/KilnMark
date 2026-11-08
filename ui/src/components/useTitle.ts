import { useEffect } from "react";
import { useProductName } from "./Brand";

/**
 * The browser tab's title, so screen-reader users hear where they are after each move, ending with
 * the organisation's product name when the branding folder sets one.
 *
 * Two parts make it, so it is never left over from the page before: the route's own title, which the
 * app sets on every move (useRouteTitle, from routeTitle in router.ts), and the title a page sets
 * while it is shown (useTitle), which can say more (an asset's or a source's name, a guide's
 * title). The page's wins while it is shown; when it goes, the route's is back at once.
 */
let routeTitle = "";
let product = "Kilnmark";
const shown: { title: string }[] = [];

function write() {
  const title = shown.length ? shown[shown.length - 1].title : routeTitle;
  document.title = title ? `${title} · ${product}` : product;
}

/** The title of the page shown: it replaces the route's while the page is there. */
export function useTitle(title: string) {
  const name = useProductName();
  useEffect(() => {
    product = name;
    const mine = { title };
    shown.push(mine);
    write();
    return () => {
      shown.splice(shown.indexOf(mine), 1);
      write();
    };
  }, [title, name]);
}

/** The route's title, set by the app on every move, under whatever its page sets. */
export function useRouteTitle(title: string) {
  const name = useProductName();
  useEffect(() => {
    product = name;
    routeTitle = title;
    write();
  }, [title, name]);
}
