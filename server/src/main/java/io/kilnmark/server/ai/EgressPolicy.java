package io.kilnmark.server.ai;

import java.util.Locale;
import java.util.Optional;

import io.kilnmark.graph.store.Records;
import io.kilnmark.server.KilnmarkConfig;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * What may leave the building: the one place that decides whether a model may see a source's
 * metadata or data (ADR 0016). {@link AiGateway} asks it before every model call, and anything
 * that builds a prompt from several sources asks it to filter them.
 *
 * <p>Two rules, both must allow the call:
 * <ul>
 *   <li>The server-wide rule: {@code kilnmark.ai.hosted-models} (set by whoever runs the server)
 *       and the admin's {@code hosted_models} setting. Either one set to {@code forbidden} refuses
 *       every model not marked local.</li>
 *   <li>The source's {@code ai_egress} setting: {@code local_only} (the default, also used for a
 *       value this version does not know) lets only local models see it, {@code none} no model at
 *       all, and {@code allowed} any configured model.</li>
 * </ul>
 */
@ApplicationScoped
public class EgressPolicy {

    /** A source's rule, from {@code source.settings.ai_egress}. */
    public enum SourceRule {
        LOCAL_ONLY("local_only"), ALLOWED("allowed"), NONE("none");

        private final String id;

        SourceRule(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Absent, blank or unknown values read as {@code local_only}, the safe default. */
        public static SourceRule of(String value) {
            if (value != null) {
                String v = value.trim().toLowerCase(Locale.ROOT);
                for (SourceRule r : values()) {
                    if (r.id.equals(v)) {
                        return r;
                    }
                }
            }
            return LOCAL_ONLY;
        }

        public static SourceRule of(Records.Source source) {
            return of(source.settings().get(SETTING));
        }
    }

    /** The key in {@code source.settings}. */
    public static final String SETTING = "ai_egress";

    private final KilnmarkConfig config;
    private final ModelProfiles profiles;

    public EgressPolicy(KilnmarkConfig config, ModelProfiles profiles) {
        this.config = config;
        this.profiles = profiles;
    }

    /** Whether whoever runs the server forbade hosted models in its configuration. Admins cannot undo it. */
    public boolean hostedLockedOff() {
        return "forbidden".equalsIgnoreCase(config.ai().hostedModels().trim());
    }

    /** Whether models not marked local may be used at all, by the configuration and the admin's setting. */
    public boolean hostedAllowed() {
        return !hostedLockedOff()
            && !"forbidden".equalsIgnoreCase(profiles.setting(ModelProfiles.HOSTED_MODELS).orElse("allowed"));
    }

    /** Why {@code profile} may not see {@code source} (null for a call about no source), or empty if it may. */
    public Optional<String> refusal(ModelProfile profile, Records.Source source) {
        return Optional.ofNullable(decide(profile.local(), profile.name(), hostedAllowed(),
            source == null ? null : source.name(), source == null ? null : SourceRule.of(source)));
    }

    /**
     * The rule itself, without the database: the reason for refusing, or null to allow.
     *
     * @param sourceName null when the call carries no source's metadata
     */
    static String decide(boolean local, String profileName, boolean hostedAllowed, String sourceName,
                         SourceRule rule) {
        if (!local && !hostedAllowed) {
            return "This server allows only models marked local, and " + profileName + " is not marked local. "
                + "An admin can mark a model local, or allow hosted models, under AI models.";
        }
        if (sourceName == null || rule == null) {
            return null;
        }
        return switch (rule) {
            case NONE -> "Source " + sourceName + " does not allow AI (ai_egress is none), so no model may see it.";
            case LOCAL_ONLY -> local ? null : "Source " + sourceName + " allows only local models (ai_egress is "
                + "local_only), and " + profileName + " is not marked local. A steward can change the source's AI "
                + "rule, or an admin can choose a local model for this feature.";
            case ALLOWED -> null;
        };
    }
}
