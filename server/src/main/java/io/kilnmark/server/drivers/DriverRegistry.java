package io.kilnmark.server.drivers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jboss.logging.Logger;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.DriverFolder;
import io.kilnmark.kiln.connect.RegisteredDriver;
import io.kilnmark.server.Db;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * JDBC drivers an admin registers for the generic JDBC connector (docs/design/drivers.md,
 * "Registered drivers"; ADR 0025). Kilnmark cannot pin these in advance, so the admin vouches for
 * the jar instead: they upload it or pick it in the drivers folder, name its driver class and the
 * URLs it takes, and Kilnmark records its SHA-256 then. From then on the driver loads only while
 * the jar has that checksum, in a class loader of its own like a pinned driver
 * ({@link DriverFolder}). Registrations are kept in {@code driver_registration}; who registered or
 * removed what, and when, in {@code driver_registration_log}, for good. Nothing is ever downloaded
 * for a registered driver.
 */
@ApplicationScoped
public class DriverRegistry {

    private static final Logger LOG = Logger.getLogger(DriverRegistry.class);

    /** A registration as the admin screen shows it, with whether its jar is there and unchanged. */
    public record Item(String name, String fileName, String sha256, String driverClass, String urlPrefix,
                       String registeredBy, Instant registeredAt, String state, String message, List<String> usedBy) {
    }

    /** A jar in the drivers folder, for picking one to register. */
    public record FolderFile(String fileName, long size, Instant modified, String registeredAs, boolean pinned) {
    }

    /** What the drivers folder holds, and where it is. */
    public record Folder(String folder, List<FolderFile> files, long maxUploadBytes) {
    }

    /** One registration or removal, for the audit trail. */
    public record LogEntry(String action, String name, String fileName, String sha256, String driverClass,
                           String urlPrefix, String by, Instant at) {
    }

    /** What an admin sends to register a driver. */
    public record Registration(String name, String fileName, String driverClass, String urlPrefix) {
    }

    /** A request refused, with what to say and the kind of refusal. */
    public static final class Refused extends Exception {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /** Why. */
        public enum Reason { INVALID, CONFLICT, NOT_FOUND }

        private final Reason reason;

