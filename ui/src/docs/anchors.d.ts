/** Types for anchors.js (plain JavaScript so the pre-build Node check needs no TypeScript). */

/** A section heading of a page, with the slug that addresses it. */
export type DocHeading = { depth: number; text: string; slug: string };

export function plainHeading(markdown: string): string;
export function slug(heading: string): string;
export function uniqueSlug(base: string, seen: Map<string, number>): string;
export function markdownHeadings(markdown: string): DocHeading[];
