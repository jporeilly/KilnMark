package io.kilnmark.server.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.connect.AssetFilter;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.server.SecretBox;
import io.kilnmark.server.sources.CommonSettings;
import io.kilnmark.server.sources.SourceChecks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

/**
 * What adding, editing, testing and listing sources share: turning a form into a source a
 * connector can use, with the stored secret kept only where it is safe to keep it.
 */
@ApplicationScoped
public class SourceService {

    /** A name for checking a source that has none yet. */
    static final String UNNAMED = "new-source";

    /**
     * A source as the form describes it, ready for a connector. {@code replaceSecret} says whether
     * saving it changes the stored secret, to {@code newSecret} (null removes it).
     */
    record Draft(Connector connector, SourceConfig config, boolean replaceSecret, String newSecret,
                 String displayName) {
    }

    /** Said when an edit tries to change a source's name. */
    static final String NAME_FIXED = "name: a source's name cannot change, because every asset's reference "
        + "(urn:kilnmark:<name>:...) includes it. Change its display name instead, or add a new source with the "
        + "new name";

    private final GraphStore store;
    private final SecretBox secrets;
    private final Connectors connectors;
    private final SourceChecks checks;

    public SourceService(GraphStore store, SecretBox secrets, Connectors connectors, SourceChecks checks) {
        this.store = store;
        this.secrets = secrets;
        this.connectors = connectors;
        this.checks = checks;
    }

