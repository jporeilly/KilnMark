package io.kilnmark.kiln.plugins.jdbc;

import org.junit.jupiter.api.Tag;

/**
 * Reading against MySQL 8.4 LTS (pinned in {@link MySqlServers}). Tagged {@code mysql}, so it runs with the
 * other database servers in CI's heavy-databases job: {@code ./gradlew :kiln-plugins:heavyTest}.
 */
@Tag("mysql")
class MySqlIngestTest extends MySqlFamilyIngestTest {

    @Override
    MySqlServers.Server server() {
        return MySqlServers.mysql();
    }
}
