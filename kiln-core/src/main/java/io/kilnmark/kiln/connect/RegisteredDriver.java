package io.kilnmark.kiln.connect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A JDBC driver an admin registered for the generic JDBC connector (docs/design/drivers.md,
 * "Registered drivers"): a jar in the drivers folder Kilnmark cannot pin in advance, recorded with
 * the SHA-256 it had when it was registered. It is loaded only while the file still has that
 * checksum, in a class loader of its own, exactly like a pinned driver ({@link DriverFolder}).
 *
 * @param name         what sources call it ({@code db2}, {@code teradata}): lower-case letters,
 *                     digits, {@code -} and {@code _}
 * @param fileName     the jar's name in the drivers folder, nothing more (no folder)
 * @param sha256       the jar's SHA-256 when it was registered, lower-case hex
 * @param driverClass  the {@link java.sql.Driver} class in the jar
 * @param urlPrefix    how the JDBC URLs this driver takes start, such as {@code jdbc:db2://}; a
 *                     source's URL must start with it
 * @param allowedProperties the driver properties sources may set, in the URL or as
 *                     {@code properties}, lower case: an allow-list the admin gives, since Kilnmark
 *                     cannot know which of a driver's properties read files, run scripts or reach
 *                     other hosts. Empty: none
 * @param registeredBy who registered it
 * @param registeredAt when
 */
public record RegisteredDriver(String name, String fileName, String sha256, String driverClass, String urlPrefix,
                               List<String> allowedProperties, String registeredBy, Instant registeredAt) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,195}\\.jar");
    private static final Pattern CLASS = Pattern.compile(
        "(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern PREFIX = Pattern.compile("jdbc:[A-Za-z][A-Za-z0-9+._-]*:[A-Za-z0-9:/@+._-]*");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PROPERTY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");
    /**
     * The settings any registered driver may be allowed (ADR 0030): names Kilnmark knows only tune a
     * connection to a database server, never load code, read the server's files or sign in as the
     * server. Anything else is refused, whatever the admin types.
     */
    static final List<String> VETTED = List.of(
        // time limits
        ".*timeout.*",
        // TLS: on, its mode and protocols, how the server's certificate is checked
        "ssl", "usessl", "requiressl", "sslmode", "sslconnection", "sslenabled", "sslprotocol", "encrypt", "tls",
        "tlsmode", "enabledtlsprotocols", "trustservercertificate", "hostnameincertificate", "validatecertificate",
        "verifyservercertificate",
        // characters
        "charset", "characterset", "characterencoding", "encoding", "clientcharset", "useunicode",
        // where to read: database, schema, catalog, warehouse, role
        "database", "databasename", "db", "schema", "currentschema", "catalog", "warehouse", "role", "currentsqlid",
        // how the connection names itself
        "applicationname", "application_name", "appname", "programname", "clientprogramname", "applicationintent",
        // how much travels at once
        "fetchsize", "defaultrowfetchsize", "batchsize", "packetsize", "prefetch", "prefetchsize", "maxrows",
        "usecursorfetch", "compress", "compression", "usecompression",
        // time zones and dates
        "timezone", "servertimezone", "sessiontimezone", "connectiontimezone", "zerodatetimebehavior",
        // keeping the connection up, and the other servers to try (their hosts are checked as the URL's)
        "tcpkeepalive", "keepalive", "failoverpartner", "multisubnetfailover", "loadbalancehosts",
        "targetservertype", "clientreroutealternateservername", "clientreroutealternateportnumber",
        // a few databases' own harmless settings
        "readonly", "tmode", "dbs_port", "encryptdata", "informixserver", "db_locale", "mode", "db_close_delay",
        "create", "transportmode");
    private static final List<Pattern> VETTED_PATTERNS = VETTED.stream().map(Pattern::compile).toList();

    /** Whether {@code name} (lower case) is one of the settings a driver may be allowed ({@link #VETTED}). */
    public static boolean vetted(String name) {
        return name != null && VETTED_PATTERNS.stream().anyMatch(p -> p.matcher(name).matches());
    }

    /** Names that hold a secret: never allowed, as settings and URLs are stored and shown as plain text. */
    private static final Pattern SECRET = Pattern.compile(".*(pass|pwd|secret|token|key|credential).*");

    public RegisteredDriver {
        allowedProperties = allowedProperties == null ? List.of() : List.copyOf(allowedProperties);
        List<String> problems = problems(name, fileName, driverClass, urlPrefix);
        problems.addAll(propertyProblems(allowedProperties));
        if (sha256 == null || !SHA.matcher(sha256).matches()) {
            problems.add("sha256: 64 lower-case hex digits");
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
    }

    /**
     * The problems with what an admin typed to register a driver, each starting with the field it is
     * about; empty when it may be registered.
     */
    public static List<String> problems(String name, String fileName, String driverClass, String urlPrefix) {
        List<String> out = new ArrayList<>();
        if (name == null || !NAME.matcher(name).matches()) {
            out.add("name: 1 to 40 lower-case letters, digits, - and _, starting with a letter or digit, such as db2");
        }
        if (fileName == null || !FILE.matcher(fileName).matches() || fileName.contains("..")) {
            out.add("fileName: the jar's name in the drivers folder, such as jcc-12.1.0.0.jar, with no folder");
        }
        if (driverClass == null || driverClass.length() > 300 || !CLASS.matcher(driverClass).matches()) {
            out.add("driverClass: the driver's class name, such as com.ibm.db2.jcc.DB2Driver");
        }
        if (urlPrefix == null || urlPrefix.length() > 100 || !PREFIX.matcher(urlPrefix).matches()) {
            out.add("urlPrefix: how this driver's URLs start, such as jdbc:db2:// or jdbc:teradata://");
        }
        return out;
    }

    /**
     * A list of property names as an admin types it (commas, spaces or new lines between them), lower
     * case, each once, sorted.
     */
    public static List<String> propertyList(String typed) {
        if (typed == null || typed.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(typed.split("[,;\\s]+")).map(String::strip).filter(s -> !s.isEmpty())
            .map(s -> s.toLowerCase(java.util.Locale.ROOT)).distinct().sorted().toList();
    }

    /** The problems with an allow-list of property names; empty when every name may be allowed. */
    public static List<String> propertyProblems(List<String> names) {
        List<String> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        if (names.size() > 200) {
            out.add("allowedProperties: at most 200 names");
        }
        for (String n : names) {
            if (n == null || !PROPERTY.matcher(n).matches()) {
                out.add("allowedProperties: a property's name is letters, digits, . _ and -, not '" + n + "'");
            } else if (isSecretName(n) || n.equals("user")) {
                out.add("allowedProperties: " + n + " holds a user name or secret; sources give those in their own "
                    + "fields, which keep the secret encrypted");
            } else if (readsServerFiles(n)) {
                out.add("allowedProperties: " + n + " names a file, folder, class or key store on the Kilnmark "
                    + "server, which no source may set");
            } else if (!vetted(n)) {
                out.add("allowedProperties: " + n + " is not a setting Kilnmark has vetted; a driver may be allowed "
                    + "only time limits, TLS settings, characters, database, schema, catalog, warehouse or role, "
                    + "the application name, fetch and batch sizes, time zones, keep-alive and failover servers "
                    + "(docs/guide/drivers.md)");
            }
        }
        return out;
    }

    /** Whether a property's name says it holds a secret (pass, pwd, secret, token, key, credential), ignoring case. */
    public static boolean isSecretName(String name) {
        return name != null && SECRET.matcher(name.toLowerCase(java.util.Locale.ROOT)).matches();
    }

    /**
     * Whether a property's name says it reads or writes the server's files, loads code or acts as the
     * server (ends in file, path, dir, directory, factory, class or classname; a trust or key store;
     * javax.net; an interceptor, verifier, callback, provider, strategy, transform, plugin, checker,
     * handler or logger; a location; a client certificate; MySQL's deserialising and local-file
     * loading; any authentication setting, which can sign in as the server through Windows, Kerberos,
     * JAAS, GSS or a cloud identity), so no allow-list may hold it.
     */
    public static boolean readsServerFiles(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith("file") || n.endsWith("path") || n.endsWith("dir") || n.endsWith("directory")
            || n.endsWith("factory") || n.endsWith("class") || n.endsWith("classname") || n.contains("truststore")
            || n.contains("keystore") || n.startsWith("javax.") || n.endsWith("tns_admin")
            || n.contains("interceptor") || n.contains("verifier") || n.contains("callback") || n.contains("provider")
            || n.contains("strategy") || n.contains("location") || n.contains("autodeserialize")
            || n.contains("localinfile") || n.contains("integratedsecurity") || n.contains("transform")
            || n.contains("plugin") || n.contains("checker") || n.contains("handler") || n.contains("logger")
            || n.contains("authentication") || n.contains("authenticator") || n.contains("kerberos")
            || n.contains("jaas") || n.contains("gss") || n.contains("securitymechanism") || n.endsWith("cert")
            || n.contains("clientcertificate");
    }

    /** Whether sources may set the property {@code name} (ignoring case) with this driver. */
    public boolean allows(String name) {
        return name != null && allowedProperties.contains(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** Whether a JDBC URL starts as this driver's do (the scheme ignoring case, as drivers compare it). */
    public boolean takes(String url) {
        return url != null && url.length() >= urlPrefix.length()
            && url.regionMatches(true, 0, urlPrefix, 0, urlPrefix.length());
    }
}
