package io.kilnmark.server.relations;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.graph.store.BrowseStore;
import io.kilnmark.graph.store.JdbcBrowseStore;
import io.kilnmark.graph.store.JdbcRelationshipStore;
import io.kilnmark.graph.store.RelationshipStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/** The relationship and browse stores, on the server's own database. */
@ApplicationScoped
public class RelationsStoreProducer {

    @Produces
    @ApplicationScoped
    RelationshipStore relationshipStore(AgroalDataSource dataSource,
                                        @org.eclipse.microprofile.config.inject.ConfigProperty(
                                            name = "kilnmark.relationships.statement-timeout", defaultValue = "5m")
                                        java.time.Duration statementTimeout) {
        return new JdbcRelationshipStore(dataSource, statementTimeout);
    }

    @Produces
    @ApplicationScoped
    BrowseStore browseStore(AgroalDataSource dataSource) {
        return new JdbcBrowseStore(dataSource);
    }
}
