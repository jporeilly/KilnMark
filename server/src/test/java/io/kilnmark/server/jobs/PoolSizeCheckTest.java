package io.kilnmark.server.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.Role;

/** The start-up check of the database pool against this process's work (INSTALL.md, "Sizing the workers"). */
class PoolSizeCheckTest {

    private static final Set<Role> ALL = EnumSet.allOf(Role.class);

    @Test
    void theDefaultsNeedTwentyAndTheDefaultPoolHoldsThem() {
        // 2 slots × 3 + 8 flows + 1 for listening + 5 for the API and scheduler.
        assertEquals(20, PoolSizeCheck.needed(ALL, 2, 8));
        assertTrue(PoolSizeCheck.problem(ALL, 2, 8, 30).isEmpty());
    }

    @Test
    void aPoolOneShortOfTheNeedIsRefusedAndOneEnoughIsNot() {
        assertTrue(PoolSizeCheck.problem(ALL, 8, 8, 38).isEmpty());
        String why = PoolSizeCheck.problem(ALL, 8, 8, 37).orElseThrow();
        assertTrue(why.contains("is 37, and it needs at least 38"), why);
        assertTrue(why.contains("QUARKUS_DATASOURCE_JDBC_MAX_SIZE"), why);
        assertTrue(why.contains("KILNMARK_WORKER_CONCURRENCY"), why);
        assertTrue(why.contains("KILNMARK_WORKER_FLOWS"), why);
    }

    @Test
    void eachRoleCountsOnlyWhatItUses() {
        assertEquals(15, PoolSizeCheck.needed(EnumSet.of(Role.WORKER), 2, 8));
        assertEquals(5, PoolSizeCheck.needed(EnumSet.of(Role.API), 64, 64));
        assertEquals(5, PoolSizeCheck.needed(EnumSet.of(Role.API, Role.SCHEDULER), 2, 8));
        assertTrue(PoolSizeCheck.problem(EnumSet.of(Role.WORKER), 2, 8, 15).isEmpty());
        assertTrue(PoolSizeCheck.problem(EnumSet.of(Role.WORKER), 2, 8, 14).isPresent());
        // A process without the worker role is not told to lower worker settings it does not use.
        String why = PoolSizeCheck.problem(EnumSet.of(Role.API), 2, 8, 4).orElseThrow();
        assertFalse(why.contains("KILNMARK_WORKER"), why);
    }

    @Test
    void settingsBelowOneCountAsOneAsTheWorkerReadsThem() {
        assertEquals(PoolSizeCheck.needed(ALL, 1, 1), PoolSizeCheck.needed(ALL, 0, -3));
    }
}
