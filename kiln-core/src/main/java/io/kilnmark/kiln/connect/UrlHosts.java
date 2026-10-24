package io.kilnmark.kiln.connect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The host names in URLs such as {@code jdbc:postgresql://h1:5432,h2/db},
 * {@code jdbc:sqlserver://host\instance;databaseName=x} or {@code http://host:9000}: what follows
 * {@code ://}, up to the first {@code /}, {@code ;}, {@code ?}, {@code #}, {@code \} or space,
 * without any {@code user@} before it, split at commas, each without its port. So in
 * {@code jdbc:sqlserver://localhost;user=a@b} the host is {@code localhost}, not {@code b}.
 */
public final class UrlHosts {

    private UrlHosts() {
    }

    /** Where a host name sits in a string. */
    private record Span(int start, int end) {
    }

    private static List<Span> spans(String value) {
        List<Span> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        int from = 0;
        while (true) {
            int scheme = value.indexOf("://", from);
            if (scheme < 0) {
                return out;
            }
            int start = scheme + 3;
            int end = start;
            while (end < value.length() && "/;?#\\ \t\r\n".indexOf(value.charAt(end)) < 0) {
                end++;
            }
            int at = value.lastIndexOf('@', end - 1);
            int hosts = at >= start ? at + 1 : start;
            int i = hosts;
            while (i <= end) {
                int comma = value.indexOf(',', i);
                int entryEnd = comma < 0 || comma > end ? end : comma;
                int hostEnd;
                if (i < entryEnd && value.charAt(i) == '[') {
                    int close = value.indexOf(']', i);
                    hostEnd = close < 0 || close > entryEnd ? entryEnd : close + 1;
                } else {
                    int colon = value.indexOf(':', i);
                    hostEnd = colon < 0 || colon > entryEnd ? entryEnd : colon;
                }
                if (hostEnd > i) {
                    out.add(new Span(i, hostEnd));
                }
                i = entryEnd + 1;
            }
            from = Math.max(end, start);
        }
    }

    /** The host names in every URL in {@code value}, as written. */
    public static List<String> hosts(String value) {
        List<String> out = new ArrayList<>();
        for (Span s : spans(value)) {
            out.add(value.substring(s.start(), s.end()));
        }
        return out;
    }

    /** {@code value} with each host name replaced by what {@code replace} returns for it. */
    public static String replace(String value, UnaryOperator<String> replace) {
        List<Span> spans = spans(value);
        if (spans.isEmpty()) {
            return value;
        }
        StringBuilder b = new StringBuilder();
        int last = 0;
        for (Span s : spans) {
            b.append(value, last, s.start()).append(replace.apply(value.substring(s.start(), s.end())));
            last = s.end();
        }
        return b.append(value.substring(last)).toString();
    }
}
