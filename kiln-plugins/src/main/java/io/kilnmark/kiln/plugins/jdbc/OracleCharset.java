package io.kilnmark.kiln.plugins.jdbc;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * An Oracle character set ({@code NLS_CHARACTERSET}, {@code NLS_NCHAR_CHARACTERSET}) as a writer
 * checks text against it: whether a value can be stored without a character becoming {@code ?}
 * (or {@code ¿}), and how many bytes it takes. Unicode sets hold everything. A set Kilnmark does
 * not know is treated as holding plain ASCII only, so nothing is changed silently.
 *
 * @param name    the Oracle name, upper case
 * @param java    the Java character set encoding the same characters; null for a Unicode set
 * @param unicode whether every character can be stored
 */
record OracleCharset(String name, Charset java, boolean unicode) {

    /** Oracle names and the Java character sets that hold the same characters. */
    private static final Map<String, String> JAVA = Map.ofEntries(
        Map.entry("US7ASCII", "US-ASCII"),
        Map.entry("WE8ISO8859P1", "ISO-8859-1"),
        Map.entry("WE8ISO8859P15", "ISO-8859-15"),
        Map.entry("EE8ISO8859P2", "ISO-8859-2"),
        Map.entry("CL8ISO8859P5", "ISO-8859-5"),
        Map.entry("EL8ISO8859P7", "ISO-8859-7"),
        Map.entry("IW8ISO8859P8", "ISO-8859-8"),
        Map.entry("WE8ISO8859P9", "ISO-8859-9"),
        Map.entry("WE8MSWIN1252", "windows-1252"),
        Map.entry("EE8MSWIN1250", "windows-1250"),
        Map.entry("CL8MSWIN1251", "windows-1251"),
        Map.entry("EL8MSWIN1253", "windows-1253"),
        Map.entry("TR8MSWIN1254", "windows-1254"),
        Map.entry("IW8MSWIN1255", "windows-1255"),
        Map.entry("AR8MSWIN1256", "windows-1256"),
        Map.entry("BLT8MSWIN1257", "windows-1257"),
        Map.entry("VN8MSWIN1258", "windows-1258"),
        Map.entry("CL8KOI8R", "KOI8-R"),
        Map.entry("TH8TISASCII", "TIS-620"),
        Map.entry("JA16SJIS", "windows-31j"),
        Map.entry("JA16EUC", "EUC-JP"),
        Map.entry("ZHS16GBK", "GBK"),
        Map.entry("ZHT16BIG5", "Big5"),
        Map.entry("ZHT16MSWIN950", "x-windows-950"),
        Map.entry("KO16MSWIN949", "x-windows-949"),
        Map.entry("KO16KSC5601", "EUC-KR"));

    /** The character set Oracle calls {@code oracleName}; plain ASCII for one Kilnmark does not know. */
    static OracleCharset of(String oracleName) {
        String name = oracleName == null ? "AL32UTF8" : oracleName.strip().toUpperCase(Locale.ROOT);
        switch (name) {
            case "AL32UTF8", "UTF8", "AL16UTF16", "UTFE" -> {
                return new OracleCharset(name, null, true);
            }
            default -> {
                String java = JAVA.get(name);
                Charset cs = StandardCharsets.US_ASCII;
                if (java != null) {
                    try {
                        cs = Charset.forName(java);
                    } catch (IllegalArgumentException e) {
                        // not in this Java runtime: ASCII only, which every one of them holds
                    }
                }
                return new OracleCharset(name, cs, false);
            }
        }
    }

    /**
     * Why {@code s} cannot be stored in this character set, naming the first character it lacks;
     * null when it can.
     */
    String unstorable(String s) {
        if (unicode) {
            return null;
        }
        CharsetEncoder encoder = java.newEncoder();
        if (encoder.canEncode(s)) {
            return null;
        }
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            String one = new String(Character.toChars(c));
            if (!encoder.canEncode(one)) {
                return "the character '" + one + "' (U+" + String.format(Locale.ROOT, "%04X", c) + ") cannot be stored "
                    + "in the database's character set " + name + (JAVA.containsKey(name) ? "" : " (which Kilnmark "
                    + "does not know, so it writes plain ASCII only)") + ": Oracle would store a ? instead";
            }
            i += Character.charCount(c);
        }
        return null;
    }

    /** How many bytes {@code s} takes in this character set. */
    long bytes(String s) {
        return switch (name) {
            case "AL32UTF8" -> s.getBytes(StandardCharsets.UTF_8).length;
            // Oracle's UTF8 is CESU-8: a character beyond the Basic Multilingual Plane takes six bytes
            case "UTF8" -> s.getBytes(StandardCharsets.UTF_8).length
                + 2L * s.codePoints().filter(c -> c > 0xFFFF).count();
            case "AL16UTF16" -> 2L * s.length();
            default -> unicode ? s.getBytes(StandardCharsets.UTF_8).length : s.getBytes(java).length;
        };
    }
}
