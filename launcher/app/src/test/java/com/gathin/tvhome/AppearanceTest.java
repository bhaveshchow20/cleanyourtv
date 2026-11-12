package com.gathin.tvhome;

import org.junit.After;
import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** tvos27's appearance: Automatic follows the real sun, and Light is a complete token swap (TvDesign). */
public class AppearanceTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final LocalDate MIDSUMMER = LocalDate.of(2026, 6, 21), MIDWINTER = LocalDate.of(2026, 12, 21);

    @After public void dark() {
        TvDesign.palette(false);
    }

    @Test public void automaticTurnsAtSunriseAndSunset() {
        int[] sun = Sun.riseAndSet(MIDSUMMER, 40.71, -74.01, NY);
        assertNotNull(sun);
        assertFalse(TvDesign.daylight(MIDSUMMER, sun[0] - 1, "", "", NY));
        assertTrue(TvDesign.daylight(MIDSUMMER, sun[0], "", "", NY));
        assertTrue(TvDesign.daylight(MIDSUMMER, sun[1] - 1, "", "", NY));
        assertFalse(TvDesign.daylight(MIDSUMMER, sun[1], "", "", NY));
    }

    @Test public void theSunNotTheClockDecides() {
        // New York in June: light at 06:00 and 20:00, which the old fixed 07:00-19:00 called dark.
        assertTrue(TvDesign.daylight(MIDSUMMER, 6 * 60, "", "", NY));
        assertTrue(TvDesign.daylight(MIDSUMMER, 20 * 60, "", "", NY));
        // and in December dark at 17:00, which it called light.
        assertFalse(TvDesign.daylight(MIDWINTER, 17 * 60, "", "", NY));
    }

    @Test public void configXmlCoordinatesWinOverTheZone() {
        // Reykjavik's midsummer in New York's zone: still light at 23:30 there (sunset ~00:04 local).
        assertTrue(TvDesign.daylight(MIDSUMMER, 23 * 60 + 30, "64.15", "-21.94", ZoneId.of("Atlantic/Reykjavik")));
        assertFalse(TvDesign.daylight(MIDSUMMER, 23 * 60 + 30, "", "", NY));
    }

    @Test public void unreadableCoordinatesFallBackToTheZone() {
        assertEquals(TvDesign.daylight(MIDSUMMER, 12 * 60, "", "", NY),
                TvDesign.daylight(MIDSUMMER, 12 * 60, "north", "west", NY));
    }

    @Test public void aZoneWithNoPlaceKeepsSevenToSeven() {
        ZoneId utc = ZoneId.of("Etc/UTC");
        assertFalse(TvDesign.daylight(MIDSUMMER, 7 * 60 - 1, "", "", utc));
        assertTrue(TvDesign.daylight(MIDSUMMER, 7 * 60, "", "", utc));
        assertTrue(TvDesign.daylight(MIDSUMMER, 19 * 60 - 1, "", "", utc));
        assertFalse(TvDesign.daylight(MIDSUMMER, 19 * 60, "", "", utc));
    }

    @Test public void midnightSunIsLightAndPolarNightDark() {
        ZoneId svalbard = ZoneId.of("Arctic/Longyearbyen");
        assertTrue(TvDesign.daylight(MIDSUMMER, 0, "", "", svalbard));
        assertFalse(TvDesign.daylight(MIDWINTER, 12 * 60, "", "", svalbard));
    }

    @Test public void everyZoneThatHasAPlaceGetsASaneOne() {
        assertNull(ZonePlace.of("Etc/UTC"));
        assertNull(ZonePlace.of("UTC"));
        double[] guess = ZonePlace.of("America/Indiana/Indianapolis");   // not in the table: offset and continent
        assertNotNull(guess);
        assertEquals(-75.0, guess[1], 0.01);
        for (String id : ZoneId.getAvailableZoneIds()) {
            double[] at = ZonePlace.of(id);
            if (at == null) continue;
            assertTrue(id, at[0] >= -90 && at[0] <= 90 && at[1] >= -180 && at[1] <= 180);
        }
    }

    @Test public void lightSwapsEveryTokenAndDarkPutsThemBack() {
        TvDesign.palette(true);
        assertTrue(TvDesign.light);
        assertEquals(0xFF000000, TvDesign.Color.LABEL);
        assertEquals(0xFFF2F2F7, TvDesign.Color.BACKDROP_TOP);
        assertEquals(0xFFD9DAE0, TvDesign.Color.BACKDROP_BOTTOM);
        assertEquals(0xFFFFFFFF, TvDesign.Glass.FOCUSED_FILL);
        assertEquals("dark text on a white focus in both", 0xFF1C1C1E, TvDesign.Glass.FOCUSED_TEXT);
        assertTrue(TvDesign.Glass.TINT_TOP > 0x80);
        TvDesign.palette(false);
        assertFalse(TvDesign.light);
        assertEquals(0xFFFFFFFF, TvDesign.Color.LABEL);
        assertEquals(0xFF1C1F26, TvDesign.Color.BACKDROP_TOP);
        assertEquals(0xF5FFFFFF, TvDesign.Glass.FOCUSED_FILL);
        assertTrue(TvDesign.Glass.TINT_TOP < 0x80);
    }

    @Test public void labelsContrastWithTheirBackdropInBothAppearances() {
        for (boolean light : new boolean[]{false, true}) {
            TvDesign.palette(light);
            double ratio = contrast(TvDesign.Color.LABEL, TvDesign.Color.BACKDROP_BOTTOM);
            assertTrue((light ? "light " : "dark ") + ratio, ratio >= 7);   // WCAG AAA for body text
        }
    }

    @Test public void unknownAppearanceValuesAreDark() {
        assertEquals(TvDesign.DARK, TvDesign.appearance(null));
        assertEquals(TvDesign.DARK, TvDesign.appearance("dim"));
        assertEquals(TvDesign.LIGHT, TvDesign.appearance(" Light "));
    }

    private static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int c) {
        double[] ch = {(c >> 16 & 0xFF) / 255.0, (c >> 8 & 0xFF) / 255.0, (c & 0xFF) / 255.0};
        for (int i = 0; i < 3; i++) ch[i] = ch[i] <= 0.03928 ? ch[i] / 12.92 : Math.pow((ch[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
    }
}
