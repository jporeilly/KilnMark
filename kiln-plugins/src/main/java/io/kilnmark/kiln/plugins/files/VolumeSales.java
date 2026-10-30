package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.SplittableRandom;

/**
 * The sample data's one large table, {@value #PATH}: {@value #ROWS} made-up till sales, written
 * as CSV while they are read rather than stored, so a run of the volume sample takes long enough
 * to show where its time goes. The same seed gives the same rows on every read. Every row is
 * {@value #ROW_BYTES} bytes, so the file's size is known without writing it.
 *
 * <p>Columns: sale_id, sold_at (in order through 2026), store_id (100 to 999), sku (one of the
 * sample products, as in stock/products.csv), quantity (1 to 9), unit_price (10.00 to 99.99) and
 * channel (web, app, pos or tel).
 */
final class VolumeSales {

    static final String PATH = "volume/sales.csv";
    static final long ROWS = 4_000_000L;
    static final int ROW_BYTES = 54;
    private static final byte[] HEADER =
        "sale_id,sold_at,store_id,sku,quantity,unit_price,channel\n".getBytes(StandardCharsets.US_ASCII);
    private static final long SEED = 20261030L;
    private static final long FIRST_ID = 10_000_001L;
    private static final long START = LocalDateTime.of(2026, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC);
    private static final long SPAN_SECONDS = 273L * 24 * 3600;
    /** The sample products' SKU prefixes, ten products each, in product_id order (as generate.py writes them). */
    private static final String[] PREFIXES = {"KIT", "GAR", "OFF", "OUT", "LIG", "STO"};
    private static final String[] CHANNELS = {"web", "app", "pos", "tel"};

    private VolumeSales() {
    }

    static long size() {
        return HEADER.length + ROWS * ROW_BYTES;
    }

    /** The SKU of product {@code productId} (1 to 60), as the sample products are numbered. */
    static String sku(int productId) {
        return PREFIXES[(productId - 1) / 10] + "-" + (1000 + productId);
    }

    /** The whole file, written as it is read. */
    static InputStream open() {
        return new Rows();
    }

    /** At most the first {@code bytes} bytes. */
    static byte[] head(int bytes) throws IOException {
        try (InputStream in = open()) {
            return in.readNBytes(bytes);
        }
    }

    /** Writes rows into a buffer one at a time; the reader takes bytes from it. */
    private static final class Rows extends InputStream {
        private final SplittableRandom random = new SplittableRandom(SEED);
        private final byte[] row = new byte[ROW_BYTES];
        private byte[] current = HEADER;
        private int at;
        private long next; // the next row to write, from 0

        @Override
        public int read() {
            if (!fill()) {
                return -1;
            }
            return current[at++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            int done = 0;
            while (done < len && fill()) {
                int n = Math.min(len - done, current.length - at);
                System.arraycopy(current, at, b, off + done, n);
                at += n;
                done += n;
            }
            return done == 0 ? -1 : done;
        }

        /** Whether there are bytes to read, writing the next row when the last one is used up. */
        private boolean fill() {
            if (at < current.length) {
                return true;
            }
            if (next >= ROWS) {
                return false;
            }
            write(next++);
            current = row;
            at = 0;
            return true;
        }

        private void write(long i) {
            int p = 0;
            p = digits(FIRST_ID + i, 8, p);
            row[p++] = ',';
            LocalDateTime t = LocalDateTime.ofEpochSecond(START + i * SPAN_SECONDS / ROWS, 0, ZoneOffset.UTC);
            p = digits(t.getYear(), 4, p);
            row[p++] = '-';
            p = digits(t.getMonthValue(), 2, p);
            row[p++] = '-';
            p = digits(t.getDayOfMonth(), 2, p);
            row[p++] = ' ';
            p = digits(t.getHour(), 2, p);
            row[p++] = ':';
            p = digits(t.getMinute(), 2, p);
            row[p++] = ':';
            p = digits(t.getSecond(), 2, p);
            row[p++] = ',';
            p = digits(100 + random.nextInt(900), 3, p);
            row[p++] = ',';
            p = ascii(sku(1 + random.nextInt(60)), p);
            row[p++] = ',';
            p = digits(1 + random.nextInt(9), 1, p);
            row[p++] = ',';
            int cents = 1000 + random.nextInt(9000);
            p = digits(cents / 100, 2, p);
            row[p++] = '.';
            p = digits(cents % 100, 2, p);
            row[p++] = ',';
            p = ascii(CHANNELS[random.nextInt(CHANNELS.length)], p);
            row[p++] = '\n';
            if (p != ROW_BYTES) {
                throw new IllegalStateException("a sales row of " + p + " bytes, not " + ROW_BYTES);
            }
        }

        private int digits(long value, int width, int p) {
            for (int k = width - 1; k >= 0; k--) {
                row[p + k] = (byte) ('0' + value % 10);
                value /= 10;
            }
            return p + width;
        }

        private int ascii(String s, int p) {
            for (int k = 0; k < s.length(); k++) {
                row[p + k] = (byte) s.charAt(k);
            }
            return p + s.length();
        }
    }
}
