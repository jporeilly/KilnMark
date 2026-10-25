package io.kilnmark.kiln.plugins.jdbc;

import org.junit.jupiter.api.Tag;

/**
 * Writing against MariaDB 11.4 LTS (pinned in {@link MySqlServers}). Tagged {@code mariadb}, so it runs with the
 * other database servers in CI's heavy-databases job: {@code ./gradlew :kiln-plugins:heavyTest}.
 */
@Tag("mariadb")
class MariaDbWriteTest extends MySqlFamilyWriteTest {

    @Override
    MySqlServers.Server server() {
        return MySqlServers.mariadb();
    }
}
