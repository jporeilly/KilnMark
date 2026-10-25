import { useEffect } from "react";
import { useProductName } from "./Brand";

/**
 * Sets the browser tab's title, so screen-reader users hear where they are after each move. Ends
 * with the organisation's product name when the branding folder sets one.
 */
export function useTitle(title: string) {
  const product = useProductName();
  useEffect(() => {
    document.title = title ? `${title} · ${product}` : product;
  }, [title, product]);
}
