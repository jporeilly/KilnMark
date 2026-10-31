package io.kilnmark.server.variables;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.kiln.yaml.FlowCondition;
import io.kilnmark.kiln.yaml.Parameters;
import io.kilnmark.server.api.ApiError;
import io.kilnmark.server.auth.AccessRole;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.core.Response;

/**
 * Server variables (docs/design/pipelines.md, "Parameters and server variables"): values every
 * pipeline and flow on this server reads as {@code ${vars.name}}. Stewards, who design pipelines and
 * flows, and admins set and read them; every change is kept with who made it. Never secrets: names that look like a credential are refused, since credentials
 * belong in sources, which keep them encrypted and never show them.
 */
@ApplicationScoped
public class VariableService {

    private static final Logger LOG = Logger.getLogger(VariableService.class);

    /** Environment variables that seed a server variable at start-up: KILNMARK_VAR_TARGET_SCHEMA. */
    static final String ENV_PREFIX = "KILNMARK_VAR_";
    static final int MAX_DESCRIPTION = 500;
    private static final Pattern CREDENTIAL = Pattern.compile("pass(word)?|pwd|secret|token|api_?key|credential|"
        + "private_?key");

    /** A variable as the API shows it: {@code value} is text, a number or true/false. */
    public record View(String name, String type, Object value, String description, String updatedBy,
                       java.time.Instant updatedAt) {
    }

    /** One change, newest first: the values before and after (null when created or deleted). */
    public record ChangeView(Object before, Object after, String by, java.time.Instant at) {
    }

    private final VariableStore store;
    private final ObjectMapper json;

    public VariableService(VariableStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    public List<View> list(AccessRole role) {
        requireAtLeast(role, AccessRole.STEWARD, "Reading the server's variables");
        return store.list().stream().map(this::view).toList();
    }

    public View get(String name, AccessRole role) {
        requireAtLeast(role, AccessRole.STEWARD, "Reading the server's variables");
        return store.find(name).map(this::view).orElseThrow(() -> fail(Response.Status.NOT_FOUND,
            "No server variable called " + name));
    }

    public List<ChangeView> changes(String name, AccessRole role) {
        requireAtLeast(role, AccessRole.STEWARD, "Reading the server's variables");
        return store.changes(name, 100).stream().map(c -> new ChangeView(read(c.oldValue()), read(c.newValue()),
            c.changedBy(), c.changedAt())).toList();
    }

    /** Creates or replaces a variable. */
    public View set(String name, String type, Object value, String description, String by, AccessRole role) {
        requireAtLeast(role, AccessRole.DESIGNER, "Setting a server variable");
        checkName(name);
        FlowCondition.Type t = type(type);
        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("description: at most " + MAX_DESCRIPTION + " characters");
        }
        Object typed;
        try {
            typed = Parameters.typed("value", t, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        store.put(name, t.name().toLowerCase(Locale.ROOT), write(typed), description, by, false);
        LOG.infof("Server variable %s set by %s", name, by);
        return store.find(name).map(this::view).orElseThrow();
    }

    public void delete(String name, String by, AccessRole role) {
        requireAtLeast(role, AccessRole.DESIGNER, "Deleting a server variable");
        if (!store.delete(name, by)) {
            throw fail(Response.Status.NOT_FOUND, "No server variable called " + name);
        }
        LOG.infof("Server variable %s deleted by %s", name, by);
    }

    /** Every variable's value by name, as runs read them; recorded with each run when it is queued. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (VariableStore.Variable v : store.list()) {
            Object value = read(v.value());
            out.put(v.name(), value instanceof Number n ? (Object) n.doubleValue() : value);
        }
        return out;
    }

    /**
     * Seeds a variable from each {@code KILNMARK_VAR_<NAME>} environment variable, as text, unless it
     * exists already: what an admin has set is never overwritten.
     */
    void seed(@Observes StartupEvent event) {
        seed(System.getenv());
    }

    void seed(Map<String, String> env) {
        env.forEach((key, value) -> {
            if (!key.startsWith(ENV_PREFIX)) {
                return;
            }
            String name = key.substring(ENV_PREFIX.length()).toLowerCase(Locale.ROOT);
            try {
                checkName(name);
                Object typed = Parameters.typed("value", FlowCondition.Type.TEXT, value);
                if (store.put(name, "text", write(typed), "Seeded from " + key, "environment", true)) {
                    LOG.infof("Server variable %s seeded from the environment", name);
                }
            } catch (IllegalArgumentException e) {
                LOG.warnf("%s was not made a server variable: %s", key, e.getMessage());
            }
        });
    }

    static void checkName(String name) {
        if (name == null || !Parameters.NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name: start with a lower-case letter and use only a-z, 0-9 and _, up "
                + "to 64 characters, such as target_schema");
        }
        if (CREDENTIAL.matcher(name).find()) {
            throw new IllegalArgumentException("name: '" + name + "' looks like a credential. Server variables are "
                + "readable by stewards and recorded with every run: keep passwords and keys in a source, which "
                + "holds them encrypted");
        }
    }

    private static FlowCondition.Type type(String type) {
        return switch (type == null ? "" : type.strip().toLowerCase(Locale.ROOT)) {
            case "text" -> FlowCondition.Type.TEXT;
            case "number" -> FlowCondition.Type.NUMBER;
            case "boolean" -> FlowCondition.Type.BOOLEAN;
            default -> throw new IllegalArgumentException("type: text, number or boolean");
        };
    }

    private static jakarta.ws.rs.WebApplicationException fail(Response.Status status, String message) {
        return new jakarta.ws.rs.WebApplicationException(Response.status(status)
            .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON).entity(new ApiError(message)).build());
    }

    private static void requireAtLeast(AccessRole role, AccessRole needed, String what) {
        if (role == null || role.compareTo(needed) < 0) {
            throw fail(Response.Status.FORBIDDEN, what + " takes the " + needed.id() + " role");
        }
    }

    private View view(VariableStore.Variable v) {
        return new View(v.name(), v.type(), read(v.value()), v.description(), v.updatedBy(), v.updatedAt());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("value: could not be written");
        }
    }

    private Object read(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
