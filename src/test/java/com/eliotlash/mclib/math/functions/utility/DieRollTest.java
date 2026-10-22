package com.eliotlash.mclib.math.functions.utility;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;

/**
 * {@code math.die_roll} / {@code math.die_roll_integer} must terminate.
 *
 * <p>The vendored mclib copies looped {@code while (i < num)} without ever advancing
 * {@code i} (and {@code DieRoll} rolled {@code high - high}), so any keyframe expression
 * like {@code math.die_roll(3,1,6)} froze the client on the render thread. These tests are
 * written with a preemptive timeout so a regression fails instead of hanging the suite.
 */
class DieRollTest {

    private static double roll(double... args) {
        Double value = assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            () -> create(DieRoll.class, args).get(),
            "math.die_roll must terminate");
        return value;
    }

    private static double rollInteger(double... args) {
        Double value = assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            () -> create(DieRollInteger.class, args).get(),
            "math.die_roll_integer must terminate");
        return value;
    }

    private static com.eliotlash.mclib.math.functions.Function create(
        Class<? extends com.eliotlash.mclib.math.functions.Function> type, double[] args) throws Exception {
        IValue[] values = new IValue[args.length];
        for (int i = 0; i < args.length; i++) {
            values[i] = new Constant(args[i]);
        }
        return type.getConstructor(IValue[].class, String.class)
            .newInstance(values, "die_roll");
    }

    @Test
    void dieRollSumsRollsInsideTheRange() {
        for (int attempt = 0; attempt < 100; attempt++) {
            double value = roll(3, 1, 6);
            assertTrue(value >= 3 && value <= 18, "3 rolls in [1,6] must land in [3,18], got " + value);
        }
        // A degenerate range is the only case with a predictable value.
        assertEquals(6.0d, roll(6, 1, 1), 1.0e-9);
    }

    @Test
    void dieRollWithNoRollsIsZero() {
        assertEquals(0.0d, roll(0, 1, 6), 1.0e-9);
        assertEquals(0.0d, roll(-5, 1, 6), 1.0e-9);
    }

    @Test
    void dieRollCountIsBounded() {
        // A model asking for a million rolls must not stall the render thread.
        assertEquals(100.0d, roll(1_000_000, 1, 1), 1.0e-9);
    }

    @Test
    void dieRollIntegerProducesWholeNumbersInsideTheRange() {
        for (int attempt = 0; attempt < 100; attempt++) {
            double value = rollInteger(20, 1, 6);
            assertEquals(Math.floor(value), value, 1.0e-9, "integer rolls must stay integral");
            assertTrue(value >= 20 && value <= 120, "20 rolls in [1,6] must land in [20,120], got " + value);
        }
        // A degenerate range with 4 rolls is the only deterministic integer case.
        assertEquals(8.0d, rollInteger(4, 2, 2), 1.0e-9);
        // Reversed bounds are normalised instead of producing an empty range.
        double swapped = rollInteger(4, 6, 1);
        assertTrue(swapped >= 4 && swapped <= 24, "reversed bounds must still roll in [1,6], got " + swapped);
    }

    @Test
    void dieRollIntegerSumDoesNotOverflowAtIntEndpoints() {
        assertEquals(4294967294.0d, rollInteger(2, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(-4294967296.0d, rollInteger(2, Integer.MIN_VALUE, Integer.MIN_VALUE));
    }

    @Test
    void dieRollIntegerCountRemainsBoundedWithWideAccumulation() {
        assertEquals(100.0d * Integer.MAX_VALUE,
            rollInteger(1_000_000, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(100.0d * Integer.MIN_VALUE,
            rollInteger(1_000_000, Integer.MIN_VALUE, Integer.MIN_VALUE));
    }

    @Test
    void dieRollIntegerSupportsRangesWiderThanIntMax() {
        boolean differsFromLowerEndpoint = false;
        for (int attempt = 0; attempt < 100; attempt++) {
            double nonNegative = rollInteger(1, 0, Integer.MAX_VALUE);
            assertTrue(nonNegative >= 0 && nonNegative <= Integer.MAX_VALUE,
                "wide non-negative interval must not produce a negative roll: " + nonNegative);
            assertEquals(Math.floor(nonNegative), nonNegative);

            double fullRange = rollInteger(1, Integer.MIN_VALUE, Integer.MAX_VALUE);
            assertTrue(fullRange >= Integer.MIN_VALUE && fullRange <= Integer.MAX_VALUE);
            assertEquals(Math.floor(fullRange), fullRange);
            differsFromLowerEndpoint |= fullRange != Integer.MIN_VALUE;

            double reversed = rollInteger(1, Integer.MAX_VALUE, Integer.MIN_VALUE);
            assertTrue(reversed >= Integer.MIN_VALUE && reversed <= Integer.MAX_VALUE);
            assertEquals(Math.floor(reversed), reversed);
        }
        assertTrue(differsFromLowerEndpoint, "full-range width must not wrap to zero and always return its lower endpoint");
    }

    @Test
    void dieRollIntegerWithNoRollsIsZero() {
        assertEquals(0.0d, rollInteger(0, 1, 6), 1.0e-9);
    }
}
