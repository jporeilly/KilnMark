import { useEffect, useState } from "react";
import { Card, IconTile, PageHeader, type Tint } from "../components/ui";
import { allows, samplesApi, type AccessRole } from "../api";
import { useProductName } from "../components/Brand";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { Icon } from "../components/icons";
import { useBranding } from "../theme/looks";
import {
  ACCENTS,
  DEFAULTS,
  DENSITIES,
  setAppearance,
  THEMES,
  useAppearance,
  type Accent,
  type Density,
  type Theme,
} from "../theme/appearance";

/**
 * Settings: Appearance for everyone (their own view, in this browser), then the admin pages,
 * gathered in one place so the main menu stays about data.
 */
export function SettingsPage({ role }: { role: AccessRole }) {
  useTitle("Settings");
  const admin = allows(role, "admin");
  const product = useProductName();
  const items: { title: string; link: string; text: string; icon: string; tint: Tint; external?: boolean }[] = [
    { title: "API tokens", link: href.tokens(), icon: "key", tint: "amber", text: "Create, list and revoke tokens for people, scripts and AI assistants." },
    { title: "Email", link: href.email(), icon: "mail", tint: "green", text: "The mail server notifications go through, the rules for email, a test email, and team addresses." },
    { title: "Drivers", link: href.drivers(), icon: "connected", tint: "orange", text: "Drivers some databases need that cannot ship with Kilnmark: accept their licence and install them." },
    { title: "JDBC drivers", link: href.jdbcDrivers(), icon: "database", tint: "teal", text: "Drivers you bring for databases without a connector of their own, such as Db2 or Teradata: register them for Generic JDBC sources." },
    { title: "Audit log", link: href.audit(), icon: "timeline", tint: "slate", text: "Who changed what, and requests refused: tokens, marks, sources, Allow writing and Allow SQL." },
    { title: "Access review", link: href.accessReview(), icon: "users", tint: "slate", text: "Who can do what: every API token and everyone who signs in with your identity provider, when they last did anything, as a CSV for your auditors." },
    { title: "Starter Pack", link: href.starterPack(), icon: "library", tint: "amber", text: "Example data, samples ready to run and, where allowed, example people, for trials, demos and training: turn it on, and off again before real use." },
    { title: "Sent emails", link: href.emailLog(), icon: "mail-queued", tint: "slate", text: "Every email sent or tried in the last 30 days, with errors." },
    { title: "AI models", link: href.ai(), icon: "model", tint: "violet", text: "Local and hosted models, which feature uses which, and the rule for hosted models." },
    { title: "AI usage", link: href.aiUsage(), icon: "chart", tint: "sky", text: "Model calls by day and model, with failures and refusals." },
    {
      title: "API documentation",
      link: "/api/docs",
      icon: "external",
      tint: "slate",
      text: "Every endpoint, with its parameters and responses. Try them with an API token.",
      external: true,
    },
  ];
  return (
    <>
      <PageHeader
        title="Settings"
        lead={admin ? `How ${product} looks for you, and administration for this server.` : `How ${product} looks for you.`}
      />
      <Card title="Notifications" titleId="notifications-heading" icon={<Icon name="bell" size={18} />}
        actions={<a href={href.notifications()}>Choose what you get</a>} className="km-section">
        <p>
          What {product} tells you about in the app, and by email if you turn it on: failing rules and their recovery,
          new issues, failed scans and connection tests, drift, and pipeline runs.
        </p>
      </Card>
      <Appearance />
      {admin && (
        <section aria-labelledby="admin-heading" className="km-section">
          <h2 id="admin-heading">Administration</h2>
          <ul className="settings-list">
            {items.map((i) => (
              <li key={i.title} className="panel">
                <h3>
                  <IconTile tint={i.tint} size="sm">
                    <Icon name={i.icon} size={16} />
                  </IconTile>
                  <a href={i.link} {...(i.external ? { target: "_blank", rel: "noopener" } : {})}>
                    {i.title}
                    {i.external && <span className="visually-hidden"> (opens in a new tab)</span>}
                  </a>
                </h3>
                <p>{i.text}</p>
              </li>
            ))}
          </ul>
          <SamplesSwitch />
        </section>
      )}
    </>
  );
}

/**
 * Whether this server offers the samples: Add samples in the Library, the starter pack and
 * Kilnmark's sample data. Samples already added stay when they are turned off; the templates stay offered.
 */
