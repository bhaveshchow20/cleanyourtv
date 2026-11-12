package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The label-to-provider match that decides which apps get TMDB backdrops (TmdbMatch). The catalogue is a copy of the
 * names and ids TMDB's /watch/providers returned for US on a real TV (Nov 2026), resellers and stores included, so a
 * change to the normalising or the ranking that would send an app to the wrong service fails here.
 */
public class TmdbMatchTest {

    private static final Object[][] US = {
            {8, "Netflix"}, {1796, "Netflix Standard with Ads"}, {175, "Netflix Kids"},
            {9, "Amazon Prime Video"}, {2100, "Amazon Prime Video with Ads"}, {613, "Amazon Prime Video Free with Ads"},
            {10, "Amazon Video"}, {337, "Disney Plus"}, {15, "Hulu"}, {1899, "HBO Max"},
            {1825, "HBO Max Amazon Channel"}, {350, "Apple TV"}, {2, "Apple TV Store"},
            {531, "Paramount Plus"}, {2303, "Paramount Plus Premium"}, {2616, "Paramount Plus Essential"},
            {2304, "Paramount Plus Basic with Ads"}, {582, "Paramount+ Amazon Channel"},
            {1853, "Paramount Plus Apple TV Channel "}, {633, "Paramount+ Roku Premium Channel"},
            {386, "Peacock Premium"}, {387, "Peacock Premium Plus"}, {2077, "Peacock Premium Plus Amazon Channel"},
            {73, "Tubi TV"}, {300, "Pluto TV"}, {207, "The Roku Channel"}, {3, "Google Play Movies"},
            {192, "YouTube"}, {188, "YouTube Premium"}, {283, "Crunchyroll"},
    };

    private static List<TmdbMatch.Provider> catalogue() {
        List<TmdbMatch.Provider> out = new ArrayList<>();
        for (int i = 0; i < US.length; i++) out.add(new TmdbMatch.Provider((Integer) US[i][0], (String) US[i][1], i));
        return out;
    }

    private static List<String> ids(String label) {
        String m = TmdbMatch.match(label, catalogue());
        return m == null ? null : Arrays.asList(m.split("\\|"));
    }

    @Test public void netflixTakesItsAdsTierButNotKids() {
        assertEquals(Arrays.asList("8", "1796"), ids("Netflix"));
    }

    @Test public void primeVideoIsAmazonPrimeVideoWithItsAdsTiersNotTheStore() {
        List<String> got = ids("Prime Video");
        assertEquals("9", got.get(0));
        assertEquals(new java.util.HashSet<>(Arrays.asList("9", "2100", "613")), new java.util.HashSet<>(got));
    }

    @Test public void disneyPlusMatchesBySpellingThePlus() {
        assertEquals(Collections.singletonList("337"), ids("Disney+"));
    }

    @Test public void appleTvIsTheSubscriptionNotTheStoreOrAChannel() {
        assertEquals(Collections.singletonList("350"), ids("Apple TV"));
    }

    @Test public void appleTvMatchesUnderTmdbsOldNameToo() {
        List<TmdbMatch.Provider> old = catalogue();
        old.set(11, new TmdbMatch.Provider(350, "Apple TV+", 11));
        assertEquals("350", TmdbMatch.match("Apple TV", old));
    }

    @Test public void maxAndHboMaxAreTheSameService() {
        assertEquals(Collections.singletonList("1899"), ids("Max"));
        assertEquals(Collections.singletonList("1899"), ids("HBO Max"));
    }

    @Test public void huluAndTubi() {
        assertEquals(Collections.singletonList("15"), ids("Hulu"));
        assertEquals(Collections.singletonList("73"), ids("Tubi"));
    }

    @Test public void peacockTakesBothTiersButNoChannel() {
        assertEquals(Arrays.asList("386", "387"), ids("Peacock"));
        assertEquals(Arrays.asList("386", "387"), ids("Peacock TV"));
    }

    @Test public void paramountTakesItsTiersButNoResellerChannel() {
        List<String> got = ids("Paramount+");
        assertEquals("531", got.get(0));
        assertEquals(new java.util.HashSet<>(Arrays.asList("531", "2303", "2616", "2304")), new java.util.HashSet<>(got));
    }

    @Test public void demoAndBracketedLabelsMatchLikeTheRealApp() {
        assertEquals(ids("Hulu"), ids("Hulu (demo)"));
        assertEquals(ids("Netflix"), ids("Netflix [Beta]"));
    }

    @Test public void appsThatAreNotStreamingServicesNeverMatch() {
        for (String label : new String[]{"YouTube", "YouTube Kids", "Google Play Store", "Settings", "Spotify",
                "Google Play Movies", "Live Channels", "TV", "", "  "}) {
            assertNull(label, TmdbMatch.match(label, catalogue()));
        }
        assertNull(TmdbMatch.match(null, catalogue()));
    }

    @Test public void anEmptyOrMissingCatalogueMatchesNothing() {
        assertNull(TmdbMatch.match("Netflix", Collections.emptyList()));
        assertNull(TmdbMatch.match("Netflix", null));
    }

    @Test public void normaliseRemovesPlusTvAppAndPunctuation() {
        assertEquals("disney", TmdbMatch.normalise("Disney+"));
        assertEquals("paramount", TmdbMatch.normalise("Paramount Plus"));
        assertEquals("tubi", TmdbMatch.normalise("Tubi TV"));
        assertEquals("prime video", TmdbMatch.normalise("Prime Video (demo)"));
    }
}
