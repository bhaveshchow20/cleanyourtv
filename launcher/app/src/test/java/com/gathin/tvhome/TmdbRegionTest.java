package com.gathin.tvhome;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which country TMDB is asked about (Tmdb.region). The bug this guards: a US TV whose locale is en-GB asked TMDB for
 * GB, where Hulu and Peacock have nothing, so their backdrops never appeared.
 */
public class TmdbRegionTest {

    @Test public void anExplicitRegionWinsWhateverItsCase() {
        assertEquals("US", Tmdb.region("us", "Europe/London", "GB"));
        assertEquals("IN", Tmdb.region(" IN ", "America/New_York", "US"));
    }

    @Test public void autoFollowsTheTimeZoneOverAnEnGbLocale() {
        assertEquals("US", Tmdb.region("auto", "America/New_York", "GB"));
        assertEquals("US", Tmdb.region("", "America/Los_Angeles", "GB"));
        assertEquals("US", Tmdb.region(null, "America/Chicago", "GB"));
    }

    @Test public void londonIsGb() {
        assertEquals("GB", Tmdb.region("auto", "Europe/London", "US"));
    }

    @Test public void aZoneWithNoCountryFallsBackToTheLocaleThenUs() {
        assertEquals("DE", Tmdb.region("auto", "Etc/UTC", "DE"));
        assertEquals("US", Tmdb.region("auto", "Etc/UTC", ""));
        assertEquals("US", Tmdb.region("auto", "Etc/UTC", null));
    }

    @Test public void aBadRegionValueIsTreatedAsAuto() {
        assertEquals("US", Tmdb.region("United States", "America/Denver", "GB"));
    }

    @Test public void onlyANetworkFailureTriesTheOtherHost() {
        assertTrue(Tmdb.switchHost(new java.net.UnknownHostException("api.themoviedb.org")));
        assertTrue(Tmdb.switchHost(new javax.net.ssl.SSLHandshakeException("blocked")));
        assertTrue(Tmdb.switchHost(new java.net.ConnectException("refused")));
        assertFalse("a bad key is a bad key on both hosts", Tmdb.switchHost(new Tmdb.HttpStatusException(401)));
        assertEquals(2, Tmdb.HOSTS.length);
        assertTrue(Tmdb.HOSTS[1].startsWith("https://api.tmdb.org/"));
    }
}
