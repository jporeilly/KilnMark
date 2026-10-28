package io.kilnmark.kiln.plugins.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The settings written into a JDBC URL whatever the driver's syntax, for the generic JDBC
 * connector's allow-list: every {@code key=value} after {@code ;}, {@code ?}, {@code &}, {@code :},
 * {@code /}, {@code ,} or inside {@code ( )}, so {@code ;INIT=...} (H2), {@code ?a=1&b=2},
 * {@code /DB:traceFile=x;} (Db2), {@code /DATABASE=x,CHARSET=y} (Teradata),
 * {@code address=(host=h)(key=v)} (MySQL) and {@code (DESCRIPTION=(ADDRESS=...))} (Oracle) are all
 * read. Every {@code =} in the URL must belong to such a pair; a URL with one that does not, or with
 * quotes, braces, back-slashes, spaces or percent-encoding (which a driver could read as more
 * settings), cannot be read, and is refused rather than guessed at.
 */
final class JdbcUrlSettings {

    /** A setting as written. */
    record Pair(String key, String value) {

        /** The key as drivers compare it: lower case. */
        String name() {
            return key.toLowerCase(Locale.ROOT);
        }
    }

    /** The settings, or why the URL cannot be read (then no pairs). */
    record Parsed(List<Pair> pairs, String problem) {
    }

    private static final Pattern KEY = Pattern.compile("(?<=[;?&:(/,])([A-Za-z0-9_.-]+)=");
    private static final String VALUE_END = ";&()?";

    private JdbcUrlSettings() {
    }

    static Parsed parse(String url) {
        if (url == null) {
            return new Parsed(List.of(), null);
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x20 || c == 0x7f || "'\"`{}<>\\%".indexOf(c) >= 0) {
                return new Parsed(List.of(), "Kilnmark cannot read the settings in this URL: it holds "
                    + (c <= 0x20 || c == 0x7f ? "a space or control character" : "the character " + c)
                    + ", which drivers read in ways that could hide a setting. Write values plainly");
            }
        }
        Matcher m = KEY.matcher(url);
        List<int[]> keys = new ArrayList<>(); // key start, '=' index
        List<String> names = new ArrayList<>();
        while (m.find()) {
            keys.add(new int[] {m.start(1), m.end() - 1});
            names.add(m.group(1));
        }
        int k = 0;
        for (int i = url.indexOf('='); i >= 0; i = url.indexOf('=', i + 1)) {
            while (k < keys.size() && keys.get(k)[1] < i) {
                k++;
            }
            if (k >= keys.size() || keys.get(k)[1] != i) {
                return new Parsed(List.of(), "Kilnmark cannot tell which setting the = at character " + (i + 1)
                    + " of this URL belongs to. Write settings as name=value after ; ? & : / , or inside ( )");
            }
        }
        List<Pair> pairs = new ArrayList<>();
        for (int j = 0; j < keys.size(); j++) {
            int from = keys.get(j)[1] + 1;
            int to = j + 1 < keys.size() ? keys.get(j + 1)[0] - 1 : url.length();
            for (int x = from; x < to; x++) {
                if (VALUE_END.indexOf(url.charAt(x)) >= 0) {
                    to = x;
                    break;
                }
            }
            pairs.add(new Pair(names.get(j), url.substring(from, Math.max(from, to))));
        }
        return new Parsed(List.copyOf(pairs), null);
    }

    /** Whether a setting's name says it names a host: failover partners, server names, alternate servers. */
    static boolean namesHost(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("failoverpartner") || n.endsWith("servername") || n.startsWith("alternate")
            || n.contains("host") || n.equals("server") || n.equals("address") || n.endsWith("serverlist")
            || n.endsWith("servernames");
    }

    /** The hosts in a host setting's value: comma-separated, without ports, SQL Server instances or brackets. */
    static List<String> hostsIn(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String h = part.strip();
            int cut = h.indexOf('\\');
            h = cut < 0 ? h : h.substring(0, cut);
            if (h.startsWith("[")) {
                int close = h.indexOf(']');
                h = close < 0 ? h.substring(1) : h.substring(1, close);
            } else {
                int slash = h.indexOf('/');
                h = slash < 0 ? h : h.substring(0, slash);
                int colon = h.indexOf(':');
                h = colon < 0 ? h : h.substring(0, colon);
            }
            if (!h.isEmpty() && !out.contains(h)) {
                out.add(h);
            }
        }
        return out;
    }
}
