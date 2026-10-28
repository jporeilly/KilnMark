package io.kilnmark.server.drivers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.DriverFolder;
import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Drivers Kilnmark cannot ship, which the customer installs (docs/design/drivers.md). Each
 * connector that needs one says which ({@link Connector#driver()}): Maven coordinates, a pinned
 * version and checksum, its licence. An admin accepts the licence on the organisation's behalf,
 * which is recorded, and the server downloads that exact version from its Maven repository
 * ({@code kilnmark.drivers.maven-url}: Maven Central or a mirror, never an address people type) and
 * keeps it only if the checksum matches. A server without internet access
 * ({@code kilnmark.drivers.download=false}) shows which file to put in the drivers folder instead.
 */
@ApplicationScoped
public class DriverService {

    /** One driver, with the connectors that need it and whether it is installed. */
    public record Item(String id, String coordinates, String version, String fileName, String licence,
                       String licenceUrl, List<String> usedBy, String state, String message, String folder,
                       String sha256, String downloadUrl, boolean download, String acceptedBy, Instant acceptedAt) {
    }

    /** A download that did not install the driver; the message says what to do. */
    public static final class DownloadFailed extends Exception {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /** Why nothing was installed. */
        public enum Reason {
            /** This server does not download drivers ({@code kilnmark.drivers.download=false}). */
            DOWNLOADS_OFF,
            /** The download failed or did not give the expected file. */
            DOWNLOAD_FAILED
        }

        private final Reason reason;

        DownloadFailed(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private static final Logger LOG = Logger.getLogger(DriverService.class);

    private final Connectors connectors;
    private final DriverFolder folder;
    private final KilnmarkConfig.Drivers config;
    private final Db db;

    public DriverService(Connectors connectors, DriverFolder folder, KilnmarkConfig config, Db db) {
        this.connectors = connectors;
        this.folder = folder;
        this.config = config.drivers();
        this.db = db;
    }

    /**
     * At start: a driver whose pinned version changed with an upgrade is not installed any more
     * (its file name holds the version), and its licence must be accepted again. Say so in the log,
     * naming the file left behind, so an admin knows why Oracle sources stopped connecting.
     */
    void onStart(@Observes StartupEvent event) {
        for (Item i : listWithoutLicences()) {
            if (!i.state().equals("missing")) {
                continue;
            }
            List<String> older = olderFiles(i);
            if (!older.isEmpty()) {
                LOG.warnf("This version of Kilnmark uses %s %s, but the drivers folder %s holds %s: the driver was "
                    + "updated, so the installed one is no longer used, and sources needing it (%s) cannot connect "
                    + "until an admin accepts its licence again under Settings, Drivers, or puts %s in that folder",
                    i.coordinates(), i.version(), i.folder(), String.join(", ", older), String.join(", ", i.usedBy()),
                    i.fileName());
            }
        }
    }

    /** Other versions of {@code i}'s driver in the folder, by file name. */
    private List<String> olderFiles(Item i) {
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(folder.dir())) {
            return files.map(f -> f.getFileName().toString())
                .filter(n -> n.startsWith(i.id() + "-") && n.endsWith(".jar") && !n.equals(i.fileName()))
                .sorted().toList();
        } catch (java.io.IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** {@link #list()} without reading who accepted the licences, for use before the database is needed. */
    private List<Item> listWithoutLicences() {
        Map<String, Connector.Driver> drivers = new LinkedHashMap<>();
        Map<String, List<String>> usedBy = new LinkedHashMap<>();
        collect(drivers, usedBy);
        List<Item> out = new ArrayList<>();
        drivers.forEach((key, d) -> {
            DriverFolder.Status st = folder.status(d);
            out.add(new Item(d.artifact(), d.coordinates(), d.version(), d.fileName(), d.licence(), d.licenceUrl(),
                List.copyOf(usedBy.get(key)), st.state().name().toLowerCase(Locale.ROOT), st.message(),
                folder.dir().toString(), d.sha256(), downloadUrl(d), config.download(), null, null));
        });
        return out;
    }

    private void collect(Map<String, Connector.Driver> drivers, Map<String, List<String>> usedBy) {
        for (Connector c : connectors.all()) {
            Connector.Driver d = c.driver();
            if (d == null || d.bundleable()) {
                continue;
            }
            String key = d.coordinates() + ":" + d.version();
            drivers.putIfAbsent(key, d);
            usedBy.computeIfAbsent(key, k -> new ArrayList<>()).add(c.displayName());
        }
    }

    /** The drivers the server's connectors need, each once, in the order of their connectors. */
    public List<Item> list() {
        Map<String, Connector.Driver> drivers = new LinkedHashMap<>();
        Map<String, List<String>> usedBy = new LinkedHashMap<>();
        collect(drivers, usedBy);
        List<Item> out = new ArrayList<>();
        drivers.forEach((key, d) -> out.add(item(d, usedBy.get(key))));
        return out;
    }

    /** The driver with this id (its artifact, such as {@code ojdbc11}). */
    public Optional<Item> get(String id) {
        return list().stream().filter(i -> i.id().equals(id)).findFirst();
    }

    private Connector.Driver spec(String id) {
        for (Connector c : connectors.all()) {
            if (c.driver() != null && c.driver().artifact().equals(id)) {
                return c.driver();
            }
        }
        return null;
    }

    private Item item(Connector.Driver d, List<String> usedBy) {
        DriverFolder.Status s = folder.status(d);
        String[] accepted = accepted(d);
        return new Item(d.artifact(), d.coordinates(), d.version(), d.fileName(), d.licence(), d.licenceUrl(),
            List.copyOf(usedBy), s.state().name().toLowerCase(Locale.ROOT), s.message(), folder.dir().toString(),
            d.sha256(), downloadUrl(d), config.download(), accepted == null ? null : accepted[0],
            accepted == null ? null : Instant.parse(accepted[1]));
    }

    /** Where the server downloads {@code d} from; also where an admin can fetch it by hand. */
    String downloadUrl(Connector.Driver d) {
        String base = config.mavenUrl().endsWith("/") ? config.mavenUrl() : config.mavenUrl() + "/";
        return base + d.repositoryPath();
    }

    private String[] accepted(Connector.Driver d) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT accepted_by, accepted_at FROM kilnmark.driver_licence "
                + "WHERE coordinates = ? AND version = ?")) {
                ps.setString(1, d.coordinates());
                ps.setString(2, d.version());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new String[] {rs.getString(1), rs.getTimestamp(2).toInstant().toString()} : null;
                }
            }
        });
    }

    /**
     * An admin accepts the driver's licence for the organisation: recorded (who and when, kept from
     * the first acceptance), then, unless the driver is already installed, the server downloads it
     * and installs it if its checksum matches.
     *
     * @return the driver as it now is, or empty when no connector needs a driver with this id
     * @throws DownloadFailed when this server does not download drivers, or the download did not
     *                        give the expected file; the message says where to put the file
     */
    public synchronized Optional<Item> accept(String id, String caller) throws DownloadFailed {
        Connector.Driver d = spec(id);
        if (d == null) {
            return Optional.empty();
        }
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.driver_licence (coordinates, version, "
                + "licence, licence_url, accepted_by, accepted_at) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (coordinates, version) DO NOTHING")) {
                ps.setString(1, d.coordinates());
                ps.setString(2, d.version());
                ps.setString(3, d.licence());
                ps.setString(4, d.licenceUrl());
                ps.setString(5, caller);
                ps.setTimestamp(6, Timestamp.from(Instant.now()));
                return ps.executeUpdate();
            }
        });
        if (!folder.status(d).ready()) {
            if (!config.download()) {
                throw new DownloadFailed(DownloadFailed.Reason.DOWNLOADS_OFF,
                    "The licence is accepted. This server does not download drivers "
                    + "(kilnmark.drivers.download is false): put " + d.fileName() + " in " + folder.dir()
                    + ", unchanged, from " + downloadUrl(d), null);
            }
            download(d);
        }
        return get(id);
    }

    private void download(Connector.Driver d) throws DownloadFailed {
        String url = downloadUrl(d);
        String instead = " Or put " + d.fileName() + " in " + folder.dir() + " yourself, unchanged, from " + url;
        HttpClient http = HttpClient.newBuilder().connectTimeout(config.downloadTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(config.downloadTimeout())
            .header("User-Agent", "Kilnmark").GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new DownloadFailed(DownloadFailed.Reason.DOWNLOAD_FAILED,
                        "Could not download " + d.fileName() + ": " + url + " answered "
                        + response.statusCode() + "." + instead, null);
                }
                folder.install(d, in);
            }
        } catch (IOException e) {
            throw new DownloadFailed(DownloadFailed.Reason.DOWNLOAD_FAILED,
                "Could not download " + d.fileName() + " from " + url + ": " + e.getMessage() + "."
                + instead, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadFailed(DownloadFailed.Reason.DOWNLOAD_FAILED,
                "The download of " + d.fileName() + " was interrupted." + instead, e);
        }
    }
}
