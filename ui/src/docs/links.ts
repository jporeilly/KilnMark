/** Links into the bundled documentation, kept apart from the pages themselves so linking costs nothing. */

/** The id of the changelog page, "What's new". */
export const WHATS_NEW = "whats-new";

/** The link to the documentation, a page of it, or one section of a page (#/docs/<id>/<slug>). */
export const docHref = (id?: string, section?: string | null) =>
  !id ? "#/docs" : `#/docs/${encodeURIComponent(id)}${section ? `/${encodeURIComponent(section)}` : ""}`;
