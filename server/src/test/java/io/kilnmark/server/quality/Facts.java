package io.kilnmark.server.quality;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Synthetic profile versions for tests: fields with statistics written as the profile writes them. */
final class Facts {

    static final ObjectMapper JSON = new ObjectMapper();

    private final List<ProfileFacts.FieldFacts> fields = new ArrayList<>();
    private List<String> pk = List.of();
    private Long rows;
    private Instant at = Instant.parse("2026-10-01T12:00:00Z");
    private long id = 1;

    static Facts version() {
        return new Facts();
    }

    Facts id(long id) {
        this.id = id;
        return this;
    }

    Facts at(String instant) {
        this.at = Instant.parse(instant);
        return this;
    }

    Facts rows(long rows) {
        this.rows = rows;
        return this;
    }

    Facts key(String... names) {
        this.pk = List.of(names);
        return this;
    }

    /** A field; {@code stats} is the profile's JSON (single quotes allowed for readability). */
    Facts field(String name, String kind, String stats, String... accepted) {
        JsonNode s;
        try {
            s = stats == null ? null : JSON.readTree(stats.replace('\'', '"'));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(stats, e);
        }
        fields.add(new ProfileFacts.FieldFacts(name, fields.size() + 1, kind, kind, true, s, Set.of(accepted)));
        return this;
    }

    ProfileFacts build() {
        return new ProfileFacts(id, "urn:kilnmark:test:shop.t", at, rows, true, false, pk, fields);
    }
}
