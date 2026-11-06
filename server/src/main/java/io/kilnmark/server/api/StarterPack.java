package io.kilnmark.server.api;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.SecretBox;
import io.kilnmark.server.ServerSettings;
import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.flows.FlowService;
import io.kilnmark.server.flows.FlowStore;
import io.kilnmark.server.pipelines.PipelineService;
import io.kilnmark.server.pipelines.PipelineViews;
import io.kilnmark.server.pipelines.Samples;
import io.kilnmark.server.quality.Sights;
import io.kilnmark.server.starter.ExampleAccounts;
import io.kilnmark.server.variables.VariableService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * The Starter Pack (ADR 0051): example data for trials, demos and training, turned on and off by an
 * admin under Settings. Kilnmark does all of it itself:
 *
 * <ul>
 * <li>on: a database source on {@code kilnmark_samples} (the empty database every install has on
 * Kilnmark's own PostgreSQL server, reached with its own login, which can connect to that database
 * only) and a files source on a folder of its own in the files folder, both with Allow writing on;
 * the sample tables and the samples in the Library reading them; then the banner;</li>
 * <li>off, once the admin has typed DELETE: the samples, those sources, the tables in
 * kilnmark_samples (the database stays, empty) and the files in the pack's folder.</li>
 * </ul>
 *
 * <p>Kilnmark holds no Keycloak credential: the pack's example people are made and removed by the
 * control script and keycloak-setup, with Keycloak's own admin, so no Kilnmark admin ever learns the
 * password of an example admin or of a designer who sees sensitive values. Kilnmark only shows which
 * kilnmark.com accounts have signed in, from its own audit log, and the commands.
 *
 * <p>The pack's two sources are remembered by their ids in its setting: a source of either name that
 * is not the pack's own is never changed or deleted. Refused on an install marked production
 * ({@code KILNMARK_PRODUCTION=true}) unless forced through the API. Each turn is one audit event,
 * with every step's outcome. Both are safe to repeat: what is already there is kept, what is
 * already gone is skipped.
 */
@ApplicationScoped
public class StarterPack {

