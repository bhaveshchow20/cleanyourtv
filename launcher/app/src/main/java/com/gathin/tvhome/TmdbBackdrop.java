package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.List;

/**
 * design_language tvos27's Top Shelf with a TMDB key: when focus rests on a streaming app in the top row for a few
 * seconds, its gradient gives way to full-screen backdrops of today's top titles on that service, cross-fading slowly
 * from one to the next, with no title over them. Moving focus drops back to the gradient at once. An app TMDB knows
 * nothing about, or any failure, keeps the gradient.
 *
 * Kept light for 1 GB TVs: at most two full-screen layers draw at any time (the gradient under the first backdrop,
 * or the old backdrop under the next), the gradient stops drawing once a backdrop covers it, the old bitmap is let
 * go as soon as a fade ends, and nothing animates between fades, so a resting shelf draws no frames.
 */
final class TmdbBackdrop {
    /** A title stays SHOW_MS from the start of its fade in to the start of the next one's. */
    private static final long DWELL_MS = 3000, SHOW_MS = 6000;
    private static final int FADE_IN_MS = 1600, CROSS_MS = 1800, FADE_OUT_MS = TvDesign.Motion.SHELF_FADE_MS;
    private static final float SETTLE = 1.04f;

    private final Tmdb tmdb;
    private final View hero;
    /** The shelf's gradient layers (heroArt and the copy under it), hidden while a backdrop covers them. */
    private final View[] gradient;
    private final ImageView back, top;
    /** The title's logo at the bottom left, above the dock; null leaves logos out. */
    private final ImageView logo;
    private final boolean animations;
    /** Full-screen backdrop: rotation slows to FULL_SHOW_MS so the picture can be looked at. */
    private boolean full;
    private static final long FULL_SHOW_MS = 12000;
    private static final int LOGO_OUT_MS = 280;

    private String pkg;
    private List<Tmdb.Title> titles;
    /** The app focused before the provider list was in, matched when it is; null once focus has moved on. */
    private String waiting;
    private int next, misses, generation;
    /** A backdrop is fully in and the gradient under it no longer draws. */
    private boolean covering;
    private boolean shown;
    /** Off the top row: bitmaps that leave are let go, not kept for the next decode. */
    private boolean idle;

    TmdbBackdrop(Context ctx, Tmdb tmdb, ViewGroup root, int index, View hero, ImageView logo, boolean animations,
                 View... gradient) {
        this.tmdb = tmdb;
        this.logo = logo;
        this.hero = hero;
        this.gradient = gradient;
        this.animations = animations;
        back = layer(ctx, root, index);
        top = layer(ctx, root, index + 1);
    }

