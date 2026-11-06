package io.kilnmark.kiln.nodes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Join keys' numbers: equal values give equal bytes whatever their type, and bytes order as values do. */
class KeyCodecTest {

    private static byte[] of(long v) {
        KeyCodec.Sink s = new KeyCodec.Sink(8);
        KeyCodec.number(v, s);
        return s.trimmed();
    }

    private static byte[] of(BigDecimal d) {
        KeyCodec.Sink s = new KeyCodec.Sink(8);
        KeyCodec.number(d, s);
        return s.trimmed();
    }

    @Test
    void aWholeNumberEncodesAsTheSameDecimal() {
        long[] samples = {0, 1, -1, 7, 10, 1200, -1200, 99, 100, 101, Long.MAX_VALUE, Long.MIN_VALUE,
            Long.MIN_VALUE + 1, 1_000_000_000_000L, -10};
        for (long v : samples) {
            assertArrayEquals(of(BigDecimal.valueOf(v)), of(v), "for " + v);
            assertArrayEquals(of(new BigDecimal(BigDecimal.valueOf(v).toBigInteger(), 0).setScale(4)), of(v),
                "for " + v + ".0000");
        }
        Random random = new Random(5);
        for (int i = 0; i < 10_000; i++) {
            long v = random.nextLong() >> random.nextInt(63);
            assertArrayEquals(of(BigDecimal.valueOf(v)), of(v), "for " + v);
        }
    }

    @Test
    void bytesOrderAsTheValuesDo() {
        List<BigDecimal> values = new ArrayList<>();
        Random random = new Random(9);
        for (int i = 0; i < 3_000; i++) {
            values.add(BigDecimal.valueOf(random.nextLong() >> random.nextInt(63), random.nextInt(12) - 3));
        }
        values.addAll(List.of(BigDecimal.ZERO, new BigDecimal("0.001"), new BigDecimal("-0.001"), new BigDecimal("1E+30"),
            new BigDecimal("-1E+30"), new BigDecimal("0.1"), new BigDecimal("0.12"), new BigDecimal("-0.12"),
            new BigDecimal("-0.123")));
        List<BigDecimal> byValue = new ArrayList<>(values);
        byValue.sort(BigDecimal::compareTo);
        List<BigDecimal> byBytes = new ArrayList<>(values);
        byBytes.sort((a, b) -> Arrays.compareUnsigned(of(a), of(b)));
        for (int i = 0; i < values.size(); i++) {
            assertEquals(0, byValue.get(i).compareTo(byBytes.get(i)), "at " + i);
        }
    }
}