    private static final Logger LOG = Logger.getLogger(StarterPack.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The server setting that says the pack is on, with when and by whom (JSON). */
    public static final String SETTING = "starter_pack";
    /** The pack's database source, on kilnmark_samples. */
    public static final String DATABASE_SOURCE = "starter-samples-db";
    /** The pack's files source, on its folder in the files folder. */
    public static final String FILES_SOURCE = "starter-samples-files";
    /** The pack's folder inside the files folder: its files, and nothing else, go there. */
    public static final String FOLDER = "starter-pack";
    /** The schema of kilnmark_samples the sample tables are created in. */
    public static final String SCHEMA = "public";
    /** What the admin types to turn the pack off. */
    public static final String CONFIRM = "DELETE";

    /** One step of turning the pack on or off: what, how it went (done, skipped, failed), and what happened. */
    public record Step(String step, String outcome, String detail) {
    }

    /** Whether the pack is on, since when and by whom: what the banner reads, for everyone. */
    public record State(boolean on, Instant since, String by) {
    }

    /**
     * The example people, which the control script makes and removes, never Kilnmark: the commands,
     * and the kilnmark.com accounts that have signed in here, as Kilnmark's audit log knows them.
     */
    public record People(String add, String remove, List<String> signedIn) {
    }

    /** The control script's commands for the example people, both editions. */
    public static final String PEOPLE_ADD = "kilnmark.sh starter people   (Windows: kilnmark.cmd starter people)";
    public static final String PEOPLE_REMOVE = "kilnmark.sh starter people --remove   (Windows: kilnmark.cmd starter people -Remove)";

    /** The pack in full, for admins on Settings, Starter Pack. */
    public record Details(boolean on, Instant since, String by, boolean production, boolean ready, String notReady,
                          String database, String files, PipelineViews.RunSummary tablesRun, int samples,
                          People people) {
    }

    /** What turning the pack on or off did: each step, and the sample tables' run. */
    public record Result(boolean on, List<Step> steps, PipelineViews.RunSummary tablesRun) {
    }

    private final KilnmarkConfig config;
    private final ServerSettings settings;
    private final GraphStore store;
    private final SecretBox secrets;
    private final SourceService sources;
    private final Samples samples;
    private final PipelineService pipelines;
    private final FlowService flows;
    private final FlowStore flowStore;
    private final VariableService variables;
    private final AuditLog audit;
    private final Object lock = new Object();

    public StarterPack(KilnmarkConfig config, ServerSettings settings, GraphStore store, SecretBox secrets,
                       SourceService sources, Samples samples, PipelineService pipelines, FlowService flows,
                       FlowStore flowStore, VariableService variables, AuditLog audit) {
        this.config = config;
        this.settings = settings;
        this.store = store;
        this.secrets = secrets;
        this.sources = sources;
        this.samples = samples;
        this.pipelines = pipelines;
        this.flows = flows;
        this.flowStore = flowStore;
        this.variables = variables;
        this.audit = audit;
    }

    // ---- state ----

    public State state() {
        JsonNode n = stored();
        boolean on = n.path("on").asBoolean(false);
        try {
            return new State(on, on && n.hasNonNull("since") ? Instant.parse(n.get("since").asText()) : null,
                on ? n.path("by").asText(null) : null);
        } catch (RuntimeException e) {
            return new State(on, null, null);
        }
    }

    private JsonNode stored() {
        Optional<String> v = settings.get(SETTING);
        try {
            return v.isEmpty() ? JSON.createObjectNode() : JSON.readTree(v.get());
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    /** The ids of the sources the pack made, by name: only these are ever changed or deleted by it. */
    private Map<String, Long> ownSources() {
        Map<String, Long> out = new LinkedHashMap<>();
        JsonNode sources = stored().path("sources");
        for (Map.Entry<String, JsonNode> e : sources.properties()) {
            if (e.getValue().canConvertToLong()) {
                out.put(e.getKey(), e.getValue().asLong());
            }
        }
        return out;
    }

    private void save(Boolean on, String by, Map<String, Long> own) {
        JsonNode was = stored();
        Map<String, Object> v = new LinkedHashMap<>();
        boolean isOn = on != null ? on : was.path("on").asBoolean(false);
        v.put("on", isOn);
        v.put("since", on != null || !was.hasNonNull("since") ? Instant.now().toString() : was.get("since").asText());
        v.put("by", on != null || !was.hasNonNull("by") ? by : was.get("by").asText()); // by: who changed it now
        v.put("sources", own);
        try {
            settings.put(SETTING, JSON.writeValueAsString(v), by);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Why the pack cannot be turned on here, or null. */
    String notReady() {
        if (config.samplesDb().password().filter(p -> !p.isBlank()).isEmpty()) {
            return "Kilnmark has no password for the Starter Pack's database: KILNMARK_SAMPLES_DB_PASSWORD in "
                + "config/.env. The control script sets it: run its up (kilnmark.sh up, or kilnmark.cmd up on Windows)";
        }
        if (filesRoot() == null) {
            return "There is no files folder (KILNMARK_FILES_ROOTS) for the samples to write to";
        }
        return null;
    }

    public Details details(Sights sights) {
        State s = state();
        String notReady = notReady();
        Samples.Catalogue c = settings.samplesOffered() ? samples.catalogue(sights) : null;
        int added = c == null ? 0 : (int) c.samples().stream().filter(Samples.SampleView::exists).count();
        PipelineViews.RunSummary run = c == null || c.tables() == null ? null : c.tables().lastRun();
        Path folder = folder();
        return new Details(s.on(), s.since(), s.by(), config.production(), notReady == null, notReady,
            DATABASE, folder == null ? null : folder.toString().replace('\\', '/'), run, added,
            new People(PEOPLE_ADD, PEOPLE_REMOVE, audit.providerAccess().stream().map(AuditLog.ProviderAccess::name)
                .filter(ExampleAccounts::isExample).toList()));
    }

    // ---- turning it on ----

    /**
     * Turns the pack on: the two sources, the sample tables and the samples. Refused (409) on an
     * install marked production unless {@code force}.
     */
    public Result turnOn(boolean force, String by, AccessRole role, Sights sights, AuditLog.Actor actor) {
        if (config.production() && !force) {
            throw ApiError.of(Response.Status.CONFLICT, "This install is marked as production (KILNMARK_PRODUCTION=true): "
                + "the Starter Pack, with its example data, is not for production, so it was not "
                + "turned on. Nothing was changed");
        }
        if (!settings.samplesOffered()) {
            throw ApiError.of(Response.Status.CONFLICT, "The samples are not offered on this server: turn on Offer the "
                + "samples under Settings, Administration first. Nothing was changed");
        }
        String notReady = notReady();
        if (notReady != null) {
            throw ApiError.of(Response.Status.CONFLICT, notReady + ". Nothing was changed");
        }
        synchronized (lock) {
            List<Step> steps = new ArrayList<>();
            try {
                checkDatabase();
                steps.add(new Step("database", "done", "kilnmark_samples answers its own login, over TLS"));
            } catch (RuntimeException e) {
                steps.add(new Step("database", "failed", e.getMessage()));
                audit.record(actor, AuditLog.STARTER_PACK_ADDED, "starter pack", Map.of("forced", force && config.production(),
                    "steps", outcomes(steps)));
                return new Result(state().on(), steps, null);
            }
            // On from here, so even a pack added in part shows the banner until it is turned off.
            save(true, by, ownSources());
            boolean ok = true;
            ok &= run(steps, "database source", () -> databaseSource(actor));
            ok &= run(steps, "files source", () -> filesSource(actor));
            PipelineViews.RunSummary[] tables = new PipelineViews.RunSummary[1];
            if (ok) {
                run(steps, "sample tables", () -> {
                    Samples.Catalogue c = samples.catalogue(sights);
                    PipelineViews.RunSummary last = c.tables() == null ? null : c.tables().lastRun();
                    if (last != null && DATABASE_SOURCE.equals(c.tables().source())
                        && List.of("queued", "running", "succeeded").contains(last.status())) {
                        tables[0] = last;
                        return "already there (run " + last.id() + ", " + last.status() + ")";
                    }
                    Samples.TablesResult r = samples.createTables(new Samples.TablesRequest(DATABASE_SOURCE, SCHEMA), by,
                        role, sights);
                    tables[0] = r.run();
                    return "being created in kilnmark_samples by the pipeline " + r.pipeline() + " (run " + r.run().id()
                        + ")";
                });
                run(steps, "samples", () -> {
                    Samples.AddResult r = samples.add(new Samples.AddRequest(Map.of("crm-db", DATABASE_SOURCE,
                        "shop-db", DATABASE_SOURCE, "files", FILES_SOURCE), SCHEMA), by, role, sights);
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    r.samples().forEach(o -> counts.merge(o.outcome(), 1, Integer::sum));
                    List<String> words = new ArrayList<>();
                    counts.forEach((k, n) -> words.add(n + " " + switch (k) {
                        case "exists" -> "already there";
                        case "skipped" -> "not added (they read with a query or run SQL: a designer who may see "
                            + "unmasked values adds them under Library, Add samples)";
                        default -> k;
                    }));
                    if (counts.containsKey("failed")) {
                        throw new IllegalStateException(String.join(", ", words));
                    }
                    return String.join(", ", words) + ", in the Library";
                });
            }
            audit.record(actor, AuditLog.STARTER_PACK_ADDED, "starter pack", Map.of("forced", force && config.production(),
                "steps", outcomes(steps)));
            LOG.infof("Starter Pack turned on by %s%s", by, force && config.production() ? ", forced on a production install" : "");
            return new Result(true, steps, tables[0]);
        }
    }

    private static Map<String, String> outcomes(List<Step> steps) {
        Map<String, String> m = new LinkedHashMap<>();
        steps.forEach(s -> m.put(s.step(), s.outcome()));
        return m;
    }

    private interface Work {
        String run();
    }

    private static boolean run(List<Step> steps, String step, Work work) {
        try {
            steps.add(new Step(step, "done", work.run()));
            return true;
        } catch (WebApplicationException e) {
            steps.add(new Step(step, "failed", e.getResponse().getEntity() instanceof ApiError a ? a.error()
                : "it could not be done (HTTP " + e.getResponse().getStatus() + ")"));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Starter Pack: %s failed", step);
            steps.add(new Step(step, "failed", e.getMessage() == null ? "it could not be done" : e.getMessage()));
        }
        return false;
    }

    /** The one database the pack uses. */
    public static final String DATABASE = "kilnmark_samples";

    /** Connects to kilnmark_samples as its own login and checks it is the pack's database ({@link #guard}). */
    private void checkDatabase() {
        try (Connection c = samplesConnection()) {
            guard(c);
        } catch (SQLException e) {
            throw new IllegalStateException("Kilnmark could not reach the Starter Pack's database " + DATABASE + " with "
                + "its own login. It is made at every start by the samples-db service: run the control script's up "
                + "again, then see its logs (logs samples-db)");
        }
    }

    /**
     * What every connection the pack makes to its database must be, before anything is done on it:
     * the database {@value #DATABASE}, over TLS, as a login that is not a superuser, holding none of
     * Kilnmark's own tables. So a setting pointed by mistake at Kilnmark's own database, or at
     * Kilnmark's login, is refused rather than emptied.
     */
    static void guard(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_database(), (SELECT ssl FROM pg_stat_ssl WHERE pid = "
                 + "pg_backend_pid()), (SELECT rolsuper FROM pg_roles WHERE rolname = current_user), "
                 + "to_regclass('kilnmark.flyway_schema_history') IS NOT NULL")) {
            if (!rs.next() || !DATABASE.equals(rs.getString(1))) {
                throw new IllegalStateException("The Starter Pack's database setting does not name " + DATABASE
                    + ": the pack works in that database only. Nothing was changed");
            }
            if (!rs.getBoolean(2)) {
                throw new IllegalStateException("The connection to " + DATABASE + " is not encrypted: it must demand TLS "
                    + "(sslmode=require). Nothing was changed");
            }
            if (rs.getBoolean(3)) {
                throw new IllegalStateException("The Starter Pack's login is a superuser: it must be its own login, "
                    + "kilnmark_samples, which can open no other database. Nothing was changed");
            }
            if (rs.getBoolean(4)) {
                throw new IllegalStateException(DATABASE + " holds Kilnmark's own tables: the pack never works there. "
                    + "Nothing was changed");
            }
        }
    }

    /**
     * Where the pack's database is: {@code kilnmark.samples-db.url}, or else kilnmark_samples on the
     * server of Kilnmark's own database, reached as Kilnmark reaches its own (ADR 0032: over TLS).
     */
    String samplesUrl() {
        Optional<String> set = config.samplesDb().url().map(String::strip).filter(u -> !u.isEmpty());
        if (set.isPresent()) {
            return set.get();
        }
        String own = ConfigProvider.getConfig().getOptionalValue("quarkus.datasource.jdbc.url", String.class).orElse("");
        return samplesUrl(own);
    }

    /** {@code own}, Kilnmark's own database's JDBC URL, with kilnmark_samples for its database, demanding TLS. */
    static String samplesUrl(String own) {
        java.util.regex.Matcher m = Pattern.compile("^(jdbc:postgresql:" + "/" + "/[^/?]+/)([^?]*)(\\?.*)?$").matcher(own);
        if (!m.matches()) {
            throw new IllegalStateException("Kilnmark cannot tell where its own database server is, so the Starter "
                + "Pack's database cannot be found: set KILNMARK_SAMPLES_DB_URL");
        }
        String query = m.group(3) == null ? "" : m.group(3);
        if (!query.contains("sslmode=")) {
            query = query.isEmpty() ? "?sslmode=require" : query + "&sslmode=require";
        }
        return m.group(1) + DATABASE + query;
    }

    private Connection samplesConnection() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", config.samplesDb().username());
        p.setProperty("password", config.samplesDb().password().orElse(""));
        p.setProperty("connectTimeout", "10");
        p.setProperty("ApplicationName", "Kilnmark Starter Pack");
        return DriverManager.getConnection(samplesUrl(), p);
    }

    private String databaseSource(AuditLog.Actor actor) {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("url", samplesUrl());
        s.put("username", config.samplesDb().username());
        s.put("schemas", SCHEMA);
        s.put("allow_writing", "true");
        // SQL a person writes runs only on PostgreSQL with Allow SQL on (ADR 0028): two samples need it
        s.put("allow_sql", "true");
        s.put("description", "The Starter Pack's sample data, in kilnmark_samples. Example data, not for production.");
        return source(DATABASE_SOURCE, "postgresql", s, config.samplesDb().password().orElse(null), "Starter Pack database",
            actor);
    }

    private String filesSource(AuditLog.Actor actor) {
        Path folder = folder();
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new IllegalStateException("Kilnmark could not make the folder " + FOLDER + " in the files folder: it "
                + "must be writable by Kilnmark (INSTALL.md, The files folder)");
        }
        Map<String, String> s = new LinkedHashMap<>();
        s.put("path", folder.toString().replace('\\', '/'));
        s.put("allow_writing", "true");
        s.put("description", "Where the Starter Pack's samples write their files. Example data, not for production.");
        return source(FILES_SOURCE, "files", s, null, "Starter Pack files", actor);
    }

    /**
     * Adds the source, or sets the pack's own source of that name as the pack needs it. A source of
     * that name the pack did not make (its id is not in the pack's setting) is left as it is.
     */
    private String source(String name, String kind, Map<String, String> settingsWanted, String password,
                          String displayName, AuditLog.Actor actor) {
        Records.Source existing = store.source(name).orElse(null);
        Map<String, Long> own = ownSources();
        if (existing != null && (!kind.equals(existing.kind()) || own.get(name) == null
            || own.get(name) != existing.id())) {
            throw new IllegalStateException("A source called " + name + " is here that the Starter Pack did not make: "
                + "it was left as it is. Rename it, then turn the pack on again");
        }
        SourceService.Draft d = sources.draft(new SourceForm(name, kind, settingsWanted, password, null, null,
            displayName), null, true, true);
        sources.checkHosts(d.connector(), d.config());
        byte[] secret = d.newSecret() == null ? null : secrets.seal(d.newSecret());
        if (existing == null) {
            Records.Source s = store.addSource(new Records.NewSource(name, kind, d.config().settings(), secret,
                d.displayName()));
            own.put(name, s.id());
            save(null, actor.name(), own);
            audit.sourceSettings(actor, s.name(), null, s.settings(), "starter pack");
            return "added " + name + " (" + displayName + "), Allow writing on";
        }
        Records.Source s = store.updateSource(existing.id(), new Records.SourceChange(d.displayName(), d.config().settings(),
            password != null, secret, Map.of())).orElseThrow(() -> new IllegalStateException(name + " was deleted meanwhile"));
        audit.sourceSettings(actor, s.name(), existing.settings(), s.settings(), "starter pack");
        return name + " was there already: set as the pack needs it";
    }

    // ---- turning it off ----

    /**
     * Turns the pack off, once the admin has typed {@value #CONFIRM}: everything it added, and nothing
     * else. The pack is off only when every step worked; the example people are the control script's.
     */
    public Result turnOff(String confirm, String by, AccessRole role, Sights sights, AuditLog.Actor actor) {
        if (!CONFIRM.equals(confirm)) {
            throw new IllegalArgumentException("confirm: type " + CONFIRM + " to remove the Starter Pack and its data");
        }
        synchronized (lock) {
            List<Step> steps = new ArrayList<>();
            boolean ok = true;
            ok &= run(steps, "sample flows", () -> {
                List<String> gone = new ArrayList<>();
                for (FlowStore.Summary f : flowStore.summaries()) {
                    if (Samples.LIBRARY.equals(f.flow().library())) {
                        flows.delete(f.flow().name(), by, role);
                        gone.add(f.flow().name());
                    }
                }
                return gone.size() + " removed from the Library";
            });
            ok &= run(steps, "sample pipelines", () -> {
                List<String> gone = new ArrayList<>();
                for (PipelineViews.PipelineSummary ps : pipelines.list(sights)) {
                    if (Samples.LIBRARY.equals(ps.library())) {
                        pipelines.delete(ps.name(), by);
                        gone.add(ps.name());
                    }
                }
                return gone.size() + " removed from the Library, " + Samples.SETUP + " among them";
            });
            ok &= run(steps, "sources", () -> {
                List<String> gone = new ArrayList<>();
                List<String> left = new ArrayList<>();
                Map<String, Long> own = ownSources();
                for (String name : List.of(DATABASE_SOURCE, FILES_SOURCE, Samples.SAMPLE_DATA)) {
                    Records.Source s = store.source(name).orElse(null);
                    if (s == null) {
                        own.remove(name);
                        continue;
                    }
                    if (Samples.SAMPLE_DATA.equals(name) ? !Samples.SAMPLE_DATA.equals(s.kind()) || stillRead(name, sights)
                        : own.get(name) == null || own.get(name) != s.id()) {
                        left.add(name); // not the pack's, or Kilnmark's sample data that your own work reads
                        continue;
                    }
                    Records.Deleted d = deleteWhenIdle(s);
                    if (d != null) {
                        audit.record(actor, AuditLog.SOURCE_DELETED, d.name(), Map.of("assets", d.assets(), "runs",
                            d.runs(), "marked", false, "via", "starter pack"));
                        gone.add(name);
                    }
                    own.remove(name);
                    save(null, by, own);
                }
                return (gone.isEmpty() ? "none was left to remove" : String.join(", ", gone) + " removed")
                    + (left.isEmpty() ? "" : "; left as they are, not the pack's or still read by your own work: "
                        + String.join(", ", left));
            });
            run(steps, "server variable", () -> {
                var v = variables.snapshot().containsKey(Samples.REGION_VARIABLE)
                    ? variables.get(Samples.REGION_VARIABLE, role) : null;
                if (v != null && v.description() != null && v.description().contains("starter pack")) {
                    variables.delete(Samples.REGION_VARIABLE, by, role);
                    return Samples.REGION_VARIABLE + " removed";
                }
                return "nothing of the pack's";
            });
            ok &= run(steps, "sample tables", this::emptyDatabase);
            ok &= run(steps, "sample files", this::removeFiles);
            if (ok) {
                save(false, by, ownSources());
            }
            audit.record(actor, AuditLog.STARTER_PACK_REMOVED, "starter pack", Map.of("steps", outcomes(steps)));
            LOG.infof("Starter Pack turned off by %s%s", by, ok ? "" : ", in part");
            return new Result(!ok, steps, null);
        }
    }

    /**
     * Deletes a source once no scan of it is queued or running: writing a sample's files can queue a
     * scan of the files source a moment before. Waits up to a minute, then says to try again.
     */
    private Records.Deleted deleteWhenIdle(Records.Source s) {
        for (int i = 0; ; i++) {
            try {
                return store.deleteSource(s.id()).orElse(null);
            } catch (GraphStore.SourceBusyException e) {
                if (i >= 60) {
                    throw new IllegalStateException(s.name() + " is being scanned: wait for the scan, then turn the "
                        + "pack off again");
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(s.name() + " could not be removed: the server is stopping");
                }
            }
        }
    }

    /** {@code source: <name>}, on a line of its own or inside {@code { ... }}, as YAML allows both. */
    private static final Pattern NAMES_SOURCE = Pattern.compile("(?m)(?:^|[\\s{,])source:\\s*[\"']?([A-Za-z0-9_.-]+)");

    /** Whether a pipeline or flow outside the Library still names {@code source}. */
    private boolean stillRead(String source, Sights sights) {
        for (FlowStore.Summary f : flowStore.summaries()) {
            if (Samples.LIBRARY.equals(f.flow().library())) {
                continue;
            }
            FlowStore.Version v = flowStore.version(f.flow().name(), f.flow().latestVersion()).orElse(null);
            if (v == null || names(v.yaml(), source)) {
                return true; // cannot tell, or it does: keep it
            }
        }
        for (PipelineViews.PipelineSummary ps : pipelines.list(sights)) {
            if (Samples.LIBRARY.equals(ps.library())) {
                continue;
            }
            try {
                PipelineViews.PipelineDetail d = pipelines.get(ps.name(), sights);
                if (d.latest() != null && names(d.latest().yaml(), source)) {
                    return true;
                }
            } catch (WebApplicationException e) {
                return true; // cannot tell: keep it
            }
        }
        return false;
    }

    private static boolean names(String yaml, String source) {
        java.util.regex.Matcher m = NAMES_SOURCE.matcher(yaml == null ? "" : yaml);
        while (m.find()) {
            if (source.equals(m.group(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Empties kilnmark_samples as its own login: every schema it owns and every table, view and
     * sequence it owns in public. The database itself stays, empty, for the next time.
     */
    String emptyDatabase() {
        if (config.samplesDb().password().filter(p -> !p.isBlank()).isEmpty()) {
            throw new IllegalStateException("Kilnmark has no password for kilnmark_samples (KILNMARK_SAMPLES_DB_PASSWORD), "
                + "so it could not empty it");
        }
        int schemas = 0;
        int relations = 0;
        try (Connection c = samplesConnection()) {
            guard(c);
            c.setAutoCommit(false);
            List<String> drop = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT nspname FROM pg_namespace WHERE nspowner = "
                + "(SELECT oid FROM pg_roles WHERE rolname = current_user) AND nspname <> 'public' ORDER BY 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    drop.add("DROP SCHEMA " + quote(rs.getString(1)) + " CASCADE");
                    schemas++;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT c.relname, c.relkind FROM pg_class c JOIN pg_namespace n "
                + "ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'f') "
                + "AND c.relowner = (SELECT oid FROM pg_roles WHERE rolname = current_user) AND NOT c.relispartition "
                + "ORDER BY 1"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = switch (rs.getString(2)) {
                        case "v" -> "VIEW";
                        case "m" -> "MATERIALIZED VIEW";
                        case "f" -> "FOREIGN TABLE";
                        default -> "TABLE";
                    };
                    drop.add("DROP " + kind + " IF EXISTS public." + quote(rs.getString(1)) + " CASCADE");
                    relations++;
                }
            }
            drop.add("DO $$ DECLARE s record; BEGIN FOR s IN SELECT c.relname FROM pg_class c JOIN pg_namespace n "
                + "ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind = 'S' AND c.relowner = "
                + "(SELECT oid FROM pg_roles WHERE rolname = current_user) LOOP EXECUTE format('DROP SEQUENCE IF EXISTS "
                + "public.%I CASCADE', s.relname); END LOOP; END $$");
            try (Statement st = c.createStatement()) {
                for (String sql : drop) {
                    st.execute(sql);
                }
            }
            c.commit();
        } catch (SQLException e) {
            LOG.warnf("Starter Pack: emptying kilnmark_samples failed: %s", e.getSQLState());
            throw new IllegalStateException("Kilnmark could not empty kilnmark_samples (" + e.getSQLState() + "): a run "
                + "may still be writing there. Wait for it, then turn the pack off again");
        }
        return "kilnmark_samples emptied (" + relations + " tables and views" + (schemas > 0 ? ", " + schemas + " schemas"
            : "") + "); the database stays, empty";
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** Deletes the pack's folder in the files folder, never following a link out of it. */
    String removeFiles() {
        Path folder = folder();
        if (folder == null || !Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            return "none was left";
        }
        if (Files.isSymbolicLink(folder)) {
            throw new IllegalStateException("The pack's folder " + FOLDER + " is a link: remove it yourself");
        }
        int[] count = {0};
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file); // a link is deleted, never what it points at
                    count[0]++;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    if (e != null) {
                        throw e;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Kilnmark could not delete every file in " + FOLDER + ": a file there may be "
                + "open, or not Kilnmark's to delete");
        }
        return count[0] + " files removed with the folder " + FOLDER;
    }

    /** The files folder the pack writes in: /srv/files when it is a root, else the first root. */
    private Path filesRoot() {
        List<String> roots = config.files().roots().orElse(List.of()).stream().filter(r -> r != null && !r.isBlank())
            .toList();
        if (roots.isEmpty()) {
            return null;
        }
        String root = roots.contains("/srv/files") ? "/srv/files" : roots.get(0);
        return Path.of(root.strip());
    }

    private Path folder() {
        Path root = filesRoot();
        return root == null ? null : root.resolve(FOLDER);
    }
}
