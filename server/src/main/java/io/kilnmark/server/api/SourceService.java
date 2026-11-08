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
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.SecretBox;
import io.kilnmark.server.sources.CommonSettings;
import io.kilnmark.server.sources.HostPolicy;
import io.kilnmark.server.sources.HostRemap;
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
                 String displayName, Map<String, String> keepSecretIf) {
    }

    /** Said when an edit tries to change a source's name. */
    static final String NAME_FIXED = "name: a source's name cannot change, because every asset's reference "
        + "(urn:kilnmark:<name>:...) includes it. Change its display name instead, or add a new source with the "
        + "new name";

    private final GraphStore store;
    private final SecretBox secrets;
    private final Connectors connectors;
    private final SourceChecks checks;

    private final HostPolicy hosts;

    public SourceService(GraphStore store, SecretBox secrets, Connectors connectors, SourceChecks checks,
                         KilnmarkConfig config) {
        this.store = store;
        this.secrets = secrets;
        this.connectors = connectors;
        this.checks = checks;
        this.hosts = HostPolicy.of(config);
    }

    /**
     * Checks the hosts a source would reach against the administrator's allow-list.
     *
     * @throws IllegalArgumentException for a 400 answer
     */
    void checkHosts(Connector connector, SourceConfig config) {
        hosts.check(connector, config);
    }

    Records.Source find(String name) {
        return store.source(name).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND, "No source called " + name));
    }

    /**
     * The connector for a kind a source may have here. On production the Starter Pack's example data
     * has none (ConnectorsProducer, ADR 0051): a source of it is refused, by hand or in a bulk import,
     * and said why.
     */
    Connector connector(String kind) {
        return connectors.get(kind).orElseThrow(() -> new IllegalArgumentException(
            io.kilnmark.server.starter.ModeCheck.SAMPLE_DATA_KIND.equals(kind)
                ? io.kilnmark.server.starter.ModeCheck.SAMPLE_DATA_REFUSED
                : "kind: unknown kind '" + kind + "'; this server supports "
                    + String.join(", ", connectors.all().stream().map(Connector::kind).toList())));
    }

    /** A saved source as its connector sees it, with its secret decrypted. */
    SourceConfig config(Records.Source s) {
        String secret = s.hasSecret() ? store.sourceSecret(s.name()).map(secrets::open).orElse(null) : null;
        return new SourceConfig(s.name(), s.kind(), s.settings(), secret);
    }

    private static final java.util.regex.Pattern DATABASE = java.util.regex.Pattern.compile("[A-Za-z0-9_$.-]{1,128}");

    /**
     * The settings that point a source at a host, port and database, checked first: a host name or
     * address, a port from 1 to 65535, a database name of letters, digits and _ $ . -.
     *
     * @throws IllegalArgumentException naming the field at fault, for a 400 answer
     */
    static Map<String, String> address(Connector connector, String host, String port, String database) {
        String h = host == null ? "" : host.trim();
        if (!HostRemap.validHost(h)) {
            throw new IllegalArgumentException("host: '" + h + "' is not a host name or address, such as "
                + "db.example.com or 10.0.4.12");
        }
        Integer p = null;
        if (port != null && !port.isBlank()) {
            try {
                p = Integer.parseInt(port.trim());
            } catch (NumberFormatException e) {
                p = -1;
            }
            if (p < 1 || p > 65535) {
                throw new IllegalArgumentException("port: a number from 1 to 65535, not '" + port.trim() + "'");
            }
        }
        String db = database == null || database.isBlank() ? null : database.trim();
        if (db != null && !DATABASE.matcher(db).matches()) {
            throw new IllegalArgumentException("database: letters, digits and _ $ . - only, not '" + db + "'");
        }
        Map<String, String> address = connector.addressSettings(h, p, db);
        if (address.isEmpty()) {
            throw new IllegalArgumentException("host: sources of kind " + connector.kind() + " do not take host, port "
                + "and database; use their own settings");
        }
        return address;
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
     * forms, which may not have a name yet. {@code admin}: whether the caller is an admin; for anyone
     * else, a kind only admins set up (generic JDBC) is refused (403) here, on the kind as it is read
     * below and before anything in the form is checked, since checking a URL already hands it to the
     * driver. Every path that makes or changes a source comes through here.
     *
     * @throws IllegalArgumentException with the first problem, for a 400 answer
     */
    Draft draft(SourceForm form, Records.Source existing, boolean checkName, boolean admin) {
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
        adminOnlyKind(connector, existing, form.settings() == null ? Map.of() : settings(form.settings()), admin);
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
        Map<String, String> keepSecretIf = Map.of();
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
            // Saving checks these again on the locked row, so a change made meanwhile cannot slip past.
            Map<String, String> scope = new HashMap<>();
            connector.secretScope().forEach(k -> scope.put(k, existing.settings().get(k)));
            keepSecretIf = scope;
        }
        if (CommonSettings.allowsSql(settings) && !SQL_KIND.equals(kind)) {
            throw new IllegalArgumentException(CommonSettings.ALLOW_SQL + ": only PostgreSQL sources can run SQL a person "
                + "writes for now, where Kilnmark can hold it to a read-only transaction (ADR 0028). Leave it off for "
                + "this " + kind + " source");
        }
        SourceConfig config = new SourceConfig(name, kind, settings, secret);
        String problem = SourceRules.check(connectors, config);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        hosts.check(connector, config);
        noUnmarkedCopy(config);
        return new Draft(connector, config, replace, replace ? secret : null, displayName, keepSecretIf);
    }

    /**
     * Refuses (403), for everyone, a folder or bucket source that would read files another source
     * already reads. Marks belong to the source that found the columns, so two sources over the same
     * files keep separate marks: a column marked sensitive in one would be catalogued unmarked in the
     * other, and its profiles, samples and previews would show the values to stewards, viewers and
     * admins. That holds for marks set later, or carried there by a pipeline's write, too, so any
     * overlap is refused, not only one with marks today. The server reaches folders, and buckets
     * through its own cloud role, without a login of the person's own, so nothing else would stop it.
     * Database sources need a login the person enters, which reads the same data directly, so they are
     * not checked here (ADR 0027).
     */
    void noUnmarkedCopy(SourceConfig config) {
        if (!FOLDER_KIND.equals(config.kind()) && !S3_KIND.equals(config.kind())) {
            return;
        }
        for (Records.Source other : store.sources()) {
            if (other.name().equals(config.name()) || !other.kind().equals(config.kind())
                || !sameFiles(config.kind(), config.settings(), other.settings())) {
                continue;
            }
            throw ApiError.of(Response.Status.FORBIDDEN, other.name() + " already reads these files (one folder or "
                + "prefix inside the other). Two sources over the same files keep separate marks, so a column marked "
                + "sensitive in one would show unmasked in the other. Use " + other.name() + ", or point this source "
                + "at files no other source reads.");
        }
    }

    static final String FOLDER_KIND = "files";
    static final String S3_KIND = "s3";

    /**
     * Whether two sources of {@code kind} read some of the same files: one folder or prefix inside the
     * other. Errs towards yes: folders are compared after following links and ignoring case, and
     * every way of naming AWS's own S3 service counts as one endpoint.
     *
     * <p>An S3 access point reads a bucket its name does not tell, so Kilnmark cannot see which bucket
     * is behind it: an alias ({@code -s3alias}, Object Lambda's {@code --ol-s3}, Outposts'
     * {@code --op-s3}, a multi-Region {@code .mrap}), an ARN, or an endpoint host that names a bucket or
     * an access point itself ({@code <bucket>.s3.<region>.amazonaws.com}, {@code s3-accesspoint},
     * Object Lambda, Outposts or VPC endpoint hosts: anything at amazonaws.com other than the plain
     * service hosts). Such a source counts as reading the same files as every other source at the same
     * endpoint, whatever the prefixes and however that one names its bucket. The one exception is two
     * ARNs, which name their partition and region ({@code arn:aws:s3:<region>:<account>:accesspoint/
     * <name>}): an access point reads only a bucket in its own partition and region, so two ARNs in
     * different partitions, or in different regions when both name one, cannot read the same files.
     * Their accounts are not compared, since an access point may front a bucket another account owns.
     * An alias cannot be told apart without asking AWS, and a bucket's region is not in its name, so an
     * alias, or an ARN beside a bucket name, still counts as the same files.
     */
    static boolean sameFiles(String kind, Map<String, String> a, Map<String, String> b) {
        if (FOLDER_KIND.equals(kind)) {
            java.nio.file.Path x = folder(a.get("path"));
            java.nio.file.Path y = folder(b.get("path"));
            return x != null && y != null && (x.startsWith(y) || y.startsWith(x));
        }
        if (S3_KIND.equals(kind)) {
            String bucket = trim(a.get("bucket"));
            String other = trim(b.get("bucket"));
            if (bucket == null || other == null || !endpoint(a.get("endpoint")).equals(endpoint(b.get("endpoint")))) {
                return false;
            }
            boolean hostA = namesBucket(a.get("endpoint"));
            boolean hostB = namesBucket(b.get("endpoint"));
            if (accessPoint(bucket) || accessPoint(other) || hostA || hostB) {
                return hostA || hostB || !apart(bucket, other);
            }
            if (!bucket.equalsIgnoreCase(other)) {
                return false;
            }
            String p = trim(a.get("prefix")) == null ? "" : trim(a.get("prefix"));
            String q = trim(b.get("prefix")) == null ? "" : trim(b.get("prefix"));
            return p.startsWith(q) || q.startsWith(p);
        }
        return false;
    }

    /**
     * Whether an S3 bucket setting names an access point, not a bucket: an alias ({@code -s3alias},
     * {@code --ol-s3} for Object Lambda, {@code --op-s3} for Outposts, {@code --<zone>--xa-s3} for a
     * directory bucket's, {@code .mrap} for a multi-Region one) or any ARN (a bucket name never holds
     * a colon). A directory bucket itself ({@code --x-s3}) is a bucket.
     */
    static boolean accessPoint(String bucket) {
        String b = bucket.toLowerCase(java.util.Locale.ROOT);
        return b.endsWith("-s3alias") || b.endsWith("--ol-s3") || b.endsWith("--op-s3") || b.endsWith("--xa--s3")
            || b.endsWith("--xa-s3") || b.endsWith(".mrap") || b.startsWith("arn:");
    }

    /** An S3 ARN's partition and region (empty for a multi-Region access point). */
    private record S3Arn(String partition, String region) {
    }

    /** The partition and region of an S3 ARN ({@code arn:<partition>:s3...:<region>:...}), or null. */
    private static S3Arn s3Arn(String bucket) {
        String[] parts = bucket.toLowerCase(java.util.Locale.ROOT).split(":", 6);
        if (parts.length < 6 || !parts[0].equals("arn") || parts[1].isEmpty() || !parts[2].startsWith("s3")) {
            return null;
        }
        return new S3Arn(parts[1], parts[3]);
    }

    /**
     * Whether two access points cannot read the same bucket: only when both are ARNs, in different
     * partitions, or in different regions where both name one. Anything else may be the same bucket.
     */
    private static boolean apart(String a, String b) {
        S3Arn x = s3Arn(a);
        S3Arn y = s3Arn(b);
        if (x == null || y == null) {
            return false;
        }
        if (!x.partition().equals(y.partition())) {
            return true;
        }
        return !x.region().isEmpty() && !y.region().isEmpty() && !x.region().equals(y.region());
    }

    /**
     * AWS's plain S3 service hosts, which name no bucket: {@code s3}, {@code s3-fips} or
     * {@code s3-external-1}, then dual-stack and region labels, at one of AWS's domains ({@link #aws}).
     * A bucket's name before them ({@code <bucket>.s3...}) never matches.
     */
    private static final java.util.regex.Pattern AWS_SERVICE_HOST = java.util.regex.Pattern.compile(
        "s3(-fips|-external-1)?([.-](dualstack|[a-z]{2,4}(-[a-z]+)+-[0-9]+))*\\.amazonaws\\.(com|com\\.cn|eu)");

    /**
     * Whether an endpoint's host names a bucket or an access point itself, so the bucket setting does
     * not tell what is read: any amazonaws.com host but AWS's plain service hosts, such as
     * {@code <bucket>.s3.<region>.amazonaws.com} or {@code <name>-<account>.s3-accesspoint.<region>
     * .amazonaws.com}.
     */
    static boolean namesBucket(String url) {
        String host = endpointHost(url);
        return host != null && aws(host) && !AWS_SERVICE_HOST.matcher(host).matches();
    }

    /** Whether a host is AWS's: at amazonaws.com, amazonaws.com.cn (China) or amazonaws.eu (sovereign cloud). */
    private static boolean aws(String host) {
        return host.equals("amazonaws.com") || host.endsWith(".amazonaws.com") || host.endsWith(".amazonaws.com.cn")
            || host.endsWith(".amazonaws.eu");
    }

    /** An endpoint's host, lower case and without a trailing dot, or null when there is none to read. */
    private static String endpointHost(String url) {
        String e = trim(url);
        if (e == null) {
            return null;
        }
        try {
            // No two slashes inside one literal: SightsUseTest strips line comments before strings.
            String host = java.net.URI.create(e.contains(":/") ? e : "https:" + "/" + "/" + e).getHost();
            return host == null ? null : withoutTrailingDot(host.toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException x) {
            return null;
        }
    }

    private static String withoutTrailingDot(String host) {
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    /** A folder as compared: absolute, normalised, links followed where it exists, lower case. */
    private static java.nio.file.Path folder(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            java.nio.file.Path p = java.nio.file.Path.of(path.trim()).toAbsolutePath().normalize();
            try {
                p = p.toRealPath();
            } catch (java.io.IOException e) {
                // not there (yet): compare it as written
            }
            return java.nio.file.Path.of(p.toString().toLowerCase(java.util.Locale.ROOT));
        } catch (java.nio.file.InvalidPathException e) {
            return null; // the source's own checks report a bad path
        }
    }

    /**
     * An S3 endpoint as compared: none, or any of AWS's hosts ({@link #aws}), is AWS's own service;
     * otherwise the lower-case host without a trailing dot, with its port, a default port (80, 443)
     * left out.
     */
    static String endpoint(String url) {
        String e = trim(url);
        if (e == null) {
            return "aws";
        }
        try {
            // No two slashes inside one literal: SightsUseTest strips line comments before strings.
            java.net.URI u = java.net.URI.create(e.contains(":/") ? e : "https:" + "/" + "/" + e);
            String host = u.getHost() == null ? e.toLowerCase(java.util.Locale.ROOT)
                : withoutTrailingDot(u.getHost().toLowerCase(java.util.Locale.ROOT));
            if (aws(host)) {
                return "aws";
            }
            int port = u.getPort();
            return port == -1 || port == 80 || port == 443 ? host : host + ":" + port;
        } catch (IllegalArgumentException x) {
            return e.toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Said when someone other than a designer or an admin tries to change whether a source may be written to. */
    static final String WRITING_ADMIN_ONLY = CommonSettings.ALLOW_WRITING + ": only designers and admins can let "
        + "pipelines write to a source, or make it read-only again. Ask a designer or an admin";

    /**
     * The edit form with {@code allow_writing} as only a designer or an admin ({@code designer}: a
     * role at least designer) may change it: for anyone else, a form that leaves it out keeps the
     * stored value, and one that changes it is refused.
     *
     * @throws jakarta.ws.rs.WebApplicationException 403 when someone else changes it
     */
    static SourceForm writingAsAllowed(SourceForm form, Records.Source existing, boolean designer) {
        if (designer || form == null || form.settings() == null) {
            return form;
        }
        String stored = existing.settings().get(CommonSettings.ALLOW_WRITING);
        String sent = form.settings().get(CommonSettings.ALLOW_WRITING);
        if (sent == null || sent.isBlank()) {
            if (stored == null) {
                return form;
            }
            Map<String, String> kept = new HashMap<>(form.settings());
            kept.put(CommonSettings.ALLOW_WRITING, stored);
            return new SourceForm(form.name(), form.kind(), kept, form.password(), form.clearPassword(), form.source(),
                form.displayName());
        }
        if (CommonSettings.allowsWriting(Map.of(CommonSettings.ALLOW_WRITING, sent))
            != CommonSettings.allowsWriting(existing.settings())) {
            throw ApiError.of(Response.Status.FORBIDDEN, WRITING_ADMIN_ONLY);
        }
        return form;
    }

    /** The one kind of source that may allow SQL a person writes, for now (ADR 0028). */
    static final String SQL_KIND = "postgresql";

    /** Said when someone below a designer tries to change whether SQL a person writes may run on a source. */
    static final String SQL_DESIGNERS_ONLY = CommonSettings.ALLOW_SQL + ": only designers and admins can let SQL a "
        + "person writes run on a source, or turn it off again. Ask a designer";

    /**
     * The edit form with {@code allow_sql} as only a designer or an admin may change it, as
     * {@link #writingAsAllowed} does for writing: for anyone else, a form that leaves it out keeps the
     * stored value, and one that changes it is refused.
     *
     * @throws jakarta.ws.rs.WebApplicationException 403 when someone else changes it
     */
    static SourceForm sqlAsAllowed(SourceForm form, Records.Source existing, boolean designer) {
        if (designer || form == null || form.settings() == null) {
            return form;
        }
        String stored = existing.settings().get(CommonSettings.ALLOW_SQL);
        String sent = form.settings().get(CommonSettings.ALLOW_SQL);
        if (sent == null || sent.isBlank()) {
            if (stored == null) {
                return form;
            }
            Map<String, String> kept = new HashMap<>(form.settings());
            kept.put(CommonSettings.ALLOW_SQL, stored);
            return new SourceForm(form.name(), form.kind(), kept, form.password(), form.clearPassword(), form.source(),
                form.displayName());
        }
        if (CommonSettings.allowsSql(Map.of(CommonSettings.ALLOW_SQL, sent))
            != CommonSettings.allowsSql(existing.settings())) {
            throw ApiError.of(Response.Status.FORBIDDEN, SQL_DESIGNERS_ONLY);
        }
        return form;
    }

    /**
     * Refuses (403) someone other than an admin a source of a kind only admins set up
     * ({@link Connector#adminOnly()}, such as generic JDBC, whose URL goes to a driver Kilnmark has
     * not vetted): a new one, or a change to the connector's own settings of a saved one. Stewards
     * may still check a saved source as it is.
     */
    static void adminOnlyKind(Connector connector, Records.Source existing, Map<String, String> settings,
                              boolean admin) {
        if (admin || !connector.adminOnly()) {
            return;
        }
        if (existing != null) {
            List<String> changed = new ArrayList<>();
            for (Connector.Setting s : connector.settings()) {
                if (!same(existing.settings().get(s.key()), settings.get(s.key()))) {
                    changed.add(s.key());
                }
            }
            if (changed.isEmpty()) {
                return;
            }
            throw ApiError.of(Response.Status.FORBIDDEN, "Only admins can change the " + String.join(", ", changed)
                + " of " + connector.displayName() + " sources: they go to a driver Kilnmark cannot vet. You can still "
                + "change the description and display name, and test, scan and preview the source as it is.");
        }
        throw ApiError.of(Response.Status.FORBIDDEN, "Only admins can set up " + connector.displayName() + " sources "
            + "or check unsaved ones: their URL goes to a driver Kilnmark cannot vet. Ask an admin.");
    }

    /** Settings that never decide where a source's data is: changing them never moves a pipeline's writes. */
    private static final java.util.Set<String> NOT_WHERE = java.util.Set.of(AssetFilter.INCLUDE, AssetFilter.EXCLUDE,
        CommonSettings.ALLOW_WRITING);

    /**
     * Refuses (403) someone other than an admin a change to where a source that allows writing
     * points (its address, bucket, folder, database, schemas, account): pipelines would write
     * somewhere no admin allowed. Description, display name and the like stay open to stewards.
     */
    static void whereUnchangedOrAdmin(Records.Source existing, Map<String, String> settings, boolean admin) {
        if (admin || !CommonSettings.allowsWriting(existing.settings())) {
            return;
        }
        Map<String, String> before = new java.util.TreeMap<>(reading(existing.settings()));
        Map<String, String> after = new java.util.TreeMap<>(reading(settings));
        NOT_WHERE.forEach(k -> {
            before.remove(k);
            after.remove(k);
        });
        if (before.equals(after)) {
            return;
        }
        java.util.Set<String> changed = new java.util.TreeSet<>(before.keySet());
        changed.addAll(after.keySet());
        changed.removeIf(k -> Objects.equals(before.get(k), after.get(k)));
        throw ApiError.of(Response.Status.FORBIDDEN, "Pipelines may write to " + existing.name() + ", so only an admin "
            + "can change where it points (" + String.join(", ", changed) + "). Ask an admin; they can also turn Allow "
            + "writing off first. You can still change its description and display name.");
    }

    private static boolean same(String a, String b) {
        String x = a == null || a.isBlank() ? null : a.trim();
        String y = b == null || b.isBlank() ? null : b.trim();
        return Objects.equals(x, y);
    }

    Views.TestResult test(Connector connector, SourceConfig config) {
        SourceChecks.TestResult r;
        try {
            r = checks.test(connector, config);
        } catch (SourceChecks.Busy e) {
            throw ApiError.of(Response.Status.TOO_MANY_REQUESTS, e.getMessage());
        }
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

    /** A page of the tables or files in one container, listed cheaply (metadata, a listing). */
    Views.AssetList assets(Connector connector, SourceConfig config, String container, int offset, int limit) {
        if (container == null) {
            throw new IllegalArgumentException("container: which schema or folder to list");
        }
        int from = Math.max(0, offset);
        int size = Math.max(1, Math.min(limit, 500));
        List<io.kilnmark.kiln.connect.AssetInfo> all = run(connector, config, "list " + container,
            () -> connector.assets(config, container));
        List<Views.AssetItem> page = all.stream().skip(from).limit(size)
            .map(a -> new Views.AssetItem(a.path(), a.name() == null ? a.path() : a.name(), a.kind(), a.readable()))
            .toList();
        return new Views.AssetList(container, all.size(), from, page);
    }

    /**
     * The first rows of one asset, read live through the connector's reader under the check time
     * limit and places. Nothing is stored, logged or sent to a model; the rows go to the person
     * asking and nowhere else.
     *
     * <p>A preview reads the asset named, whatever the source's include and exclude patterns say:
     * they choose what a scan records, not what a steward may look at.
     *
     * <p>Columns a steward marked sensitive on the catalogued asset (matched by name) are masked
     * through {@code Sights}, for everyone but designers with the unmask grant (ADR 0027), as in
     * every other view of the data. A table never scanned has no marks yet.
     */
    Views.Preview preview(Connector connector, SourceConfig config, String asset, Integer rows) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset: the path of the table or file to preview");
        }
        int n = rows == null ? 100 : rows;
        if (n < 1 || n > io.kilnmark.kiln.connect.Preview.MAX_ROWS) {
            throw new IllegalArgumentException("rows: from 1 to " + io.kilnmark.kiln.connect.Preview.MAX_ROWS);
        }
        java.util.concurrent.atomic.AtomicReference<String> path = new java.util.concurrent.atomic.AtomicReference<>();
        io.kilnmark.kiln.connect.Preview p = run(connector, config, "preview " + asset, () -> {
            io.kilnmark.kiln.connect.AssetInfo info = connector.asset(config, asset);
            path.set(info.path()); // the connector's own spelling, as the catalogue holds it
            return connector.preview(config, info, n);
        });
        return new Views.Preview(asset, p.columns().stream()
            .map(c -> new Views.PreviewColumn(c.name(), c.kind(), c.sourceType())).toList(),
            masked(config, path.get() == null ? asset : path.get(), p), p.rows().size(), p.truncated(), p.details());
    }

    /** The preview's rows with the catalogued asset's sensitive columns masked, for all but designers with the unmask grant. */
    private List<List<String>> masked(SourceConfig config, String path, io.kilnmark.kiln.connect.Preview p) {
        io.kilnmark.server.quality.Masking.Sight sight;
        try {
            sight = sights.catalogue(new io.kilnmark.graph.Urn(config.name(), path).toString());
        } catch (IllegalArgumentException e) {
            return p.rows(); // no asset can have that URN, so none has marks
        }
        List<Integer> hide = new ArrayList<>();
        for (int i = 0; i < p.columns().size(); i++) {
            if (!sight.values(p.columns().get(i).name())) {
                hide.add(i);
            }
        }
        if (hide.isEmpty()) {
            return p.rows();
        }
        List<List<String>> out = new ArrayList<>();
        for (List<String> row : p.rows()) {
            List<String> r = new ArrayList<>(row);
            hide.forEach(i -> {
                if (i < r.size() && r.get(i) != null) {
                    r.set(i, io.kilnmark.server.quality.Masking.MASK);
                }
            });
            out.add(r);
        }
        return out;
    }

    /** Settings that do not change what is read or how its columns are named. */
    private static final java.util.Set<String> NOT_READING = java.util.Set.of("description", "ai_egress", "display_name",
        "keep_samples", "sample_rows", "table_sample_above_rows", CommonSettings.MAX_RUNS);

    /**
     * A draft preview for a non-admin. New connections have no marks, so there is nothing to mask
     * and stewards preview them as they add them. But masking finds a table's sensitive columns by
     * the saved source's name and the column names it read, so for a saved source with columns
     * marked sensitive, changed reading settings (another folder, no header row, other patterns)
     * could read the same data under other names: only a designer with the unmask grant may preview those.
     */
    void previewDraftAllowed(Records.Source existing, SourceConfig draft) {
        Records.Source saved = existing != null ? existing : store.source(draft.name()).orElse(null);
        readingUnchangedOrUnmasked(saved, draft.settings(), "preview settings that are not saved");
    }

    /**
     * Refuses (403) a change of reading settings, or a preview of them, for a source with
     * columns marked sensitive, to anyone who sees them masked: read under other names, its
     * columns would no longer be masked. Description, display name and the like stay open to stewards.
     */
    void readingUnchangedOrUnmasked(Records.Source saved, Map<String, String> settings, String doing) {
        if (saved == null || sights.mayUnmask() || reading(saved.settings()).equals(reading(settings))
            || !sights.sourceHasMarks(saved.name())) {
            return;
        }
        throw ApiError.of(Response.Status.FORBIDDEN, "Only a designer who sees unmasked values can " + doing + " for "
            + saved.name() + ": it has columns marked sensitive, and other reading settings (folders, patterns, header "
            + "rows, formats) could read them under other names, unmasked. You can still change its description and "
            + "display name.");
    }

    private static Map<String, String> reading(Map<String, String> settings) {
        Map<String, String> out = new java.util.TreeMap<>();
        settings.forEach((k, v) -> {
            if (!NOT_READING.contains(k) && v != null && !v.isBlank()) {
                out.put(k, v.trim());
            }
        });
        return out;
    }

    /** Whether any asset of {@code source} has a column marked sensitive. */
    boolean hasMarks(String source) {
        return sights.sourceHasMarks(source);
    }

    /** Who is looking: columns marked sensitive are masked in previews for all but designers with the unmask grant. */
    @jakarta.inject.Inject
    io.kilnmark.server.quality.Sights sights;

    /**
     * One table's columns, keys, indexes and estimated rows, read live (Explore, ADR 0045), under
     * the check time limit and places. Names and types only: no value of the table is read.
     */
    io.kilnmark.kiln.connect.TableDetail explore(Connector connector, SourceConfig config, String asset) {
        explorable(connector, asset);
        return run(connector, config, "read the table " + asset,
            () -> connector.explore(config, asset, checks.timeout()));
    }

    /**
     * SQL for one table of the source, generated and never run (ADR 0045, D2). The table is read
     * live first, as Explore reads it, so names and columns are the catalogue's.
     */
    Views.GeneratedSql generateSql(Connector connector, SourceConfig config, SqlGenerator.Request request) {
        if (request == null) {
            throw new IllegalArgumentException("statement: what to generate");
        }
        explorable(connector, request.asset());
        io.kilnmark.kiln.plugins.jdbc.DdlWriter.Dialect dialect = SqlGenerator.dialect(request.dialect(), config.kind());
        io.kilnmark.kiln.connect.TableDetail table = explore(connector, config, request.asset());
        return SqlGenerator.generate(request, table, dialect,
            io.kilnmark.kiln.plugins.jdbc.DdlWriter.Dialect.forKind(config.kind()).orElse(null));
    }

    /** The exact number of rows of one table, counted by the database under the check time limit. */
    Views.RowCount countRows(Connector connector, SourceConfig config, String asset) {
        explorable(connector, asset);
        return new Views.RowCount(asset, run(connector, config, "count the rows of " + asset,
            () -> connector.countRows(config, asset, checks.timeout())));
    }

    private static void explorable(Connector connector, String asset) {
        if (!connector.explores()) {
            throw ApiError.of(Response.Status.NOT_FOUND, "Explore shows the tables of database sources only.");
        }
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset: the table to show, as schema.table");
        }
    }

    /** A response no browser or proxy keeps: a preview's rows are the source's data. */
    static Response noStore(Object body) {
        jakarta.ws.rs.core.CacheControl none = new jakarta.ws.rs.core.CacheControl();
        none.setNoStore(true);
        none.setNoCache(true);
        return Response.ok(body).cacheControl(none).build();
    }

    private <T> T run(Connector connector, SourceConfig config, String doing,
                      java.util.concurrent.Callable<T> work) {
        try {
            return checks.forPerson(connector, config, doing, work);
        } catch (SourceChecks.CheckFailed e) {
            throw ApiError.of(e.busy() ? Response.Status.TOO_MANY_REQUESTS : Response.Status.BAD_GATEWAY,
                e.getMessage());
        }
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
