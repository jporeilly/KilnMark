package io.kilnmark.server.quality;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.kilnmark.server.auth.AccessRole;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

/**
 * Who is looking, for one request: the one place where the caller's roles and an asset's columns
 * marked sensitive become a {@link Masking.Sight}. Every resource, preview and MCP tool that shows
 * values from the data (profiles, samples, examples, evidence, drift, rules, results, failing
 * rows, exports, previews, document text) asks here; {@code SightsUseTest} fails the build when
 * one reads them without.
 */
@RequestScoped
public class Sights {

    /** The least role that may see data values. */
    public static final String DATA_ROLE = AccessRole.STEWARD_ROLE;
    /**
     * What a caller needs to see values of columns marked sensitive: the unmask grant, which only a
     * designer token holds, since designers build what transforms the data and cannot do it blind.
     * It is not a level on the role ladder, so admins, stewards and viewers see them masked (ADR 0027).
     */
    public static final String UNMASK_ROLE = AccessRole.UNMASK_GRANT;

    @Inject
    SecurityIdentity identity;
    @Inject
    QualityStore store;
    private final Map<String, Set<String>> marks = new HashMap<>();
    private boolean model;

    /** Whether the caller may see data values at all. */
    public boolean data() {
        return identity.hasRole(DATA_ROLE);
    }

    /**
     * Whether the caller may see values of columns marked sensitive, when asking to: a designer with
     * the unmask grant, and never while the request answers a model ({@link #speakingToModel}).
     */
    public boolean mayUnmask() {
        return identity.hasRole(UNMASK_ROLE) && !model;
    }

    /** The asset's columns marked sensitive (once per request). */
    public Set<String> marks(String urn) {
        return marks.computeIfAbsent(urn, store::sensitive);
    }

    /** Reads the marks of several assets in one query, for listings. */
    public void load(Collection<String> urns) {
        List<String> missing = urns.stream().filter(u -> !marks.containsKey(u)).distinct().toList();
        if (!missing.isEmpty()) {
            marks.putAll(store.sensitive(missing));
        }
    }

    /**
     * This request answers a model (the MCP server, AI pipeline suggestions): never unmasked, whoever's
     * token it uses. Call it before anything is read for the model.
     */
    public void speakingToModel() {
        model = true;
    }

    /**
     * Whether searches may match the text of every document, including those withheld because they
     * hold a column marked sensitive: for a designer with the unmask grant, never for a model.
     */
    public boolean everyText() {
        return mayUnmask() && !model;
    }

    private final Map<String, Boolean> sourceMarks = new HashMap<>();

    /** Whether any asset of the source has a column marked sensitive (once per request and source). */
    public boolean sourceHasMarks(String source) {
        return sourceMarks.computeIfAbsent(source, store::sourceHasMarks);
    }

    /** Quality views: values for roles that may see data; sensitive columns only for a designer who asks. */
    public Masking.Sight of(String urn, boolean unmask) {
        return new Masking.Sight(data(), marks(urn), unmask && mayUnmask());
    }

    /**
     * The catalogue's own views (an asset's column profiles, live previews): their statistics are
     * shown to every role, as before; sensitive columns are masked for all but designers.
     */
    public Masking.Sight catalogue(String urn) {
        return new Masking.Sight(true, marks(urn), mayUnmask());
    }

    /**
     * What an email to someone with {@code role} may show of an asset whose columns marked sensitive
     * are {@code marks} (notifications, which have no request): the quality views' rule, as
     * {@link #of} gives it for that role, without unmasking. A team address is written for a viewer.
     */
    public static Masking.Sight recipient(AccessRole role, Set<String> marks) {
        return new Masking.Sight(role.grants().contains(DATA_ROLE), marks, false);
    }

    /** What a model may be shown (the MCP server): never a sensitive column's values, whoever's token. */
    public Masking.Sight model(String urn) {
        return new Masking.Sight(true, marks(urn), false);
    }

    /**
     * Documents whose text is withheld from the caller: a document's text holds its sheets' cells
     * (a workbook), so when it or any part of it has a column marked sensitive, only a designer
     * with the unmask grant reads its text, finds in it or sees its snippets.
     */
    public Set<String> textWithheld(Collection<String> urns) {
        return mayUnmask() || urns.isEmpty() ? Set.of() : store.textWithheld(urns);
    }
}
