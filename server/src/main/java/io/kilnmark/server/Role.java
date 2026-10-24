package io.kilnmark.server;

/**
 * What a Kilnmark server process does. One image runs any combination:
 * a small install runs all three in one container; Kubernetes runs each as its own deployment.
 */
public enum Role {
    /** REST and GraphQL APIs plus the web UI. */
    API,
    /** Fires scheduled scans and rules by queueing jobs. */
    SCHEDULER,
    /** Pulls jobs from the PostgreSQL queue and runs them on the Kiln engine. */
    WORKER
}
