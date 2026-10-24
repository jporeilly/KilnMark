package io.kilnmark.server;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.JdbcGraphStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/** The metadata store, on the server's own database. */
@ApplicationScoped
public class StoreProducer {

    @Produces
    @ApplicationScoped
    GraphStore graphStore(AgroalDataSource dataSource) {
        return new JdbcGraphStore(dataSource);
    }
}
