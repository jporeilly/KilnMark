import { href } from "../router";
import { useTitle } from "../components/useTitle";

/** Settings: the admin pages, gathered in one place so the main menu stays about data. */
export function SettingsPage() {
  useTitle("Settings");
  const items: { title: string; link: string; text: string; external?: boolean }[] = [
    { title: "API tokens", link: href.tokens(), text: "Create, list and revoke tokens for people, scripts and AI assistants." },
    { title: "AI models", link: href.ai(), text: "Local and hosted models, which feature uses which, and the rule for hosted models." },
    { title: "AI usage", link: href.aiUsage(), text: "Model calls by day and model, with failures and refusals." },
    {
      title: "API documentation",
      link: "/api/docs",
      text: "Every endpoint, with its parameters and responses. Try them with an API token.",
      external: true,
    },
  ];
  return (
    <>
      <h1 tabIndex={-1}>Settings</h1>
      <p className="lead">Administration for this Kilnmark server.</p>
      <ul className="settings-list">
        {items.map((i) => (
          <li key={i.title} className="panel">
            <h2>
              <a href={i.link} {...(i.external ? { target: "_blank", rel: "noopener" } : {})}>
                {i.title}
                {i.external && <span className="visually-hidden"> (opens in a new tab)</span>}
              </a>
            </h2>
            <p>{i.text}</p>
          </li>
        ))}
      </ul>
    </>
  );
}
