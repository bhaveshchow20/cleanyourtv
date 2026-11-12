package com.gathin.tvhome;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A theme's config.json is hand-written and pushed to TVs we never see: every tvos27 key with a value of the wrong
 * type must leave its default in place rather than crash Home or switch something on (Config.Tvos27Keys).
 */
public class Tvos27KeysTest {

    private static final List<String> XML_KEYS = Collections.singletonList("4062:com.netflix.ninja");

    private static Config.Tvos27Keys parse(String json) throws Exception {
        return Config.Tvos27Keys.parse(json == null ? null : new JSONObject(json), "default", false, false, XML_KEYS);
    }

    @Test public void noThemeKeepsConfigXml() throws Exception {
        Config.Tvos27Keys k = parse(null);
        assertEquals("default", k.designLanguage);
        assertFalse(k.focusParallax);
        assertEquals(TvDesign.DARK, k.appearance);
        assertTrue(k.navigationSounds);
        assertFalse(k.restoreKeyServices);
        assertEquals(XML_KEYS, k.appKeys);
        assertEquals("", k.tmdbKey);
        assertEquals("", k.tmdbRegion);
        assertNull(k.tmdbProviders);
    }

    @Test public void theShippedThemeReadsAsWritten() throws Exception {
        Config.Tvos27Keys k = parse("{\"design_language\":\"tvos27\",\"focus_parallax\":true,\"appearance\":\"light\","
                + "\"navigation_sounds\":false,\"restore_key_services\":true,\"tmdb_key\":\" abc \",\"tmdb_region\":\"auto\","
                + "\"tmdb_providers\":{\"com.x\":\"8,1796\"},"
                + "\"app_keys\":[\"297:com.tcl.tv.plus\",\"313:com.tcl.tv.plus\",\"KEYCODE_TV_INPUT:inputs\"]}");
        assertEquals("tvos27", TvDesign.language(k.designLanguage));
        assertTrue(k.focusParallax);
        assertEquals(TvDesign.LIGHT, k.appearance);
        assertFalse(k.navigationSounds);
        assertTrue(k.restoreKeyServices);
        assertEquals("abc", k.tmdbKey);
        assertEquals("auto", k.tmdbRegion);
        assertEquals("8,1796", k.tmdbProviders.optString("com.x"));
        assertEquals(3, k.appKeys.size());
    }

    @Test public void wrongTypesAreIgnoredAndDefaultsHold() throws Exception {
        Config.Tvos27Keys k = parse("{\"design_language\":27,\"focus_parallax\":\"yes\",\"appearance\":true,"
                + "\"navigation_sounds\":\"off\",\"restore_key_services\":1,\"tmdb_key\":12345,\"tmdb_region\":[\"US\"],"
                + "\"tmdb_providers\":\"8\",\"app_keys\":\"4062:com.netflix.ninja\"}");
        assertEquals("default", k.designLanguage);
        assertFalse(k.focusParallax);
        assertEquals(TvDesign.DARK, k.appearance);
        assertTrue(k.navigationSounds);
        assertFalse(k.restoreKeyServices);
        assertEquals("", k.tmdbKey);
        assertEquals("", k.tmdbRegion);
        assertNull(k.tmdbProviders);
        assertEquals(XML_KEYS, k.appKeys);
    }

    @Test public void anUnknownAppearanceOrLanguageFallsBack() throws Exception {
        assertEquals(TvDesign.DARK, parse("{\"appearance\":\"sepia\"}").appearance);
        assertEquals(TvDesign.AUTO, parse("{\"appearance\":\" AUTO \"}").appearance);
        assertEquals("default", TvDesign.language(parse("{\"design_language\":\"tvos26\"}").designLanguage));
        assertEquals("tvos27", TvDesign.language(parse("{\"design_language\":\" TVOS27 \"}").designLanguage));
    }

    @Test public void appKeysDropBlankAndNonStringEntries() throws Exception {
        Config.Tvos27Keys k = parse("{\"app_keys\":[\"297:com.tcl.tv.plus\",\"\",7,null,\"  \",\"KEYCODE_TV_INPUT:inputs\"]}");
        assertEquals(Arrays.asList("297:com.tcl.tv.plus", "KEYCODE_TV_INPUT:inputs"), k.appKeys);
    }

    @Test public void theThemesTclCodesParseAndNamedKeysResolve() {
        Map<Integer, String> keys = RemoteKeys.parseAppKeys(
                Arrays.asList("297:com.tcl.tv.plus", "313:com.tcl.tv.plus", "4062:com.netflix.ninja",
                        "KEYCODE_TV_INPUT:inputs", "nonsense", ":com.x", "190:"),
                name -> name.equals("KEYCODE_TV_INPUT") ? 178 : 0);
        assertEquals("com.tcl.tv.plus", keys.get(297));
        assertEquals("com.tcl.tv.plus", keys.get(313));
        assertEquals("com.netflix.ninja", keys.get(4062));
        assertEquals(RemoteKeys.INPUTS, keys.get(178));
        assertEquals(4, keys.size());
    }
}
