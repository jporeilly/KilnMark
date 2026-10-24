package io.kilnmark.kiln.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class UrlHostsTest {

    @Test
    void findsTheHostsOfDatabaseAndHttpUrls() {
        assertEquals(List.of("db.example.com"), UrlHosts.hosts("jdbc:postgresql://db.example.com:5432/sales"));
        assertEquals(List.of("h1", "h2"), UrlHosts.hosts("jdbc:postgresql://h1:5432,h2/sales?ssl=true"));
        assertEquals(List.of("localhost"), UrlHosts.hosts("jdbc:sqlserver://localhost;user=a@b;databaseName=x"));
        assertEquals(List.of("sql1"), UrlHosts.hosts("jdbc:sqlserver://sql1\\SQLEXPRESS;databaseName=x"));
        assertEquals(List.of("[::1]"), UrlHosts.hosts("jdbc:postgresql://[::1]:5432/x"));
        assertEquals(List.of("store"), UrlHosts.hosts("http://user@store:9000/bucket"));
        assertEquals(List.of(), UrlHosts.hosts("/srv/files"));
        assertEquals(List.of(), UrlHosts.hosts(null));
    }

    @Test
    void replacesOnlyHosts() {
        assertEquals("jdbc:postgresql://X:5432,X/db", UrlHosts.replace("jdbc:postgresql://a:5432,b/db", h -> "X"));
        assertEquals("no url here", UrlHosts.replace("no url here", h -> "X"));
    }
}
