package io.kilnmark.server.ai;

import java.util.Locale;

/** An AI feature an admin routes to a model profile. A feature with no profile is off. */
public enum AiFeature {
    DESCRIBE("describe", Purpose.CHAT, "Describe assets", "Drafts descriptions for people to approve."),
    CLASSIFY("classify", Purpose.CHAT, "Classify fields", "Suggests classifications for people to approve."),
    SEARCH_NL("search_nl", Purpose.CHAT, "Ask in plain English",
        "Turns a question into a search. Without it, questions are searched as typed."),
    EMBED("embed", Purpose.EMBEDDING, "Search by meaning",
        "Embeds assets at each scan and every search. Without it, search matches words only.");

    private final String id;
    private final Purpose purpose;
    private final String label;
    private final String help;

    AiFeature(String id, Purpose purpose, String label, String help) {
        this.id = id;
        this.purpose = purpose;
        this.label = label;
        this.help = help;
    }

    public String id() {
        return id;
    }

    /** The kind of model this feature needs. */
    public Purpose purpose() {
        return purpose;
    }

    public String label() {
        return label;
    }

    public String help() {
        return help;
    }

    public static AiFeature parse(String text) {
        if (text != null) {
            String t = text.trim().toLowerCase(Locale.ROOT);
            for (AiFeature f : values()) {
                if (f.id.equals(t)) {
                    return f;
                }
            }
        }
        throw new IllegalArgumentException("feature: use describe, classify, search_nl or embed");
    }

    /** What a model profile is for. */
    public enum Purpose {
        CHAT, EMBEDDING;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Purpose parse(String text) {
            if (text != null) {
                for (Purpose p : values()) {
                    if (p.id().equalsIgnoreCase(text.trim())) {
                        return p;
                    }
                }
            }
            throw new IllegalArgumentException("purpose: use chat or embedding");
        }
    }
}
