package io.kilnmark.kiln.plugins.jdbc;

import org.junit.jupiter.api.Tag;

/** Explore against MariaDB 11.4 LTS; runs in CI's heavy-databases job ({@code :kiln-plugins:heavyTest}). */
@Tag("mariadb")
class MariaDbExploreTest extends MySqlFamilyExploreTest {

    @Override
    MySqlServers.Server server() {
        return MySqlServers.mariadb();
    }
}
