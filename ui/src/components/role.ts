import { createContext, useContext } from "react";
import type { AccessRole } from "../api";

/** The signed-in role, for pages deep in the tree that show or hide controls by role. */
export const RoleContext = createContext<AccessRole>("viewer");

export const useRole = () => useContext(RoleContext);

/**
 * Whether the signed-in token may see unmasked values (`Me.unmask`, ADR 0027): only such a designer
 * writes SQL of their own in a read (ADR 0028), so the canvas shows the query read-only to everyone else.
 */
export const UnmaskContext = createContext<boolean>(false);

export const useUnmask = () => useContext(UnmaskContext);