    Records.Source find(String name) {
        return store.source(name).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND, "No source called " + name));
    }

    Connector connector(String kind) {
        return connectors.get(kind).orElseThrow(() -> new IllegalArgumentException("kind: unknown kind '" + kind
            + "'; this server supports " + String.join(", ", connectors.all().stream().map(Connector::kind).toList())));
    }

    /** A saved source as its connector sees it, with its secret decrypted. */
    SourceConfig config(Records.Source s) {
        String secret = s.hasSecret() ? store.sourceSecret(s.name()).map(secrets::open).orElse(null) : null;
        return new SourceConfig(s.name(), s.kind(), s.settings(), secret);
    }

    /** Settings as sent, trimmed, without blanks, with common values written their one way. */
    static Map<String, String> settings(Map<String, String> sent) {
        return CommonSettings.normalise(SourceResource.clean(sent));
    }

    /**
     * The source a form describes. For an edit ({@code existing} not null) a blank password keeps the
     * stored secret, unless the form changes a setting that decides where the secret goes (the
     * connector's {@link Connector#secretScope()}): then the password must be entered again, so a
     * stored secret is never sent somewhere new. {@code checkName} is false for checks of unsaved
     * forms, which may not have a name yet.
     *
     * @throws IllegalArgumentException with the first problem, for a 400 answer
     */
    Draft draft(SourceForm form, Records.Source existing, boolean checkName) {
        if (form == null) {
            throw new IllegalArgumentException("Send the source as JSON");
        }
        String sentKind = form.kind() == null || form.kind().isBlank() ? null : form.kind().trim();
        if (existing != null && sentKind != null && !sentKind.equals(existing.kind())) {
            throw new IllegalArgumentException("kind: a source's kind cannot change. Add a new source of kind "
                + sentKind + " instead");
        }
        String kind = existing != null ? existing.kind() : sentKind == null ? "postgresql" : sentKind;
        Connector connector = connector(kind);
        String name = form.name() == null || form.name().isBlank()
            ? existing != null ? existing.name() : null : form.name().trim();
        if (existing != null && !name.equals(existing.name())) {
            throw new IllegalArgumentException(NAME_FIXED);
        }
        String displayName = form.displayName() == null || form.displayName().isBlank() ? null
            : form.displayName().strip();
        String displayProblem = SourceRules.displayNameProblem(displayName);
        if (displayProblem != null) {
            throw new IllegalArgumentException(displayProblem);
        }
        if (name == null && checkName) {
            throw new IllegalArgumentException("name: give the source a name");
        }
        if (!checkName && !SourceRules.validName(name)) {
            name = UNNAMED;
        }
        Map<String, String> settings = settings(form.settings());
        boolean newPassword = form.password() != null && !form.password().isEmpty();
        boolean clear = Boolean.TRUE.equals(form.clearPassword());
        if (newPassword && clear) {
            throw new IllegalArgumentException("password: give a new password or remove it, not both");
        }
        String secret = null;
        boolean replace = false;
        if (newPassword) {
            secret = form.password();
            replace = true;
        } else if (clear) {
            replace = existing != null;
        } else if (existing != null && existing.hasSecret()) {
            List<String> moved = new ArrayList<>(new TreeSet<>(connector.secretScope()).stream()
                .filter(k -> !same(existing.settings().get(k), settings.get(k))).toList());
            if (!moved.isEmpty()) {
                throw new IllegalArgumentException("password: you changed " + String.join(" and ", moved)
                    + ", so enter the password again. Kilnmark never sends a stored password to a new place");
            }
            secret = store.sourceSecret(existing.name()).map(secrets::open).orElse(null);
        }
        SourceConfig config = new SourceConfig(name, kind, settings, secret);
        String problem = SourceRules.check(connectors, config);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        return new Draft(connector, config, replace, replace ? secret : null, displayName);
    }

    private static boolean same(String a, String b) {
        String x = a == null || a.isBlank() ? null : a.trim();
        String y = b == null || b.isBlank() ? null : b.trim();
        return Objects.equals(x, y);
    }

    Views.TestResult test(Connector connector, SourceConfig config) {
        SourceChecks.TestResult r = checks.test(connector, config);
        return new Views.TestResult(r.ok(), r.problem(), r.millis());
    }

    /** What a source holds and what a scan of it would read now. */
    Views.ContainerList containers(Connector connector, SourceConfig config) {
        List<Connector.ContainerInfo> found;
        try {
            found = checks.containers(connector, config);
        } catch (SourceChecks.CheckFailed e) {
            throw ApiError.of(e.busy() ? Response.Status.TOO_MANY_REQUESTS : Response.Status.BAD_GATEWAY,
                e.getMessage());
        }
        return containerList(connector.containerKind(), config, found);
    }

    static Views.ContainerList containerList(Connector.ContainerKind kind, SourceConfig config,
                                             List<Connector.ContainerInfo> found) {
        List<String> chosen = config.list(kind.setting());
        AssetFilter filter = AssetFilter.of(chosen, null, null);
        List<Views.ContainerItem> items = new ArrayList<>();
        long total = 0;
        for (Connector.ContainerInfo c : found) {
            boolean picked = !chosen.isEmpty() && filter.container(c.name());
            items.add(new Views.ContainerItem(c.name(), c.count(), picked));
            if (chosen.isEmpty() || picked) {
                total += c.count();
            }
        }
        List<String> missing = chosen.stream().filter(n -> found.stream()
            .noneMatch(c -> c.name().equals(n) || c.name().equalsIgnoreCase(n))).toList();
        String label = kind.label().toLowerCase(Locale.ROOT);
        List<String> warnings = new ArrayList<>();
        if (!missing.isEmpty()) {
            warnings.add("Not found in the source, so not scanned: " + String.join(", ", missing)
                + ". Choose the " + label + " again.");
        }
        if (total == 0) {
            if (found.isEmpty()) {
                warnings.add("The source holds no " + label + " Kilnmark can read, so a scan would find nothing. "
                    + "Check the settings and the account's permissions.");
            } else if (chosen.isEmpty() || !items.stream().filter(Views.ContainerItem::selected).toList().isEmpty()) {
                warnings.add("No " + kind.counted() + " match the source's choices and its include and exclude "
                    + "patterns, so a scan would find nothing. Choose other " + label + " or change the patterns.");
            } else {
                warnings.add("None of the chosen " + label + " is in the source, so a scan would find nothing.");
            }
        }
        return new Views.ContainerList(kind.setting(), kind.label(), kind.counted(), items, chosen, missing, total,
            total == 0, warnings);
    }

    /** Settings to store: the draft's, keeping keys in a stable order. */
    static Map<String, String> stored(SourceConfig config) {
        return new HashMap<>(config.settings());
    }
}
