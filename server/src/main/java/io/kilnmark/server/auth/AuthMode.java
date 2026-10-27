package io.kilnmark.server.auth;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * How callers sign in ({@code kilnmark.auth.mode}). Any other value stops the server from
 * starting, so a typo can never turn sign-in off.
 */
public enum AuthMode {
    /** API tokens: the default. */
    TOKENS,
    /**
     * No sign-in: every caller is an admin. Only for a trial on one machine, so the server refuses
     * to start unless it listens on a loopback address only.
     */
    OFF;

    /**
     * Refuses {@link #OFF} unless {@code httpHost} (the {@code quarkus.http.host} setting) is a
     * loopback address.
     *
     * @throws IllegalStateException saying what to change
     */
    public static void check(AuthMode mode, String httpHost) {
        if (mode != OFF) {
            return;
        }
        if (!loopback(httpHost)) {
            throw new IllegalStateException("kilnmark.auth.mode=off makes every caller an admin, so the server only "
                + "allows it when it listens on this machine alone, but quarkus.http.host is '" + httpHost + "'. "
                + "Set QUARKUS_HTTP_HOST=127.0.0.1, or remove KILNMARK_AUTH_MODE to sign in with API tokens.");
        }
    }

    /** Whether sign-in is really off: {@link #OFF} and listening on a loopback address only. */
    public static boolean signInOff(AuthMode mode, String httpHost) {
        return mode == OFF && loopback(httpHost);
    }

    private static boolean loopback(String httpHost) {
        try {
            return httpHost != null && !httpHost.isBlank() && InetAddress.getByName(httpHost.strip())
                .isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
