package io.kilnmark.server.scan;

import java.util.function.BooleanSupplier;

/**
 * Fired (synchronously, on the worker running the scan) once a scan has written its final status.
 * Observers add work that follows a scan, such as embedding the assets for search by meaning. They
 * run while the worker still holds the source's scan lock, should stop soon after {@code cancelled}
 * turns true (the worker is shutting down), and must not throw: the scan is already finished, and a
 * failure is only logged.
 *
 * @param status the run's final status: succeeded, partial or failed
 */
public record ScanFinished(long sourceId, String sourceName, long runId, String status, BooleanSupplier cancelled) {
}
