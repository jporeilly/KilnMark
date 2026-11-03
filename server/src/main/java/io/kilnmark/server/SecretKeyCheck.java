package io.kilnmark.server;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Says at start, in the log, when the stored passwords cannot be opened with this server's key: a
 * database restored without the keys from the same backup gets a new key on its first start, and
 * nothing else shows it until a scan fails (ADR 0039). The server starts either way, so an admin
 * can enter the passwords again.
 */
@ApplicationScoped
public class SecretKeyCheck {

    private static final Logger LOG = Logger.getLogger(SecretKeyCheck.class);

    /** Every sealed value the server keeps: source passwords, model keys, the mail password. */
    static final String SEALED = "SELECT secret FROM kilnmark.data_source WHERE secret IS NOT NULL "
        + "UNION ALL SELECT api_key FROM kilnmark.model_profile WHERE api_key IS NOT NULL "
        + "UNION ALL SELECT password FROM kilnmark.mail_settings WHERE password IS NOT NULL";

    private final Db db;
    private final SecretBox secrets;

    public SecretKeyCheck(Db db, SecretBox secrets) {
        this.db = db;
        this.secrets = secrets;
    }

    void onStart(@Observes StartupEvent event) {
        problem().ifPresent(LOG::error);
    }

    /** What is wrong, in words, when a stored value cannot be opened; empty when every one can. */
    Optional<String> problem() {
        return db.tx(c -> {
            int all = 0;
            int wrong = 0;
            try (PreparedStatement ps = c.prepareStatement(SEALED); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    all++;
                    try {
                        secrets.open(rs.getBytes(1));
                    } catch (SecretBox.WrongKey e) {
                        wrong++;
                    }
                }
            }
            return wrong == 0 ? Optional.<String>empty() : Optional.of(SecretBox.MESSAGE + " (" + wrong + " of " + all
                + " stored passwords and keys. The key is KILNMARK_SECRET_KEY, or the file KILNMARK_SECRET_KEY_FILE "
                + "names, in the keys volume.)");
        });
    }
}
