import { createContext, useContext } from "react";
import type { AccessRole } from "../api";

/** The signed-in role, for pages deep in the tree that show or hide controls by role. */
export const RoleContext = createContext<AccessRole>("viewer");

export const useRole = () => useContext(RoleContext);
