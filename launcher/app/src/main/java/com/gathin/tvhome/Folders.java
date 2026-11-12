package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * design_language tvos27: Home's app folders, as tvOS groups apps ("Kids", "Music"). Each folder has an id, a name
 * and its apps' tile keys (component strings, the keys Order saves). A folder's own tile key is
 * "&lt;launcher package&gt;/folder:&lt;id&gt;", so it takes its place in the saved Order like any app. Kept in the
 * launcher's "tvos27" preferences as one small JSON array.
 */
final class Folders {
    static final String CLASS_PREFIX = "folder:";
    private static final String PREFS = "tvos27", KEY = "folders";

    static final class Folder {
        final String id;
        String name;
        final List<String> apps = new ArrayList<>();

        Folder(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private Folders() { }

    static List<Folder> load(Context ctx) {
        return parse(prefs(ctx).getString(KEY, "[]"));
    }

    /**
     * The folders in saved JSON. Unreadable JSON gives none, so every app is back on Home; a folder without an id,
     * an empty app key, an app listed twice, or an app already in an earlier folder is dropped, so a tile never
     * shows in two places.
     */
    static List<Folder> parse(String json) {
        List<Folder> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        try {
            JSONArray a = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || o.optString("id").isEmpty() || find(out, o.optString("id")) != null) continue;
                Folder f = new Folder(o.optString("id"), o.optString("name"));
                JSONArray apps = o.optJSONArray("apps");
                for (int k = 0; apps != null && k < apps.length(); k++) {
                    String app = apps.optString(k, "").trim();
                    if (!app.isEmpty() && idOf(app) == null && seen.add(app)) f.apps.add(app);
                }
                if (!f.apps.isEmpty()) out.add(f);
            }
        } catch (Exception ignored) {
            // Unreadable: no folders, every app back on Home.
        }
        return out;
    }

    /** Saves the folders; an empty one is dropped. */
    static void save(Context ctx, List<Folder> folders) {
        prefs(ctx).edit().putString(KEY, toJson(folders)).apply();
    }

    static String toJson(List<Folder> folders) {
        JSONArray a = new JSONArray();
        for (Folder f : folders) {
            if (f.apps.isEmpty()) continue;
            try {
                a.put(new JSONObject().put("id", f.id).put("name", f.name).put("apps", new JSONArray(f.apps)));
            } catch (org.json.JSONException ignored) {
            }
        }
        return a.toString();
    }

    /**
     * Puts the carried app's tile on the target's: a new folder of the two (id {@code newId}) in the target's place
     * when the target is an app, or the carried app added to the target folder. Null, and nothing changed, when the
     * carried tile is a folder (folders don't nest), is the target itself, or the target folder is gone.
     */
    static Folder join(List<Folder> folders, String carried, String target, String newId) {
        if (carried == null || target == null || carried.equals(target) || idOf(carried) != null) return null;
        String targetId = idOf(target);
        Folder f;
        if (targetId == null) {
            f = new Folder(newId, "");
            f.apps.add(target);
            folders.add(f);
        } else {
            f = find(folders, targetId);
            if (f == null) return null;
        }
        if (!f.apps.contains(carried)) f.apps.add(carried);
        return f;
    }

    /** Takes an app out of its folder; true when that was the last one and the folder is gone with it. */
    static boolean removeApp(List<Folder> folders, String id, String appKey) {
        Folder f = find(folders, id);
        if (f == null) return false;
        f.apps.remove(appKey);
        if (!f.apps.isEmpty()) return false;
        folders.remove(f);
        return true;
    }

    /** Renames a folder; a blank name goes back to the default ("Folder"). False when there is no such folder. */
    static boolean rename(List<Folder> folders, String id, String name) {
        Folder f = find(folders, id);
        if (f == null) return false;
        f.name = name == null ? "" : name.trim();
        return true;
    }

    static Folder find(List<Folder> folders, String id) {
        for (Folder f : folders) if (f.id.equals(id)) return f;
        return null;
    }

    /** The folder holding this app's tile key, or null. */
    static Folder holding(List<Folder> folders, String appKey) {
        for (Folder f : folders) if (f.apps.contains(appKey)) return f;
        return null;
    }

    /** The folder id in a tile key, or null when the key is an app's. */
    static String idOf(String key) {
        int at = key == null ? -1 : key.indexOf("/" + CLASS_PREFIX);
        return at < 0 ? null : key.substring(at + 1 + CLASS_PREFIX.length());
    }

    static String key(Context ctx, String id) {
        return ctx.getPackageName() + "/" + CLASS_PREFIX + id;
    }

    static String newId() {
        return Long.toString(System.currentTimeMillis(), 36);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