        Refused(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** The largest upload: the server's request limit decides first (10 MB unless raised). */
    public static final long MAX_UPLOAD_BYTES = DriverFolder.MAX_BYTES;

    private final DriverFolder folder;
    private final Connectors connectors;
    private final Db db;
    private volatile boolean loaded;

    public DriverRegistry(DriverFolder folder, Connectors connectors, Db db) {
        this.folder = folder;
        this.connectors = connectors;
        this.db = db;
    }

    /** At start: the registrations into the drivers folder, so sources can connect at once. */
    void onStart(@Observes StartupEvent event) {
        try {
            load();
        } catch (RuntimeException e) {
            LOG.warnf("Could not read the registered JDBC drivers yet (%s); they are read again when first needed",
                e.getMessage());
        }
    }

    /** Reads the registrations from the database into the drivers folder. */
    synchronized void load() {
        List<RegisteredDriver> all = db.tx(c -> {
            List<RegisteredDriver> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name, file_name, sha256, driver_class, url_prefix, "
                + "registered_by, registered_at FROM kilnmark.driver_registration ORDER BY name");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        out.add(new RegisteredDriver(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant()));
                    } catch (IllegalArgumentException e) {
                        LOG.warnf("The registered JDBC driver %s is not valid and is not used: %s", rs.getString(1),
                            e.getMessage());
                    }
                }
            }
            return out;
        });
        folder.registered(all);
        loaded = true;
    }

    private void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    /** Every registration, by name, with its state. */
    public List<Item> list() {
        ensureLoaded();
        return folder.registered().stream().map(this::item).toList();
    }

    private Item item(RegisteredDriver r) {
        DriverFolder.Status s = folder.status(r);
        return new Item(r.name(), r.fileName(), r.sha256(), r.driverClass(), r.urlPrefix(), r.registeredBy(),
            r.registeredAt(), s.state().name().toLowerCase(Locale.ROOT), s.message(), sourcesUsing(r.name()));
    }

    /** The jars in the drivers folder, with what each is registered as, and whether a connector pins it. */
    public Folder files() {
        ensureLoaded();
        Set<String> pinned = new HashSet<>();
        for (Connector c : connectors.all()) {
            if (c.driver() != null) {
                pinned.add(c.driver().fileName());
            }
        }
        List<FolderFile> out = new ArrayList<>();
        for (DriverFolder.FolderFile f : folder.jars()) {
            String as = folder.registered().stream().filter(r -> r.fileName().equals(f.fileName()))
                .map(RegisteredDriver::name).findFirst().orElse(null);
            out.add(new FolderFile(f.fileName(), f.size(), f.modified(), as, pinned.contains(f.fileName())));
        }
        return new Folder(folder.dir().toString(), out, MAX_UPLOAD_BYTES);
    }

    /**
     * Puts an uploaded jar in the drivers folder, never over a file already there. Registering it is
     * a separate step, so the admin sees its name and checksum first.
     */
    public synchronized FolderFile upload(String fileName, InputStream in, String caller) throws Refused {
        try {
            folder.jar(fileName);
        } catch (IOException e) {
            throw new Refused(Refused.Reason.INVALID, e.getMessage());
        }
        try {
            String sha = folder.upload(fileName, in);
            LOG.infof("JDBC driver file %s (SHA-256 %s) uploaded to the drivers folder by %s", fileName, sha, caller);
        } catch (FileAlreadyExistsException e) {
            throw new Refused(Refused.Reason.CONFLICT, e.getFile() == null ? e.getMessage() : e.getFile());
        } catch (IOException e) {
            throw new Refused(Refused.Reason.INVALID, e.getMessage());
        }
        return files().files().stream().filter(f -> f.fileName().equals(fileName)).findFirst().orElseThrow();
    }

    /**
     * Registers a jar in the drivers folder: checks what the admin typed, reads the jar, loads the
     * driver class from it in a class loader of its own (so a jar without that driver is refused at
     * once), and records the jar's SHA-256 with who registered it and when.
     */
    public synchronized Item register(Registration body, String caller) throws Refused {
        ensureLoaded();
        if (body == null) {
            throw new Refused(Refused.Reason.INVALID, "Send the registration as JSON: name, fileName, driverClass and "
                + "urlPrefix");
        }
        String name = strip(body.name());
        String fileName = strip(body.fileName());
        String driverClass = strip(body.driverClass());
        String urlPrefix = strip(body.urlPrefix());
        List<String> problems = RegisteredDriver.problems(name, fileName, driverClass, urlPrefix);
        if (!problems.isEmpty()) {
            throw new Refused(Refused.Reason.INVALID, String.join("; ", problems));
        }
        if (folder.registration(name).isPresent()) {
            throw new Refused(Refused.Reason.CONFLICT, "name: a driver called " + name + " is registered already. "
                + "Remove it first, or choose another name");
        }
        for (Connector c : connectors.all()) {
            if (c.driver() != null && c.driver().fileName().equals(fileName)) {
                throw new Refused(Refused.Reason.INVALID, "fileName: " + fileName + " is the driver the "
                    + c.displayName() + " connector pins; it is installed under Settings, Drivers, not registered");
            }
        }
        DriverFolder.Inspected inspected;
        try {
            inspected = folder.inspect(fileName, driverClass);
        } catch (NoSuchFileException e) {
            throw new Refused(Refused.Reason.INVALID, "fileName: there is no " + fileName + " in the drivers folder "
                + folder.dir() + ". Upload it, or copy it there first");
        } catch (IOException | SQLException e) {
            throw new Refused(Refused.Reason.INVALID, e.getMessage());
        }
        Instant now = Instant.now();
        RegisteredDriver r = new RegisteredDriver(name, fileName, inspected.sha256(), driverClass, urlPrefix, caller,
            now);
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.driver_registration (name, file_name, "
                + "sha256, driver_class, url_prefix, registered_by, registered_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, name);
                ps.setString(2, fileName);
                ps.setString(3, r.sha256());
                ps.setString(4, driverClass);
                ps.setString(5, urlPrefix);
                ps.setString(6, caller);
                ps.setTimestamp(7, Timestamp.from(now));
                ps.executeUpdate();
            }
            log(c, "registered", r, caller, now);
            return null;
        });
        folder.register(r);
        LOG.infof("JDBC driver %s registered by %s: %s, %s, SHA-256 %s, URLs starting %s", name, caller, fileName,
            driverClass, r.sha256(), urlPrefix);
        return item(r);
    }

    /**
     * Removes a registration, recorded in the audit trail. Refused while sources use it: they would
     * stop connecting without anyone having chosen that. The jar stays in the folder.
     */
    public synchronized void remove(String name, String caller) throws Refused {
        ensureLoaded();
        RegisteredDriver r = folder.registration(name).orElseThrow(() -> new Refused(Refused.Reason.NOT_FOUND,
            "No JDBC driver called " + name + " is registered"));
        List<String> using = sourcesUsing(name);
        if (!using.isEmpty()) {
            throw new Refused(Refused.Reason.CONFLICT, "The driver " + name + " is used by " + String.join(", ", using)
                + ". Point " + (using.size() == 1 ? "that source" : "those sources") + " at another driver, or delete "
                + (using.size() == 1 ? "it" : "them") + ", first");
        }
        Instant now = Instant.now();
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.driver_registration WHERE name = ?")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
            log(c, "removed", r, caller, now);
            return null;
        });
        folder.unregister(name);
        LOG.infof("JDBC driver %s (%s) removed by %s", name, r.fileName(), caller);
    }

    /** Who registered and removed which drivers, and when, newest first. */
    public List<LogEntry> history(int limit) {
        return db.tx(c -> {
            List<LogEntry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT action, name, file_name, sha256, driver_class, "
                + "url_prefix, done_by, done_at FROM kilnmark.driver_registration_log ORDER BY done_at DESC, id DESC "
                + "LIMIT ?")) {
                ps.setInt(1, Math.max(1, Math.min(limit, 500)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new LogEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant()));
                    }
                }
            }
            return out;
        });
    }

    private static void log(java.sql.Connection c, String action, RegisteredDriver r, String caller, Instant at)
        throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.driver_registration_log (action, name, "
            + "file_name, sha256, driver_class, url_prefix, done_by, done_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, action);
            ps.setString(2, r.name());
            ps.setString(3, r.fileName());
            ps.setString(4, r.sha256());
            ps.setString(5, r.driverClass());
            ps.setString(6, r.urlPrefix());
            ps.setString(7, caller);
            ps.setTimestamp(8, Timestamp.from(at));
            ps.executeUpdate();
        }
    }

    /** The sources of kind {@code jdbc} using the driver, by name. */
    private List<String> sourcesUsing(String name) {
        return db.tx(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM kilnmark.data_source WHERE kind = 'jdbc' "
                + "AND settings ->> 'driver' = ? ORDER BY name")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }

    private static String strip(String s) {
        return s == null ? null : s.strip();
    }
}
