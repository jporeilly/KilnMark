/** What the assistant knows about where the person is: the documentation page to favour, and questions to suggest. */
import type { Route } from "../router";

/** The documentation page whose sections rank higher on this route, or null. */
export function pageHint(route: Route): string | null {
  switch (route.page) {
    case "docs":
      return route.doc;
    case "rules":
    case "issues":
      return "quality-rules";
    default:
      return null;
  }
}

const DEFAULT = ["What's new in this version?", "How do I set a data quality rule?"];

/** A few questions that suit the page the person is on. */
export function suggestions(route: Route): string[] {
  switch (route.page) {
    case "rules":
      return ["What does Fail above mean?", "How do I check a postcode format?", "Can I try a rule before saving it?"];
    case "issues":
      return ["When does a rule open a task?", "What does Could not run mean?"];
    case "asset":
      return ["How do I add a rule for this asset?", "How do rules affect the quality scores?"];
    case "ai":
    case "ai-usage":
      return ["How do I connect Ollama on this PC?", "Is my question sent to a hosted model?"];
    case "sources":
    case "source":
      return ["How do I add a database?", "Which kinds of source can Kilnmark read?"];
    default:
      return DEFAULT;
  }
}
