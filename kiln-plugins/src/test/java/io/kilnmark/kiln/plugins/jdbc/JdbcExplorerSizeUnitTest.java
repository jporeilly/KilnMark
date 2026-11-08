package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Types;

import org.junit.jupiter.api.Test;

/** Explore shows a column's size only where someone set one: text, not text(2147483647). */
class JdbcExplorerSizeUnitTest {

    @Test
    void aSizeTheDriverGivesForNoLimitIsNoSize() {
        // PostgreSQL's driver reports text (and varchar without a length) as VARCHAR of 2147483647
        assertNull(JdbcExplorer.ownSize(Types.VARCHAR, Integer.MAX_VALUE));
        assertNull(JdbcExplorer.ownSize(Types.VARCHAR, 0));
        assertNull(JdbcExplorer.ownSize(Types.VARCHAR, null));
        assertNull(JdbcExplorer.ownSize(Types.VARCHAR, JdbcExplorer.MOST_SIZE + 1));
    }

    @Test
    void aSizeSomeoneSetIsKept() {
        assertEquals(80, JdbcExplorer.ownSize(Types.VARCHAR, 80));
        assertEquals(12, JdbcExplorer.ownSize(Types.NUMERIC, 12));
        assertEquals(JdbcExplorer.MOST_SIZE, JdbcExplorer.ownSize(Types.CHAR, JdbcExplorer.MOST_SIZE));
        // a type without a size of its own never shows one
        assertNull(JdbcExplorer.ownSize(Types.INTEGER, 10));
    }
}
