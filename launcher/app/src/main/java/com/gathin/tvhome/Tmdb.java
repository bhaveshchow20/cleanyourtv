package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * design_language tvos27 with a TMDB key (config.json tmdb_key): today's top titles on the focused app's streaming
 * service, for the Top Shelf backdrop. An app's label is matched to TMDB's watch providers by name (TmdbMatch, against
 * the region's provider list fetched once a day), so no package table is kept; discover/movie and discover/tv with
 * with_watch_providers and watch_region give what streams there now, what TMDB has trending today comes first, and
 * films and series take turns. Lists are rebuilt each day (local midnight); backdrops (w1280, or the original on a
 * 4K panel) are kept until the folder grows past 120 MB. Everything runs on one background thread and answers on the main thread; any
 * failure (offline, no key, HTTP error) answers with nothing, so the shelf keeps its gradient.
 */
final class Tmdb {
    private static final String TAG = "TvHome.Tmdb";
    /**
     * TMDB's API, and its official alternate domain for the same API: some networks and ISPs block or redirect
     * api.themoviedb.org. A connection or TLS failure on one retries once on the other, and the one that worked is used
     * for the rest of the day.
     */
    static final String[] HOSTS = {"https://api.themoviedb.org/3", "https://api.tmdb.org/3"};
    private static final String IMAGE = "https://image.tmdb.org/t/p/";
    /** TMDB's image sizes: w1280 for a 1080p panel, the original (usually 3840 wide) for a 4K one. */
    static final String SIZE_HD = "w1280", SIZE_4K = "original";
    /** Title logos: transparent PNGs, 500 wide, enough for a third of a 1080p screen. */
    static final String SIZE_LOGO = "w500";
    /** The largest backdrop ever decoded: a 4K frame. RGB_565 keeps it at 16.6 MB. */
    private static final int MAX_W = 3840, MAX_H = 2160;
    /** How many of the day's titles one app rotates through: today's top, not the whole catalogue. */
    private static final int TOP = 16;
    private static final long RETRY_MS = 10 * 60_000L, DISK_MAX = 120L * 1024 * 1024;

    /** The sanity log of each app's match and the region: adb logcat -s Tmdb. */
    private static final String MATCH_TAG = "Tmdb";

    /** One backdrop: the title's name and its TMDB image path, or a fixture file, and its logo's path if it has one. */
    static final class Title {
        final String name, backdrop, kind;
        /** The title's logo ("/abc.png"), or null: then nothing is drawn over the backdrop. */
        String logo;
        /** "movie:123", to match against today's trending; null from a cached list or the fixture. */
        String id;

        Title(String name, String backdrop, String kind) {
            this.name = name;
            this.backdrop = backdrop;
            this.kind = kind;
        }
    }

    private final String key, region, language;
    /** The locale's country, tried once when the region has nothing on a provider; null when it is the region. */
    private final String fallbackRegion;
    private final Context app;
    /** config.json tmdb_providers: package to ids, or "" for off. Wins over the match. */
    private final Map<String, String> overrides = new HashMap<>();
    /** Package to matched provider ids, "" for none; filled once the provider list is in (ready). */
    private final Map<String, String> matches = new java.util.concurrent.ConcurrentHashMap<>();
    /** The region's providers (and the fallback region's), from TMDB or the day's cache; set before ready. */
    private volatile List<TmdbMatch.Provider> catalogue;
    private volatile boolean ready;
    private final List<Runnable> whenReady = new ArrayList<>();
    private final File dir, images;
    /** Debug builds only: a pushed list stands in for TMDB (cache/tmdb/fixture.json), so the shelf works offline. */
    private final JSONObject fixture;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tmdb");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, List<Title>> lists = new HashMap<>();
    private final Map<String, Long> failedAt = new HashMap<>();
    /** Bumped by cancelPending: queued downloads and decodes from before it are skipped. */
    private volatile int epoch;
    /** SIZE_HD or SIZE_4K, from the panel's physical mode. */
    private final String size;
    /** A bitmap that has left the screen, decoded into next (inBitmap) instead of allocating 16 MB again. */
    private Bitmap spare;

    /** "movie:123" and "tv:456" for what TMDB has trending today, and the day it is for. */
    private java.util.Set<String> trending;
    private String trendingDay;

    private Tmdb(Context ctx, String key, String region, JSONObject overrides, JSONObject fixture) {
        this.key = key;
        this.app = ctx.getApplicationContext();
        String fixed = fixture == null ? null : fixture.optString("region", null);
        this.region = region(fixed != null ? fixed : region);
        String loc = Locale.getDefault().getCountry().toUpperCase(Locale.ROOT);
        this.fallbackRegion = loc.matches("[A-Z]{2}") && !loc.equals(this.region) ? loc : null;
        Locale l = Locale.getDefault();
        this.language = l.getLanguage() + (l.getCountry().isEmpty() ? "" : "-" + l.getCountry());
        this.dir = new File(ctx.getCacheDir(), "tmdb");
        this.images = new File(dir, "img");
        this.size = imageSize(ctx);
        //noinspection ResultOfMethodCallIgnored
        images.mkdirs();
        this.fixture = fixture;
        if (overrides != null) {
            for (java.util.Iterator<String> it = overrides.keys(); it.hasNext(); ) {
                String pkg = it.next();
                String ids = overrides.optString(pkg, "").trim();
                this.overrides.put(pkg, ids.equals("off") ? "" : ids.replace(',', '|'));
            }
        }
        worker.execute(this::prune);
        worker.execute(this::matchApps);
    }

    /**
     * tmdb_region: two letters wins; "auto" (or nothing) is the country of the TV's time zone, since many TVs ship
     * with an en-GB or en-US locale wherever they are sold; then the locale's country; then US.
     */
    static String region(String configured) {
        return region(configured, java.util.TimeZone.getDefault().getID(), Locale.getDefault().getCountry());
    }

    /** {@link #region(String)} for a given time zone and locale country. */
    static String region(String configured, String zoneId, String localeCountry) {
        String r = configured == null ? "" : configured.trim().toUpperCase(Locale.ROOT);
        if (r.matches("[A-Z]{2}")) return r;
        String z = null;
        try {
            z = android.icu.util.TimeZone.getRegion(zoneId);
        } catch (RuntimeException ignored) {
            // an unknown zone id, or no ICU (the JVM tests): the table, then the locale decides
        }
        if (z == null || !z.matches("[A-Z]{2}")) z = ZonePlace.country(zoneId);   // ICU says "001" for Etc/UTC
        if (z != null) return z;
        String loc = localeCountry == null ? "" : localeCountry.toUpperCase(Locale.ROOT);
        return loc.matches("[A-Z]{2}") ? loc : "US";
    }


    /** A Tmdb for this Home, or null: no key (and no debug fixture) means the shelf keeps its gradient. */
    static Tmdb create(Context ctx, Config cfg) {
        if (!cfg.tvos27()) return null;
        JSONObject fixture = null;
        if ((ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            fixture = readJson(new File(new File(ctx.getCacheDir(), "tmdb"), "fixture.json"));
            if (fixture != null) Log.i(TAG, "using the debug fixture instead of TMDB");
        }
        if (cfg.tmdbKey.isEmpty() && fixture == null) return null;
        return new Tmdb(ctx, cfg.tmdbKey, cfg.tmdbRegion, cfg.tmdbProviders, fixture);
    }

    /**
     * True when this app has a streaming service on TMDB; YouTube, Settings and the Play Store do not. False until
     * the provider list is in ({@link #whenReady}); an app installed since is matched here, by its label.
     */
    boolean covers(String pkg) {
        return ids(pkg) != null;
    }

    /** The provider list is in and every app is matched. */
    boolean ready() {
        return ready;
    }

    /** Runs {@code r} on the main thread once {@link #ready}, at once if it already is. */
    void whenReady(Runnable r) {
        synchronized (whenReady) {
            if (!ready) {
                whenReady.add(r);
                return;
            }
        }
        r.run();
    }

    private String ids(String pkg) {
        if (pkg == null) return null;
        String o = overrides.get(pkg);
        if (o != null) return o.isEmpty() ? null : o;
        if (!ready) return null;
        String m = matches.get(pkg);
        if (m == null) {
            m = match(pkg);
            matches.put(pkg, m);
        }
        return m.isEmpty() ? null : m;
    }

    /** The provider ids for {@code pkg}'s label, or "" when it matches none. */
    private String match(String pkg) {
        String label;
        try {
            android.content.pm.PackageManager pm = app.getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
        if (pkg.equals(app.getPackageName())) return "";
        String ids = TmdbMatch.match(label, catalogue);
        Log.i(MATCH_TAG, label + " (" + pkg + ") -> " + (ids == null ? "none" : describe(ids)) + " region=" + region
                + (fallbackRegion != null ? " fallback=" + fallbackRegion : ""));
        return ids == null ? "" : ids;
    }

    private String describe(String ids) {
        StringBuilder b = new StringBuilder();
        for (String id : ids.split("\\|")) {
            String name = id;
            for (TmdbMatch.Provider p : catalogue) if (String.valueOf(p.id).equals(id)) name = p.name.trim() + " " + id;
            if (b.length() > 0) b.append(" + ");
            b.append(name);
        }
        return b.toString();
    }

    /**
     * On the worker: today's provider list, then every launchable app's match, kept for the day; then ready.
     * Offline with no list at all, nothing matches and the shelf keeps its gradient.
     */
    private void matchApps() {
        String today = today();
        catalogue = providers(today);
        File f = new File(dir, "matches-" + region + "-" + today + ".json");
        JSONObject cached = readJson(f);
        if (cached != null) {
            for (java.util.Iterator<String> it = cached.keys(); it.hasNext(); ) {
                String pkg = it.next();
                matches.put(pkg, cached.optString(pkg, ""));
            }
        }
        android.content.pm.PackageManager pm = app.getPackageManager();
        java.util.Set<String> pkgs = new java.util.TreeSet<>();
        for (String cat : new String[] {android.content.Intent.CATEGORY_LEANBACK_LAUNCHER,
                android.content.Intent.CATEGORY_LAUNCHER}) {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(cat);
            for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(i, 0)) pkgs.add(r.activityInfo.packageName);
        }
        boolean fresh = false;
        for (String pkg : pkgs) {
            if (overrides.containsKey(pkg)) {
                Log.i(MATCH_TAG, pkg + " -> tmdb_providers " + (overrides.get(pkg).isEmpty() ? "off" : overrides.get(pkg)));
                continue;
            }
            if (matches.containsKey(pkg)) {
                Log.i(MATCH_TAG, pkg + " -> " + (matches.get(pkg).isEmpty() ? "none" : matches.get(pkg))
                        + " (today's cache) region=" + region + (fallbackRegion != null ? " fallback=" + fallbackRegion : ""));
                continue;
            }
            matches.put(pkg, match(pkg));
            fresh = true;
        }
        if (fresh && !catalogue.isEmpty()) {
            try (FileOutputStream w = new FileOutputStream(f)) {
                w.write(new JSONObject(new HashMap<>(matches)).toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(TAG, "matches: " + e.getMessage());
            }
        }
        List<Runnable> run;
        synchronized (whenReady) {
            ready = true;
            run = new ArrayList<>(whenReady);
            whenReady.clear();
        }
        main.post(() -> { for (Runnable r : run) r.run(); });
    }

    /**
     * TMDB's watch providers for films and series in the region, and in the fallback region, merged by id: once a
     * day, kept on disk; yesterday's when TMDB cannot be reached. The debug fixture's "providers" stand in for it.
     */
    private List<TmdbMatch.Provider> providers(String today) {
        if (fixture != null) return providerList(fixture.optJSONArray("providers"));
        File f = new File(dir, "providers-" + region + ".json");
        JSONObject cached = readJson(f);
        if (cached != null && today.equals(cached.optString("day"))) return providerList(cached.optJSONArray("results"));
        try {
            Map<Integer, JSONObject> byId = new java.util.LinkedHashMap<>();
            for (String r : fallbackRegion == null ? new String[] {region} : new String[] {region, fallbackRegion}) {
                for (String kind : new String[] {"movie", "tv"}) {
                    JSONArray a = get("/watch/providers/" + kind + "?watch_region=" + r + "&language=" + language)
                            .optJSONArray("results");
                    for (int i = 0; a != null && i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null) continue;
                        JSONObject p = new JSONObject().put("provider_id", o.optInt("provider_id"))
                                .put("provider_name", o.optString("provider_name"))
                                .put("display_priority", o.optJSONObject("display_priorities") != null
                                        ? o.optJSONObject("display_priorities").optInt(r, o.optInt("display_priority", 999))
                                        : o.optInt("display_priority", 999));
                        if (!byId.containsKey(p.optInt("provider_id"))) byId.put(p.optInt("provider_id"), p);
                    }
                }
            }
            JSONArray out = new JSONArray(byId.values());
            try (FileOutputStream w = new FileOutputStream(f)) {
                w.write(new JSONObject().put("day", today).put("results", out).toString().getBytes(StandardCharsets.UTF_8));
            }
            return providerList(out);
        } catch (Exception e) {
            Log.w(TAG, "providers: " + e.getMessage());
            return providerList(cached == null ? null : cached.optJSONArray("results"));
        }
    }

    private static List<TmdbMatch.Provider> providerList(JSONArray a) {
        List<TmdbMatch.Provider> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("provider_name").isEmpty()) continue;
            out.add(new TmdbMatch.Provider(o.optInt("provider_id"), o.optString("provider_name"),
                    o.optInt("display_priority", 999)));
        }
        return out;
    }

    private static String today() {
        return new java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new java.util.Date());
    }

    /** Today's titles for this app, top first, on the main thread; empty when there are none or TMDB failed. */
    void titles(String pkg, Consumer<List<Title>> done) {
        String ids = ids(pkg);
        if (ids == null) {
            done.accept(Collections.emptyList());
            return;
        }
        List<Title> hit;
        synchronized (lists) { hit = lists.get(ids); }
        if (hit != null) {
            done.accept(hit);
            return;
        }
        worker.execute(() -> {
            List<Title> out = load(ids);
            synchronized (lists) { if (!out.isEmpty()) lists.put(ids, out); }
            main.post(() -> done.accept(out));
        });
    }

    /**
     * The title's backdrop, from disk or TMDB, decoded to fill {@code width} x {@code height} pixels (the shelf's
     * surface, at most 4K); null on the main thread when it failed.
     */
    void backdrop(Title t, int width, int height, java.util.function.BiConsumer<Bitmap, Bitmap> done) {
        int e0 = epoch;
        worker.execute(() -> {
            if (e0 != epoch) return;
            Bitmap b = null;
            try {
                File f = imageFile(t.backdrop);
                if (!f.isFile() || f.length() == 0) download(IMAGE + size + t.backdrop, f);
                //noinspection ResultOfMethodCallIgnored
                f.setLastModified(System.currentTimeMillis());
                b = decode(f, width, height, takeSpare());
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "backdrop " + t.backdrop + ": " + e.getMessage());
            }
            Bitmap logo = b == null ? null : logo(t, width, height);
            Bitmap result = b;
            if (e0 == epoch) main.post(() -> done.accept(result, logo));
        });
    }

    /** Warms the disk cache for a backdrop shown next, without decoding it. */
    void prefetch(Title t) {
        int e0 = epoch;
        worker.execute(() -> {
            if (e0 != epoch) return;
            try {
                File f = imageFile(t.backdrop);
                if (!f.isFile() || f.length() == 0) download(IMAGE + size + t.backdrop, f);
                if (t.logo != null) {
                    File l = imageFile(images, SIZE_LOGO, t.logo);
                    if (!l.isFile() || l.length() == 0) download(IMAGE + SIZE_LOGO + t.logo, l);
                }
            } catch (Exception e) {
                Log.w(TAG, "prefetch " + t.backdrop + ": " + e.getMessage());
            }
        });
    }

    /** Focus left the shelf or Home went away: drop the queued downloads and decodes, nothing new is needed. */
    void cancelPending() {
        epoch++;
        main.removeCallbacksAndMessages(null);
    }

    /** A backdrop no view draws any more: the next decode reuses its memory. */
    synchronized void giveBack(Bitmap b) {
        if (b == null || b.isRecycled() || !b.isMutable()) return;
        if (spare == null || b.getAllocationByteCount() > spare.getAllocationByteCount()) spare = b;
    }

    private synchronized Bitmap takeSpare() {
        Bitmap b = spare;
        spare = null;
        return b;
    }

    /**
     * Off the shelf: no backdrop memory kept for later. The spare is off screen since it was given back, so its
     * pixels go now rather than at the next GC; a decode in flight already took it.
     */
    void dropSpare() {
        Bitmap b = takeSpare();
        if (b != null) b.recycle();
    }

    void trimMemory() {
        synchronized (lists) { lists.clear(); }
        dropSpare();
    }

    void shutdown() {
        dropSpare();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    // ---- lists ---------------------------------------------------------------------------------

    private List<Title> load(String ids) {
        if (fixture != null) {
            JSONArray a = fixture.optJSONArray(ids);
            if (a == null) a = fixture.optJSONArray(ids.split("\\|")[0]);
            if (a == null) a = fixture.optJSONArray("*");
            return interleave(parse(a), Collections.emptySet());
        }
        String today = today();
        File f = new File(dir, "list-" + ids.replace('|', '_') + "-" + region + "-" + language + ".json");
        JSONObject cached = readJson(f);
        JSONArray old = cached == null ? null : cached.optJSONArray("titles");
        // A list from before logos were fetched is rebuilt once ("logos": true marks the new ones). An empty one from
        // today is the answer too: the service has nothing in the region or the fallback, asked once a day.
        if (cached != null && today.equals(cached.optString("day")) && cached.optBoolean("logos")) {
            return parse(old);
        }
        long now = System.currentTimeMillis();
        Long failed = failedAt.get(ids);
        if (failed != null && now - failed < RETRY_MS) return parse(old);
        try {
            java.util.Set<String> hot = trending(today);
            List<Title> all = new ArrayList<>();
            String used = region;
            for (String kind : new String[] {"movie", "tv"}) {
                for (int page = 1; page <= 2; page++) all.addAll(discover(kind, ids, page, region));
            }
            // Hulu or Peacock on a TV whose region has neither: once in the locale's region before giving up.
            if (all.isEmpty() && fallbackRegion != null) {
                used = fallbackRegion;
                for (String kind : new String[] {"movie", "tv"}) {
                    for (int page = 1; page <= 2; page++) all.addAll(discover(kind, ids, page, fallbackRegion));
                }
            }
            Log.i(MATCH_TAG, "providers " + ids + ": " + all.size() + " titles in " + used
                    + (all.isEmpty() ? " (none; cached as none for today)" : ""));
            List<Title> top = interleave(all, hot);
            JSONArray out = new JSONArray();
            for (Title t : top) {
                t.logo = bestLogo(t.id);
                JSONObject o = new JSONObject().put("name", t.name).put("backdrop", t.backdrop).put("kind", t.kind);
                if (t.logo != null) o.put("logo", t.logo);
                out.put(o);
            }
            try (FileOutputStream w = new FileOutputStream(f)) {
                w.write(new JSONObject().put("day", today).put("logos", true).put("region", used).put("titles", out)
                        .toString().getBytes(StandardCharsets.UTF_8));
            }
            failedAt.remove(ids);
            return top;
        } catch (Exception e) {
            Log.w(TAG, "discover " + ids + ": " + e.getMessage());
            failedAt.put(ids, now);
            return parse(old);   // offline: yesterday's list is better than none
        }
    }

    /**
     * What streams on these providers in the region now, most popular first. A series must have aired an episode in
     * the last three months, so a long-finished show does not crowd out what people are watching this week.
     */
    private List<Title> discover(String kind, String ids, int page, String region) throws Exception {
        String path = "/discover/" + kind + "?with_watch_providers=" + URLEncoder.encode(ids, "UTF-8")
                + "&watch_region=" + region
                + "&with_watch_monetization_types=" + URLEncoder.encode("flatrate|free|ads", "UTF-8")
                + "&sort_by=popularity.desc&include_adult=false&page=" + page + "&language=" + language;
        if ("tv".equals(kind)) {
            String since = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                    .format(new java.util.Date(System.currentTimeMillis() - 90L * 24 * 3600_000L));
            path += "&air_date.gte=" + since;
        }
        return results(get(path), kind);
    }

    /** TMDB's trending today, films and series, two pages each: fetched once a day, kept in memory and on disk. */
    private java.util.Set<String> trending(String today) {
        if (today.equals(trendingDay) && trending != null) return trending;
        File f = new File(dir, "trending-" + language + ".json");
        JSONObject cached = readJson(f);
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (cached != null && today.equals(cached.optString("day"))) {
            JSONArray a = cached.optJSONArray("keys");
            for (int i = 0; a != null && i < a.length(); i++) keys.add(a.optString(i));
        } else {
            try {
                for (String kind : new String[] {"movie", "tv"}) {
                    for (int page = 1; page <= 2; page++) {
                        JSONArray r = get("/trending/" + kind + "/day?page=" + page + "&language=" + language)
                                .optJSONArray("results");
                        for (int i = 0; r != null && i < r.length(); i++) {
                            JSONObject o = r.optJSONObject(i);
                            if (o != null) keys.add(kind + ":" + o.optInt("id"));
                        }
                    }
                }
                try (FileOutputStream w = new FileOutputStream(f)) {
                    w.write(new JSONObject().put("day", today).put("keys", new JSONArray(keys)).toString()
                            .getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                // The service's own popularity still orders the list; try trending again on the next list.
                Log.w(TAG, "trending: " + e.getMessage());
                return keys;
            }
        }
        trending = keys;
        trendingDay = today;
        return keys;
    }

    private static List<Title> results(JSONObject page, String kind) {
        List<Title> out = new ArrayList<>();
        JSONArray results = page.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject o = results.optJSONObject(i);
            if (o == null || o.optBoolean("adult")) continue;
            String backdrop = o.optString("backdrop_path", "");
            if (backdrop.isEmpty() || "null".equals(backdrop)) continue;
            Title t = new Title(o.optString("movie".equals(kind) ? "title" : "name"), backdrop, kind);
            t.id = kind + ":" + o.optInt("id");
            out.add(t);
        }
        return out;
    }

    /**
     * Today's top TOP: trending titles first, then the rest in the service's popularity order, a film and a series
     * in turn so the backdrop mixes both; when one kind runs out the other fills the rest.
     */
    private static List<Title> interleave(List<Title> all, java.util.Set<String> hot) {
        List<Title> movies = new ArrayList<>(), shows = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int pass = 0; pass < 2; pass++) {
            for (Title t : all) {
                boolean isHot = t.id != null && hot.contains(t.id);
                if ((pass == 0) != isHot || seen.contains(t.backdrop)) continue;
                seen.add(t.backdrop);
                ("tv".equals(t.kind) ? shows : movies).add(t);
            }
        }
        List<Title> out = new ArrayList<>();
        for (int i = 0; out.size() < TOP && (i < movies.size() || i < shows.size()); i++) {
            if (i < movies.size()) out.add(movies.get(i));
            if (i < shows.size() && out.size() < TOP) out.add(shows.get(i));
        }
        return out;
    }

    /** Index into HOSTS of the host that answered last, and the day it did; the first host again on a new day. */
    private int host;
    private String hostDay;

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String today = today();
        if (!today.equals(hostDay)) {
            host = 0;
            hostDay = today;
        }
        try {
            return get(HOSTS[host], path);
        } catch (IOException | org.json.JSONException e) {
            // A redirect to a block page answers 200 with HTML, which fails as JSON: that switches too.
            if (e instanceof IOException && !switchHost((IOException) e)) throw (IOException) e;
            int other = 1 - host;
            JSONObject o = get(HOSTS[other], path);
            Log.i(TAG, HOSTS[host] + " failed (" + e.getClass().getSimpleName() + "), using " + HOSTS[other] + " today");
            host = other;
            return o;
        }
    }

    /**
     * A failure the other host might not have: no connection, a refused or reset one, a DNS block, a TLS error from a
     * filter in the middle. Not an HTTP status, which both hosts would answer alike.
     */
    static boolean switchHost(IOException e) {
        return !(e instanceof HttpStatusException);
    }

    /** An HTTP error status from TMDB itself (401 for a bad key, 404, 429). */
    static final class HttpStatusException extends IOException {
        HttpStatusException(int code) {
            super("HTTP " + code);
        }
    }

    private JSONObject get(String api, String path) throws IOException, org.json.JSONException {
        boolean bearer = key.startsWith("eyJ");   // a v4 read access token rather than a v3 api key
        String url = api + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/json");
            if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
            int code = c.getResponseCode();
            if (code >= 400) throw new HttpStatusException(code);
            return new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    private static List<Title> parse(JSONArray a) {
        List<Title> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String b = o.optString("backdrop", "");
            if (!b.startsWith("/")) continue;
            Title t = new Title(o.optString("name", ""), b, o.optString("kind", "movie"));
            String logo = o.optString("logo", "");
            if (logo.startsWith("/")) t.logo = logo;
            out.add(t);
        }
        return out;
    }

    /**
     * The title's logo on TMDB ("movie:123" to /movie/123/images): a PNG in the TV's language, else English, else
     * one with no text language, the best voted of those. SVGs are skipped, since the launcher cannot draw them, and
     * so is a title with none: Home then shows no logo rather than a text name.
     */
    private String bestLogo(String id) {
        if (id == null) return null;
        String lang = Locale.getDefault().getLanguage();
        try {
            JSONArray logos = get("/" + id.replace(':', '/') + "/images?include_image_language="
                    + URLEncoder.encode(lang + ",en,null", "UTF-8")).optJSONArray("logos");
            String best = null;
            double bestScore = -1;
            for (int i = 0; logos != null && i < logos.length(); i++) {
                JSONObject o = logos.optJSONObject(i);
                String path = o == null ? "" : o.optString("file_path", "");
                if (!path.startsWith("/") || !path.toLowerCase(Locale.ROOT).endsWith(".png")) continue;
                String iso = o.isNull("iso_639_1") ? "" : o.optString("iso_639_1", "");
                int rank = iso.equals(lang) ? 3 : iso.equals("en") ? 2 : iso.isEmpty() ? 1 : 0;
                double score = rank * 100 + o.optDouble("vote_average", 0);
                if (rank > 0 && score > bestScore) {
                    bestScore = score;
                    best = path;
                }
            }
            return best;
        } catch (Exception e) {
            Log.w(TAG, "logo " + id + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * The title's logo, fitted into a third of the screen's width and a fifth of its height, with a soft shadow
     * baked in once here so the backdrop's bright parts never swallow it; null with no logo or on any failure.
     */
    private Bitmap logo(Title t, int width, int height) {
        if (t.logo == null) return null;
        try {
            File f = imageFile(images, SIZE_LOGO, t.logo);
            if (!f.isFile() || f.length() == 0) download(IMAGE + SIZE_LOGO + t.logo, f);
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            Bitmap src = BitmapFactory.decodeFile(f.getPath());
            if (src == null) return null;
            float fit = Math.min(Math.min(width * 0.33f / src.getWidth(), height * 0.2f / src.getHeight()), 1.5f);
            int w = Math.max(1, Math.round(src.getWidth() * fit)), h = Math.max(1, Math.round(src.getHeight() * fit));
            int pad = Math.max(4, Math.round(height / 90f));
            Bitmap out = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            android.graphics.Rect dst = new android.graphics.Rect(pad, pad, pad + w, pad + h);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            Bitmap alpha = src.extractAlpha();
            android.graphics.Paint shadow = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            shadow.setColor(TvDesign.light ? 0x40000000 : 0x73000000);   // lighter over the light scrim
            shadow.setMaskFilter(new android.graphics.BlurMaskFilter(pad * 0.8f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            c.save();
            c.translate(0, pad * 0.25f);
            c.drawBitmap(alpha, null, dst, shadow);
            c.restore();
            c.drawBitmap(src, null, dst, p);
            alpha.recycle();
            src.recycle();
            return out;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "logo " + t.logo + ": " + e.getMessage());
            return null;
        }
    }

    // ---- images --------------------------------------------------------------------------------

    private File imageFile(String path) {
        return imageFile(images, size, path);
    }

    /** The cache file for a backdrop path at a size: "original-abc.jpg", so 1080p and 4K files never collide. */
    static File imageFile(File images, String size, String path) {
        return new File(images, size + "-" + path.replaceAll("[^A-Za-z0-9._-]", ""));
    }

    /**
     * The size to fetch for this TV. Many Google TVs draw their UI at 1080p on a 4K panel, so DisplayMetrics says
     * 1920; the display mode's physical width says what the panel is.
     */
    static String imageSize(Context ctx) {
        return TvDesign.panel(ctx).getWidth() >= 3000 ? SIZE_4K : SIZE_HD;
    }

    private static void download(String url, File to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            String type = c.getContentType();
            if (type != null && !type.startsWith("image/")) throw new IOException("not an image: " + type);
            File tmp = new File(to.getPath() + ".part");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(to)) throw new IOException("could not keep " + to);
        } finally {
            c.disconnect();
        }
    }

    /**
     * Just enough pixels to cover {@code width} x {@code height} (centre-cropped), never more than 4K, in RGB_565:
     * 1920x1080 is 4 MB and 3840x2160 is 16.6 MB, and at most two are held. Decodes into {@code reuse} when it is big
     * enough, and gives it back otherwise.
     */
    private Bitmap decode(File f, int width, int height, Bitmap reuse) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            giveBack(reuse);
            return null;
        }
        // The scale that covers the target, as CENTER_CROP draws it.
        float cover = Math.max(Math.min(MAX_W, Math.max(640, width)) / (float) o.outWidth,
                Math.min(MAX_H, Math.max(360, height)) / (float) o.outHeight);
        int sample = 1;
        while (o.outWidth / (sample * 2f) >= o.outWidth * cover) sample *= 2;
        int sampledW = o.outWidth / sample;
        int targetW = Math.min(sampledW, Math.round(o.outWidth * cover));
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inMutable = true;
        if (targetW < sampledW) {
            // The sampled image is still larger than needed (a 5K original): scale the rest of the way while decoding.
            o.inScaled = true;
            o.inDensity = sampledW;
            o.inTargetDensity = targetW;
        }
        o.inBitmap = reuse;
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (IllegalArgumentException tooSmall) {
            // The spare cannot hold this one (a smaller screen before): allocate fresh.
            o.inBitmap = null;
            return BitmapFactory.decodeFile(f.getPath(), o);
        }
    }

    /** The least recently shown backdrops go, until the folder fits in DISK_MAX. */
    private void prune() {
        // The provider matches of earlier days (matches-GB-20261107.json) are never read again.
        String today = today();
        File[] old = dir.listFiles((d, n) -> n.startsWith("matches-") && !n.contains(today));
        //noinspection ResultOfMethodCallIgnored
        if (old != null) for (File f : old) f.delete();
        File[] files = images.listFiles();
        if (files == null) return;
        long total = 0;
        List<File> kept = new ArrayList<>();
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith(SIZE_HD + "-") && !n.startsWith(SIZE_4K + "-") && !n.startsWith(SIZE_LOGO + "-")) {
                // From before the cache was keyed by size: never read again.
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                continue;
            }
            total += f.length();
            kept.add(f);
        }
        if (total <= DISK_MAX) return;
        kept.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : kept) {
            if (total <= DISK_MAX * 3 / 4) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ---- files ---------------------------------------------------------------------------------

    private static JSONObject readJson(File f) {
        try {
            return f.isFile() ? new JSONObject(new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8)) : null;
        } catch (Exception e) {
            Log.w(TAG, "ignoring " + f + ": " + e.getMessage());
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
