import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { apply } from "./theme/appearance";
import "./fonts";
import "./tokens.css";
import "./app.css";

// The viewer's theme, accent and density, before the first paint.
apply();

const root = document.getElementById("root");
if (!root) {
  throw new Error("Missing #root element");
}

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
