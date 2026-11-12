package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The Sleep Timer's numbers (SleepTimer): what Control Center shows, and when the TV goes to sleep. */
public class SleepTimerTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test public void minutesLeftRoundUpSoTheLastMinuteReadsOne() {
        assertEquals(30, SleepTimer.minutesLeft(NOW + 30 * 60_000L, NOW));
        assertEquals(30, SleepTimer.minutesLeft(NOW + 29 * 60_000L + 1, NOW));
        assertEquals(29, SleepTimer.minutesLeft(NOW + 29 * 60_000L, NOW));
        assertEquals(1, SleepTimer.minutesLeft(NOW + 1, NOW));
        assertEquals(1, SleepTimer.minutesLeft(NOW + 60_000L, NOW));
    }

    @Test public void aTimerThatHasEndedIsOff() {
        assertEquals(0, SleepTimer.minutesLeft(NOW, NOW));
        assertEquals(0, SleepTimer.minutesLeft(NOW - 5_000, NOW));
        assertEquals(0, SleepTimer.minutesLeft(0, NOW));
    }

    @Test public void offCancelsAndEachChoiceEndsOnTime() {
        assertEquals(0, SleepTimer.endFor(0, NOW));
        assertEquals(0, SleepTimer.endFor(-15, NOW));
        for (int m : SleepTimer.MINUTES) {
            if (m == 0) continue;
            long end = SleepTimer.endFor(m, NOW);
            assertEquals(NOW + m * 60_000L, end);
            assertEquals(m, SleepTimer.minutesLeft(end, NOW));
        }
        assertEquals(0, SleepTimer.MINUTES[0]);
    }
}
