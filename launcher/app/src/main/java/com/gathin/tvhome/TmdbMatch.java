package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Matches an app's label ("Hulu", "Peacock", "Prime Video") to TMDB's watch providers by name, so no table of
 * packages is kept (Tmdb). Plain Java, no Android: it can be run against a saved provider list on a computer.
 *
 * Names are normalised (lowercase, "+" and "plus", "tv", "app", punctuation and anything in brackets such as
 * "(demo)" removed), then matched: the same normalised name first, then the label's words all in the provider's
 * name, then the most words in common above a threshold. Resellers ("Amazon Channel", "Apple TV Channel", "Roku
 * Premium Channel") never match. Providers that share the match's base name once tiers are removed ("Peacock
 * Premium" and "Peacock Premium Plus", "Netflix" and "Netflix Standard with Ads") are included with it.
 */
final class TmdbMatch {

    /** A watch provider from /watch/providers/{movie,tv}: its id, name and display priority (lower shows first). */
    static final class Provider {
        final int id;
        final String name;
        final int priority;

        Provider(int id, String name, int priority) {
            this.id = id;
            this.name = name;
            this.priority = priority;
        }
    }

    /**
     * The few labels whose provider differs beyond normalising: the label's normalised key, then the provider's
     * {@link #raw} name. "Apple TV" the app is TMDB's "Apple TV Plus" (its "Apple TV" is the rental store), Prime
     * Video is "Amazon Prime Video", and Max was renamed HBO Max and back.
     */
    private static final String[][] ALIASES = {
            {"apple", "apple tv plus"},
            {"prime video", "amazon prime video"},
            {"max", "hbo max"},
            {"hbo max", "max"},
    };

    /** Labels that are never a streaming service on TMDB, though a provider shares their name (YouTube, the store). */
    private static final Set<String> NEVER = new HashSet<>(Arrays.asList(
            "youtube", "youtube kids", "google play store", "play store", "google play movies", "google play",
            "settings", "live channels", "inputs"));

    /** Words that only name a tier of the same service, removed to find a match's base name. */
    private static final Set<String> TIERS = new HashSet<>(Arrays.asList(
            "with", "ads", "standard", "basic", "premium", "essential", "ad", "free"));

    private static final float MIN_OVERLAP = 0.6f;

    private TmdbMatch() {
    }

    /** "Disney+ (demo)" to "disney", "Tubi TV" to "tubi", "Paramount Plus" to "paramount". */
    static String normalise(String s) {
        if (s == null) return "";
        String n = s.toLowerCase(Locale.ROOT)
                .replaceAll("\\(.*?\\)|\\[.*?]", " ")
                .replace("+", " plus ")
                .replace('&', ' ')
                .replaceAll("[^\\p{L}\\p{Nd} ]", " ");
        StringBuilder b = new StringBuilder();
        for (String w : n.trim().split("\\s+")) {
            if (w.isEmpty() || w.equals("plus") || w.equals("tv") || w.equals("app")) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w);
        }
        return b.toString();
    }

    /** Lowercase words with "+" spelt "plus" and nothing removed: "Apple TV+" is "apple tv plus". */
    static String raw(String s) {
        return s.toLowerCase(Locale.ROOT).replace("+", " plus ").replaceAll("[^\\p{L}\\p{Nd} ]", " ")
                .trim().replaceAll("\\s+", " ");
    }

    private static boolean reseller(String providerName) {
        return providerName.toLowerCase(Locale.ROOT).contains("channel");
    }

    private static List<String> words(String normalised) {
        return normalised.isEmpty() ? Collections.emptyList() : Arrays.asList(normalised.split(" "));
    }

    /** The base name once tier words are removed: "peacock premium" and "peacock" are both "peacock". */
    private static String base(String normalised) {
        StringBuilder b = new StringBuilder();
        for (String w : words(normalised)) {
            if (TIERS.contains(w)) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w);
        }
        return b.toString();
    }

    /**
     * The provider ids for an app labelled {@code label}, best first ("8|1796"), or null when it is not a service
     * TMDB knows: then the shelf keeps its gradient.
     */
    static String match(String label, List<Provider> catalogue) {
        String key = normalise(label);
        if (key.isEmpty() || NEVER.contains(key) || catalogue == null || catalogue.isEmpty()) return null;
        List<String> aliases = new ArrayList<>();
        for (String[] a : ALIASES) if (a[0].equals(key)) aliases.add(a[1]);

        Provider best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Provider p : catalogue) {
            if (reseller(p.name)) continue;
            String n = normalise(p.name);
            if (n.isEmpty()) continue;
            int rank = aliases.contains(raw(p.name)) ? 0 : rank(key, n);
            if (rank == Integer.MAX_VALUE) continue;
            // Aliases rank first among equals; then the provider TMDB shows first in the region.
            if (best == null || rank < bestRank || rank == bestRank && p.priority < best.priority) {
                best = p;
                bestRank = rank;
            }
        }
        if (best == null) return null;
        String bestBase = base(normalise(best.name));
        int bestWords = raw(best.name).split(" ").length;
        StringBuilder ids = new StringBuilder().append(best.id);
        Set<Integer> seen = new HashSet<>();
        seen.add(best.id);
        for (Provider p : catalogue) {
            if (seen.contains(p.id) || reseller(p.name)) continue;
            // A tier of the same service: never a shorter name, which is another product ("Apple TV", the store).
            if (!bestBase.isEmpty() && bestBase.equals(base(normalise(p.name)))
                    && raw(p.name).split(" ").length >= bestWords) {
                ids.append('|').append(p.id);
                seen.add(p.id);
            }
        }
        return ids.toString();
    }

    /**
     * Lower is better (an alias's provider is 0): the same normalised name (1), every label word in the provider's
     * name (10 + the extra words), enough words in common (100 + the words not shared); MAX_VALUE for no match.
     */
    private static int rank(String key, String provider) {
        if (key.equals(provider)) return 1;
        List<String> kw = words(key), pw = words(provider);
        if (pw.containsAll(kw)) return 10 + pw.size() - kw.size();
        int common = 0;
        for (String w : kw) if (pw.contains(w)) common++;
        Set<String> union = new HashSet<>(kw);
        union.addAll(pw);
        if (common > 0 && (float) common / union.size() >= MIN_OVERLAP) return 100 + union.size() - common;
        return Integer.MAX_VALUE;
    }
}
