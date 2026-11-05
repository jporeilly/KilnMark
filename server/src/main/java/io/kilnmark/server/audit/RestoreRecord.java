package io.kilnmark.server.audit;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.jboss.logging.Logger;

import io.kilnmark.server.KilnmarkConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Records a restore in the audit log (ADR 0039). {@code restore.sh} leaves {@code restored.properties}
 * beside the secret key; at the next start the server records {@code database.restored} from it, as
 * a row and as a JSON line for the SIEM, like any other event, then deletes the file.
 */
@ApplicationScoped
public class RestoreRecord {

    private static final Logger LOG = Logger.getLogger(RestoreRecord.class);

    /** The file {@code restore.sh} writes, in the folder of the secret key file. */
    static final String FILE = "restored.properties";
    /** What is kept from it, each at most {@value #MAX} characters. */
    static final List<String> KEYS = List.of("backup", "takenAt", "version", "previousDatabase", "keys");
    static final int MAX = 100;

    private final AuditLog audit;
    private final Path file;

    public RestoreRecord(AuditLog audit, KilnmarkConfig config) {
        this.audit = audit;
        this.file = Path.of(config.secretKeyFile()).toAbsolutePath().resolveSibling(FILE);
    }

    void onStart(@Observes StartupEvent event) {
        record(file);
    }

    /** Records the restore {@code file} describes, if there is one, then deletes it. */
    void record(Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            LOG.warnf("Could not read %s, left by restore.sh: the restore is recorded without its details", file);
        }
        Map<String, String> detail = new LinkedHashMap<>();
        for (String key : KEYS) {
            String value = p.getProperty(key);
            if (value != null && !value.isBlank()) {
                detail.put(key, value.length() > MAX ? value.substring(0, MAX) : value);
            }
        }
        audit.record(AuditLog.Actor.server("restore"), AuditLog.DATABASE_RESTORED, "kilnmark", detail);
        try {
            Files.delete(file);
        } catch (IOException e) {
            LOG.warnf("Could not delete %s: the restore will be recorded again at the next start", file);
        }
    }
}
