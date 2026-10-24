package io.kilnmark.server.api;

import java.util.Map;

/**
 * A source as the add and edit forms send it: a name and a kind (both fixed once saved: the name is
 * part of every asset's URN), a display name people see (default: the name), every setting
 * (connector and common ones together) and the secret. When editing, a blank {@code password}
 * keeps the stored one and {@code clearPassword} removes it. {@code source} names the saved source
 * a check of unsaved settings is for, so a blank password there means its stored one.
 */
public record SourceForm(String name, String kind, Map<String, String> settings, String password,
                         Boolean clearPassword, String source, String displayName) {

    public SourceForm(String name, String kind, Map<String, String> settings, String password, Boolean clearPassword,
                      String source) {
        this(name, kind, settings, password, clearPassword, source, null);
    }
}