    private static ImageView layer(Context ctx, ViewGroup root, int index) {
        ImageView v = new ImageView(ctx);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        v.setVisibility(View.GONE);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.forceHasOverlappingRendering(false);
        root.addView(v, index, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    /** True while this app's backdrop covers the shelf, so the gradient should stay hidden under it. */
    boolean covers(String pkg) {
        return covering && pkg != null && pkg.equals(this.pkg);
    }

    /** Some backdrop is drawn over the shelf gradient right now, settled or mid-fade. */
    boolean onTop() {
        return top.getVisibility() == View.VISIBLE && top.getAlpha() > 0f;
    }

    /** A backdrop is on screen (fading in or resting), so the dock can give way to it (full screen). */
    boolean showing() {
        return shown && top.getVisibility() == View.VISIBLE;
    }

    /** Called when a backdrop starts to take over the shelf (the first of a run). */
    Runnable onShown;

    /** Full screen on or off: the rotation slows while the backdrop is all there is to look at. */
    void setFull(boolean on) {
        if (full == on) return;
        full = on;
        if (rotatePending) {
            hero.removeCallbacks(rotate);
            hero.postDelayed(rotate, on ? FULL_SHOW_MS : SHOW_MS);
        }
    }

    /** Focus is on this top-row app: after a dwell its titles take over the backdrop. The same app keeps them going. */
    void focus(String pkg) {
        if (pkg != null && pkg.equals(this.pkg)) return;
        waiting = null;
        if (pkg != null && !tmdb.ready()) {
            // Today's provider list is still coming (Home just started): match this app once it is in.
            waiting = pkg;
            tmdb.whenReady(() -> {
                String p = waiting;
                waiting = null;
                if (p != null) focus(p);
            });
        }
        // An app with no titles needs no spare bitmap: let the old one go.
        idle = !tmdb.covers(pkg);
        hide(true, FADE_OUT_MS);
        if (idle) {
            tmdb.dropSpare();
            return;
        }
        this.pkg = pkg;
        int gen = generation;
        hero.postDelayed(dwell, DWELL_MS);
        // Fetch the list during the dwell, so the first backdrop is usually on disk by the time it is due.
        tmdb.titles(pkg, list -> {
            if (gen != generation) return;
            titles = list;
            if (!list.isEmpty()) tmdb.prefetch(list.get(0));
        });
    }

    /** Focus left the top row: the backdrop fades over ms, and the dwell and rotation stop until focus is back. */
    void leave(int ms) {
        idle = true;
        waiting = null;
        hide(true, ms);
        tmdb.dropSpare();
    }

    /** Home is going away: everything off at once. */
    void pause() {
        idle = true;
        waiting = null;
        hide(false, 0);
        tmdb.dropSpare();
    }

    /** Memory is short with Home in the background: let the bitmaps go. */
    void release() {
        hide(false, 0);
        tmdb.trimMemory();
    }

    private final Runnable dwell = this::onDwell;

    private void onDwell() {
        int gen = generation;
        if (titles != null) {
            showNext();
            return;
        }
        tmdb.titles(pkg, list -> {
            if (gen != generation) return;
            titles = list;
            showNext();
        });
    }

    private boolean rotatePending;
    private final Runnable rotate = () -> {
        rotatePending = false;
        showNext();
    };

    private void showNext() {
        if (titles == null || titles.isEmpty() || misses >= titles.size()) return;
        Tmdb.Title t = titles.get(next % titles.size());
        next++;
        int gen = generation;
        // The layer's own pixels: 1920x1080 where the TV draws its UI at 1080p, even on a 4K panel (the panel
        // upscales that surface, so more pixels would never reach it), and 3840x2160 where it draws at 4K.
        android.util.DisplayMetrics dm = hero.getResources().getDisplayMetrics();
        int w = top.getWidth() > 0 ? top.getWidth() : dm.widthPixels;
        int h = top.getHeight() > 0 ? top.getHeight() : dm.heightPixels;
        tmdb.backdrop(t, w, h, (bmp, logoBmp) -> {
            if (gen != generation) return;
            if (bmp == null) {
                misses++;
                // Offline or blocked: try the next one, and give up after a full lap.
                if (misses < titles.size()) hero.postDelayed(rotate, 500);
                return;
            }
            misses = 0;
            int fade = shown ? CROSS_MS : FADE_IN_MS;
            crossFade(bmp);
            showLogo(logoBmp, fade);
            if (titles.size() > 1) {
                tmdb.prefetch(titles.get(next % titles.size()));
                rotatePending = true;
                hero.postDelayed(rotate, full ? FULL_SHOW_MS : SHOW_MS);
            }
        });
    }

    /**
     * The first backdrop fades in over the gradient, which then stops drawing; each next one fades in over a copy of
     * the last on the back layer, which is emptied when the fade ends. A small settle zoom rides on the fade only.
     */
    private void crossFade(Bitmap bmp) {
        back.animate().cancel();
        top.animate().cancel();
        if (shown) {
            back.setImageDrawable(top.getDrawable());
            back.setAlpha(1f);
            back.setVisibility(View.VISIBLE);
        }
        top.setImageBitmap(bmp);
        top.setVisibility(View.VISIBLE);
        int ms = shown ? CROSS_MS : FADE_IN_MS;
        if (!shown && onShown != null) onShown.run();
        shown = true;
        if (!animations) {
            top.setAlpha(1f);
            settled();
            return;
        }
        int gen = generation;
        top.setAlpha(0f);
        top.setScaleX(SETTLE);
        top.setScaleY(SETTLE);
        top.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ms).setInterpolator(TvMotion.easeInOut())
                .withEndAction(() -> {
                    if (gen == generation) settled();
                }).start();
    }

    /** The new backdrop is fully in: only it draws now. */
    private void settled() {
        back.setVisibility(View.GONE);
        drop(back);
        for (View g : gradient) {
            g.animate().cancel();
            g.setAlpha(0f);
        }
        covering = true;
    }

    /**
     * Off the backdrop: timers, queued downloads and the back layer stop at once; the top layer fades over ms (the
     * gradient, or Home's own background in the grid, is already under it) and lets its bitmap go.
     */
    private void hide(boolean animate, int ms) {
        generation++;
        hero.removeCallbacks(dwell);
        hero.removeCallbacks(rotate);
        rotatePending = false;
        tmdb.cancelPending();
        pkg = null;
        titles = null;
        next = 0;
        misses = 0;
        shown = false;
        boolean wasCovering = covering;
        covering = false;
        showLogo(null, animate && animations ? Math.min(ms, LOGO_OUT_MS) : 0);
        back.animate().cancel();
        back.setVisibility(View.GONE);
        // The back layer shows the top's bitmap during a cross-fade; only let it go once neither draws it.
        if (back.getDrawable() != top.getDrawable()) drop(back);
        else back.setImageDrawable(null);
        top.animate().cancel();
        if (top.getVisibility() != View.VISIBLE || top.getAlpha() == 0f || !animate || !animations) {
            top.setAlpha(0f);
            top.setVisibility(View.GONE);
            drop(top);
            // Home going away mid-backdrop: put the gradient back so it is there on return.
            if (wasCovering && !animate) gradient[0].setAlpha(1f);
            return;
        }
        int gen = generation;
        top.animate().alpha(0f).setDuration(ms).setInterpolator(TvMotion.easeInOut()).withEndAction(() -> {
            if (gen != generation) return;
            top.setVisibility(View.GONE);
            drop(top);
        }).start();
    }

    /**
     * The logo follows its backdrop: the old one fades out quickly, then the new one fades in over the rest of the
     * backdrop's fade. No logo (or none found) leaves the corner empty: there is no text stand-in.
     */
    private void showLogo(Bitmap b, int ms) {
        if (logo == null) return;
        logo.animate().cancel();
        int gen = generation;
        if (!animations || ms == 0) {
            logo.setImageBitmap(b);
            logo.setAlpha(b == null ? 0f : 1f);
            logo.setVisibility(b == null ? View.GONE : View.VISIBLE);
            return;
        }
        Runnable in = () -> {
            logo.setImageBitmap(b);
            if (b == null) {
                logo.setVisibility(View.GONE);
                return;
            }
            logo.setVisibility(View.VISIBLE);
            logo.setAlpha(0f);
            logo.setTranslationY(TvDesign.px(logo.getContext(), 8));
            logo.animate().alpha(1f).translationY(0f).setStartDelay(ms / 4).setDuration(ms * 3 / 4)
                    .setInterpolator(TvMotion.easeOut()).start();
        };
        if (logo.getVisibility() == View.VISIBLE && logo.getAlpha() > 0f) {
            logo.animate().alpha(0f).setStartDelay(0).setDuration(Math.min(LOGO_OUT_MS, ms))
                    .setInterpolator(TvMotion.easeInOut()).withEndAction(() -> {
                        if (gen == generation || b == null) in.run();
                    }).start();
        } else {
            in.run();
        }
    }

    /** Empties a layer and hands its bitmap to Tmdb, which decodes the next backdrop into it. */
    private void drop(ImageView layer) {
        Drawable d = layer.getDrawable();
        layer.setImageDrawable(null);
        if (!idle && d instanceof BitmapDrawable && d != top.getDrawable() && d != back.getDrawable()) {
            tmdb.giveBack(((BitmapDrawable) d).getBitmap());
        }
    }
}
