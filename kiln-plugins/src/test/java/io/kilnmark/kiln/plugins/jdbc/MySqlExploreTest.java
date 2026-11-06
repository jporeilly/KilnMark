package io.kilnmark.kiln.plugins.jdbc;

import org.junit.jupiter.api.Tag;

/** Explore against MySQL 8.4 LTS; runs in CI's heavy-databases job ({@code :kiln-plugins:heavyTest}). */
@Tag("mysql")
class MySqlExploreTest extends MySqlFamilyExploreTest {

    @Override
    MySqlServers.Server server() {
        return MySqlServers.mysql();
    }
}
