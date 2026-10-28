package io.kilnmark.server.ai;

/** For tests in other packages: the egress rule itself ({@link EgressPolicy#decide}), without the database. */
public final class EgressRules {

    private EgressRules() {
    }

    /** Why a model may not see a source's metadata, or null when it may. */
    public static String decide(boolean local, String profileName, boolean hostedAllowed, String sourceName,
                                EgressPolicy.SourceRule rule) {
        return EgressPolicy.decide(local, profileName, hostedAllowed, sourceName, rule);
    }
}