function SamplesSwitch() {
  const [offered, setOffered] = useState<boolean | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [said, setSaid] = useState("");
  useEffect(() => {
    samplesApi
      .offered()
      .then((o) => setOffered(o.offered))
      .catch((e: Error) => setError(e.message));
  }, []);
  const change = async (to: boolean) => {
    setSaving(true);
    setError(null);
    setSaid("");
    try {
      const o = await samplesApi.setOffered(to);
      setOffered(o.offered);
      setSaid(o.offered ? "The samples are offered." : "The samples are no longer offered.");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };
  return (
    <div className="panel samples-switch">
      <h3 id="samples-switch-h">Samples</h3>
      <p>
        <label>
          <input
            type="checkbox"
            checked={offered ?? false}
            disabled={offered === null || saving}
            aria-describedby="samples-switch-hint"
            onChange={(e) => change(e.target.checked)}
          />{" "}
          Offer the samples
        </label>
      </p>
      <p id="samples-switch-hint" className="hint">
        Add samples in the Library, the starter pack and Kilnmark&apos;s sample data: small pipelines and flows that
        show one idea each, for learning. Turn them off on a server that should hold your own work only; any already
        added stay, and the Library&apos;s templates are still offered.
      </p>
      <p role="status" className="small">
        {said}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </div>
  );
}

/** Theme, accent and density for this viewer, saved in this browser; org branding, read-only. */
function Appearance() {
  const a = useAppearance();
  const branding = useBranding();
  const logos = Object.values(branding.connectors).filter((c) => c.image).length;
  const [brokenLogo, setBrokenLogo] = useState<string | null>(null);
  return (
    <section className="km-card appearance" aria-labelledby="appearance-heading">
      <h2 id="appearance-heading">Appearance</h2>
      <p className="hint">
        Your choices apply to you only, in this browser, and are saved straight away. If your browser does not keep
        site data, they last until you reload the page.
      </p>
      <fieldset>
        <legend>Theme</legend>
        <div className="option-grid">
          {THEMES.map((t) => (
            <label key={t.id} className="option">
              <input
                type="radio"
                name="theme"
                value={t.id}
                checked={a.theme === t.id}
                onChange={() => setAppearance({ theme: t.id as Theme })}
                aria-labelledby={`theme-${t.id}-label`}
                aria-describedby={`theme-${t.id}-hint`}
              />
              <span className="option-label" id={`theme-${t.id}-label`}>
                <Icon name={`theme-${t.id}`} size={18} />
                {t.label}
              </span>
              <span id={`theme-${t.id}-hint`} className="hint">
                {t.hint}
              </span>
            </label>
          ))}
        </div>
      </fieldset>
      <fieldset>
        <legend>Accent colour</legend>
        <p className="hint">
          Used for the current page, selections, links and main buttons. Each has a shade for every theme, checked for
          contrast. Orange is the default.
        </p>
        <div className="option-grid">
          {ACCENTS.map((x) => (
            <label key={x.id} className="option">
              <input
                type="radio"
                name="accent"
                value={x.id}
                checked={a.accent === x.id}
                onChange={() => setAppearance({ accent: x.id as Accent })}
              />
              <span className="option-label">
                <span className={`swatch swatch-${x.id}`} aria-hidden="true" />
                {x.label}
              </span>
            </label>
          ))}
        </div>
      </fieldset>
      <fieldset>
        <legend>Density</legend>
        <div className="option-grid">
          {DENSITIES.map((d) => (
            <label key={d.id} className="option">
              <input
                type="radio"
                name="density"
                value={d.id}
                checked={a.density === d.id}
                onChange={() => setAppearance({ density: d.id as Density })}
                aria-labelledby={`density-${d.id}-label`}
                aria-describedby={`density-${d.id}-hint`}
              />
              <span className="option-label" id={`density-${d.id}-label`}>
                {d.label}
              </span>
              <span id={`density-${d.id}-hint`} className="hint">
                {d.hint}
              </span>
            </label>
          ))}
        </div>
      </fieldset>
      <div className="buttons">
        <button
          type="button"
          onClick={() => setAppearance(DEFAULTS)}
          disabled={a.theme === DEFAULTS.theme && a.accent === DEFAULTS.accent && a.density === DEFAULTS.density}
        >
          Back to the defaults
        </button>
      </div>
      <section aria-labelledby="org-heading">
        <h3 id="org-heading">Organisation branding</h3>
        {branding.productName || branding.logo || Object.keys(branding.connectors).length > 0 ? (
          <dl className="org-brand">
            <div>
              <dt className="muted small">Product name</dt>
              <dd>{branding.productName ?? "Kilnmark (not changed)"}</dd>
            </div>
            <div>
              <dt className="muted small">Logo</dt>
              <dd>
                {!branding.logo ? (
                  "None"
                ) : brokenLogo === branding.logo ? (
                  "Set, but the image could not be loaded"
                ) : (
                  <img
                    className="org-logo"
                    src={branding.logo}
                    alt="The organisation's logo"
                    onError={() => setBrokenLogo(branding.logo)}
                  />
                )}
              </dd>
            </div>
            <div>
              <dt className="muted small">Connector logos and colours</dt>
              <dd>
                {Object.keys(branding.connectors).length} connectors, {logos} with a logo
              </dd>
            </div>
          </dl>
        ) : (
          <p>None set: Kilnmark's own name, and letter tiles for connectors.</p>
        )}
        <p className="hint">
          Read-only here. The server's operator sets these by putting a manifest and image files in the branding folder
          on the server (see the branding guide in the documentation).
        </p>
      </section>
    </section>
  );
}
