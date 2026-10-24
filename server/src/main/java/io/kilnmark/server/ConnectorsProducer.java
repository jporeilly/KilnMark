package io.kilnmark.server;

import io.kilnmark.kiln.connect.Connectors;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/** Every connector on the class path; adding a connector jar adds a kind of source. */
@ApplicationScoped
public class ConnectorsProducer {

    @Produces
    @ApplicationScoped
    Connectors connectors() {
        return Connectors.load();
    }
}
