/**
 * Where the view goes to show the whole pipeline: its nodes' bounds, fitted into the part of the
 * canvas not under the node list, without going under the overview map in the bottom-right corner.
 * The map covers a corner, so the nodes are fitted either above it or to its left, whichever leaves
 * them larger; centred in what is left. Pure, so it can be tested.
 */
export type Box = { x: number; y: number; width: number; height: number };
/** The visible part of the canvas, in canvas pixels from its top-left. */
export type Area = { left: number; top: number; width: number; height: number };
export type Viewport = { x: number; y: number; zoom: number };

/** Space around the nodes, and between them and the map. */
export const FIT_PAD = 28;

/** The zoom at which `bounds` fits in `w` by `h`, never above `most`. */
const zoomFor = (bounds: Box, w: number, h: number, most: number) =>
  Math.min(most, (w - 2 * FIT_PAD) / Math.max(bounds.width, 1), (h - 2 * FIT_PAD) / Math.max(bounds.height, 1));

/**
 * The area to fit into: all of `area` when there is no map (or it is hidden), else the larger fit of
 * the band above the map and the band to its left. `map` is in the same canvas pixels as `area`.
 */
export function roomBesideMap(bounds: Box, area: Area, map: Box | null, most = 1): Area {
  if (!map || map.width <= 0 || map.height <= 0) return area;
  const above: Area = { ...area, height: Math.max(0, map.y - area.top) };
  const left: Area = { ...area, width: Math.max(0, map.x - area.left) };
  const whole = zoomFor(bounds, area.width, area.height, most);
  const zAbove = zoomFor(bounds, above.width, above.height, most);
  const zLeft = zoomFor(bounds, left.width, left.height, most);
  // The whole area when even there the nodes, centred, stay clear of the map.
  const fitted = viewportIn(bounds, area, whole);
  const drawn = { x: fitted.x + bounds.x * whole, y: fitted.y + bounds.y * whole, width: bounds.width * whole, height: bounds.height * whole };
  if (!overlaps(drawn, map)) return area;
  return zAbove >= zLeft ? above : left;
}

/** The viewport that shows `bounds` at `zoom`, centred in `area`. */
export function viewportIn(bounds: Box, area: Area, zoom: number): Viewport {
  return {
    x: area.left + (area.width - bounds.width * zoom) / 2 - bounds.x * zoom,
    y: area.top + (area.height - bounds.height * zoom) / 2 - bounds.y * zoom,
    zoom,
  };
}

/** Fits `bounds` (at most `most`, at least `least`) into the area beside the map, centred. */
export function fitBesideMap(bounds: Box, area: Area, map: Box | null, most = 1, least = 0.1): Viewport {
  const room = roomBesideMap(bounds, area, map, most);
  return viewportIn(bounds, room, Math.max(least, zoomFor(bounds, room.width, room.height, most)));
}

/** How large the nodes would be if fitted beside the map (to choose between fitting and reading). */
export const fitZoom = (bounds: Box, area: Area, map: Box | null, most = 1) => {
  const room = roomBesideMap(bounds, area, map, most);
  return zoomFor(bounds, room.width, room.height, most);
};

export const overlaps = (a: Box, b: Box) =>
  a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
