package io.kilnmark.server;

/**
 * What a Kilnmark server process does. One image runs any combination:
 * a small install runs all three in one container; Kubernetes runs each as its own deployment.
 */
public enum Role {
    /** REST and GraphQL APIs plus the web UI. */
    API,
    /**
     * Fires schedules of pipelines and flows by queueing their runs (ADR 0046), and runs the
     * server's clean-ups (old notifications, AI usage records).
     */
    SCHEDULER,
    /** Pulls jobs from the PostgreSQL queue and runs them on the Kiln engine. */
    WORKER
}
