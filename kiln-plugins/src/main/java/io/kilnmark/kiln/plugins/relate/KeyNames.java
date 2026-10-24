package io.kilnmark.kiln.plugins.relate;

import java.util.List;
import java.util.Locale;

/**
 * Names that suggest keys, compared without case, underscores or other punctuation:
 * {@code Customer_ID}, {@code customerId} and {@code customerid} are the same name.
 */
public final class KeyNames {

    /** Endings that mark a column as an identifier, longest first so {@code number} wins over {@code no}. */
    static final List<String> SUFFIXES = List.of("number", "uuid", "code", "key", "ref", "num", "id", "no");

    private KeyNames() {
    }

    /** Lower case, letters and digits only. */
    public static String normal(String name) {
        StringBuilder b = new StringBuilder(name.length());
        name.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).forEach(b::appendCodePoint);
        return b.toString();
    }

    /** The identifier ending of a normalised name ({@code id}, {@code key}, ...), or null. */
    public static String suffix(String normal) {
        for (String s : SUFFIXES) {
            if (normal.endsWith(s)) {
                return s;
            }
        }
        return null;
    }

    /** True for a name that looks like an identifier: {@code id}, {@code customer_id}, {@code order_no}. */
    public static boolean idLike(String name) {
        return suffix(normal(name)) != null;
    }

    /**
     * A normalised name in the singular, by the common English endings: {@code customers} to
     * {@code customer}, {@code categories} to {@code category}, {@code addresses} to
     * {@code address}, {@code boxes} to {@code box}. Words ending in {@code ss} or {@code us} stay.
     */
    public static String singular(String normal) {
        if (normal.length() > 3 && normal.endsWith("ies")) {
            return normal.substring(0, normal.length() - 3) + "y";
        }
        if (normal.length() > 4 && (normal.endsWith("sses") || normal.endsWith("xes") || normal.endsWith("ches")
            || normal.endsWith("shes") || normal.endsWith("zes"))) {
            return normal.substring(0, normal.length() - 2);
        }
        if (normal.length() > 1 && normal.endsWith("s") && !normal.endsWith("ss") && !normal.endsWith("us")) {
            return normal.substring(0, normal.length() - 1);
        }
        return normal;
    }

    private static final java.util.regex.Pattern FILE_EXTENSION = java.util.regex.Pattern.compile(
        "\\.(csv|tsv|txt|json|jsonl|ndjson|parquet|xlsx|xls|avro|orc)(\\.(gz|zst|bz2))?$",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * What a table or file holds, from its name: {@code Customers}, {@code crm.customers} and
     * {@code exports/customer.csv} all give {@code customer}.
     */
    public static String entity(String assetName) {
        String n = assetName.substring(assetName.lastIndexOf('/') + 1);
        n = FILE_EXTENSION.matcher(n).replaceFirst("");
        n = n.substring(n.lastIndexOf('.') + 1);
        return singular(normal(n));
    }
}
