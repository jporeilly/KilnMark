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
 * @param registeredBy who registered it
 * @param registeredAt when
 */
public record RegisteredDriver(String name, String fileName, String sha256, String driverClass, String urlPrefix,
                               String registeredBy, Instant registeredAt) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,195}\\.jar");
    private static final Pattern CLASS = Pattern.compile(
        "(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern PREFIX = Pattern.compile("jdbc:[A-Za-z][A-Za-z0-9+._-]*:[A-Za-z0-9:/@+._-]*");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    public RegisteredDriver {
        List<String> problems = problems(name, fileName, driverClass, urlPrefix);
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

    /** Whether a JDBC URL starts as this driver's do (the scheme ignoring case, as drivers compare it). */
    public boolean takes(String url) {
        return url != null && url.length() >= urlPrefix.length()
            && url.regionMatches(true, 0, urlPrefix, 0, urlPrefix.length());
    }
}
