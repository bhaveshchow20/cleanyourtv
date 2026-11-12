package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * design_language tvos27: the screensaver as an Apple TV Aerial (layout dream_aerial). Full-screen stills drift in a
 * slow Ken Burns push and dissolve into one another; the time rests at the top right, and each still's name fades in
 * at the bottom left for a few seconds, as the Aerials' location does when the remote is touched.
 *
 * The stills, with no network of its own: the photos sent from a phone when they are the chosen wallpaper, else the
 * TMDB Top Shelf's cached backdrops when a key (or the debug fixture) is set, else the theme's wallpapers with their
 * captions.txt names, else Home's calm dark gradient (TvDesign.backdrop).
 * Shared by ClockDream and ClockActivity, like ClockScene.
 */
final class AerialScene {

    /** How long a still is on screen, and the dissolve into the next. */
    private static final long SLIDE_MS = 30_000L, DISSOLVE_MS = 2_500L;
    /** The caption stays this long after it fades in. */
    private static final long CAPTION_MS = 6_000L;
    /** Every minute the time shifts a few points, so nothing burns in. */
    private static final long DRIFT_MS = 60_000L;

    private static final class Still {
        /** The picture, or null for the plain gradient. */
        final File file;
        final String title;

        Still(File file, String title) {
            this.file = file;
            this.title = title == null ? "" : title;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final ViewGroup root;
    private ImageView front, back;
    private final View clock;
    private final TextView caption;
    private final ClockRefresh clockRefresh;
    private final boolean animations, reduced, trueColor;
    private final List<Still> stills = new ArrayList<>();
    private int index = -1;
    private int generation;
    private boolean running;
    private ObjectAnimator dissolve;
    /** The bitmap of the still that last went off screen, decoded into again for the next one. Main thread only. */
    private Bitmap spare;

    private final Runnable next = this::advance;
    private final Runnable hideCaption = this::hideCaption;
    private final Runnable drift = new Runnable() {
        @Override
        public void run() {
            float range = TvDesign.px(root.getContext(), 10);
            clock.animate().translationX((random.nextFloat() * 2 - 1) * range)
                    .translationY((random.nextFloat() * 2 - 1) * range)
                    .setDuration(animations ? 4_000 : 0).setInterpolator(TvMotion.easeInOut()).start();
            handler.postDelayed(this, DRIFT_MS);
        }
    };

    /** root is the inflated dream_aerial layout. */
    AerialScene(View rootView, Config cfg) {
        root = (ViewGroup) rootView.findViewById(R.id.aerialRoot);
        front = root.findViewById(R.id.aerialFront);
        back = root.findViewById(R.id.aerialBack);
        clock = root.findViewById(R.id.aerialClock);
        caption = root.findViewById(R.id.aerialCaption);
        animations = cfg.animations;
        reduced = cfg.reducedMotion;
        trueColor = cfg.wallpaperTrueColor;
        clockRefresh = new ClockRefresh(clock);
        style(root.getContext());
        gather(root.getContext(), cfg);
    }

    /** tvOS type and the safe area: a Title1 time over a Caption1 date, a Title3 caption. */
    private void style(Context ctx) {
        TextView time = clock.findViewById(R.id.aerialTime);
        TextView date = clock.findViewById(R.id.aerialDate);
        TvDesign.type(time, TvDesign.Type.TITLE1);
        time.setLetterSpacing(-0.01f);
        TvDesign.type(date, TvDesign.Type.CAPTION1);
        date.setAllCaps(true);
        date.setLetterSpacing(0.06f);
        TvDesign.type(caption, TvDesign.Type.TITLE3);
        int top = (int) TvDesign.px(ctx, TvDesign.Layout.SAFE_TOP);
        int side = (int) TvDesign.px(ctx, TvDesign.Layout.SAFE_SIDE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) clock.getLayoutParams();
        lp.setMargins(side, top, side, top);
        FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) caption.getLayoutParams();
        cp.setMargins(side, top, side, (int) TvDesign.px(ctx, TvDesign.Layout.SAFE_BOTTOM + 20));
    }

    void start() {
        if (running) return;
        running = true;
        clockRefresh.start();
        clock.setAlpha(0f);
        clock.animate().alpha(1f).setStartDelay(animations ? 800 : 0)
                .setDuration(TvMotion.ms(TvDesign.Motion.ENTER_MS * 3, reduced)).setInterpolator(TvMotion.easeOut()).start();
        handler.postDelayed(drift, DRIFT_MS);
        if (index < 0) index = firstIndex(root.getContext());
        else index = (index + stills.size() - 1) % stills.size();   // show the same still again after a stop
        if (root.isLaidOut()) advance();
        else root.post(next);   // the push needs the screen's size
    }

    void stop() {
        running = false;
        generation++;   // a decode still running must not attach its picture to a stopped scene
        handler.removeCallbacksAndMessages(null);
        clockRefresh.stop();
        if (dissolve != null) dissolve.cancel();
        front.animate().cancel();
        back.animate().cancel();
        front.setLayerType(View.LAYER_TYPE_NONE, null);
        back.setLayerType(View.LAYER_TYPE_NONE, null);
        clock.animate().cancel();
        caption.animate().cancel();
    }

    /** A press on the remote: the caption comes back, as touching an Apple TV remote shows the Aerial's place. */
    void reveal() {
        if (index >= 0 && index < stills.size()) showCaption(stills.get(index).title);
    }

    // The stills.

    private void gather(Context ctx, Config cfg) {
        // Photos sent from a phone and chosen as the wallpaper are the person's own: they come before the Top Shelf's.
        if (!cfg.wallpapers.isEmpty() && PhoneUpload.isPhone(cfg.wallpapers.get(0))) addWallpapers(cfg);
        if (stills.isEmpty()) addTmdb(ctx, cfg);
        if (stills.isEmpty()) addWallpapers(cfg);
        if (stills.isEmpty()) {
            stills.add(new Still(null, ""));
        }
    }

    /** The Top Shelf's backdrops already on disk, with their titles: Tmdb's lists and the debug fixture. */
    private void addTmdb(Context ctx, Config cfg) {
        File dir = new File(ctx.getCacheDir(), "tmdb");
        File images = new File(dir, "img");
        boolean debug = (ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        File fixture = new File(dir, "fixture.json");
        boolean useFixture = debug && fixture.isFile();
        if (cfg.tmdbKey.isEmpty() && !useFixture) return;
        String size = Tmdb.imageSize(ctx);
        Set<String> seen = new HashSet<>();
        List<Still> found = new ArrayList<>();
        try {
            if (useFixture) {
                JSONObject o = new JSONObject(read(fixture));
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    addTitles(o.optJSONArray(it.next()), images, size, seen, found);
                }
            }
            File[] lists = dir.listFiles((d, name) -> name.startsWith("list-") && name.endsWith(".json"));
            if (lists != null) for (File f : lists) addTitles(new JSONObject(read(f)).optJSONArray("titles"), images, size, seen, found);
        } catch (Exception ignored) {
            // A half-written list: keep what was read so far.
        }
        Collections.shuffle(found, random);
        stills.addAll(found);
    }

    private static void addTitles(JSONArray a, File images, String size, Set<String> seen, List<Still> out) {
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t == null) continue;
            String path = t.optString("backdrop", "");
            // Tmdb's own file for a backdrop path: the size this TV fetches, or else the other one already on disk.
            File f = Tmdb.imageFile(images, size, path);
            if (!f.isFile()) f = Tmdb.imageFile(images, Tmdb.SIZE_4K.equals(size) ? Tmdb.SIZE_HD : Tmdb.SIZE_4K, path);
            if (!path.startsWith("/") || !f.isFile() || f.length() == 0 || !seen.add(f.getName())) continue;
            out.add(new Still(f, t.optString("name", "")));
        }
    }

    private static String read(File f) throws java.io.IOException {
        if (f.length() > 1024 * 1024) throw new java.io.IOException("too big");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** The theme's pictures, named from wallpapers/captions.txt when it has one. */
    private void addWallpapers(Config cfg) {
        if (cfg.wallpapers.isEmpty()) return;
        Map<String, String> names = WallpaperCaptions.read(cfg.wallpapers.get(0).getParentFile());
        for (File f : cfg.wallpapers) stills.add(new Still(f, names.get(f.getName())));
    }

    /** Starts on the picture Home is showing, so the screensaver grows out of it. */
    private int firstIndex(Context ctx) {
        String shown = ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE).getString("image", "");
        for (int i = 0; i < stills.size(); i++) {
            File f = stills.get(i).file;
            if (f != null && f.getName().equals(shown)) return (i + stills.size() - 1) % stills.size();
        }
        return stills.get(0).file == null ? random.nextInt(stills.size()) : stills.size() - 1;
    }

    // The slideshow.

    private void advance() {
        if (!running) return;
        handler.removeCallbacks(next);
        index = (index + 1) % stills.size();
        Still still = stills.get(index);
        if (still.file == null) {
            present(TvDesign.darkBackdrop(), null, still);
            return;
        }
        DisplayMetrics dm = root.getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int gen = ++generation;
        Bitmap reuse = spare;
        spare = null;   // the decoder owns it now
        new Thread(() -> {
            Bitmap bmp = ClockScene.decode(still.file, w, h, trueColor, reuse);
            handler.post(() -> {
                if (gen != generation || !running) {
                    if (bmp != null) spare = bmp;
                    return;
                }
                if (bmp == null) {
                    stills.remove(still);   // unreadable: never tried again
                    if (stills.isEmpty()) stills.add(new Still(null, ""));
                    index = Math.max(-1, index - 1);
                    advance();
                    return;
                }
                present(new BitmapDrawable(root.getResources(), bmp), bmp, still);
            });
        }, "aerial-still").start();
    }

    /** The new still goes on top, pushes slowly in or out, and dissolves over the old one. */
    private void present(Drawable drawable, Bitmap bitmap, Still still) {
        ImageView incoming = back;
        back = front;
        front = incoming;
        root.removeView(incoming);
        root.addView(incoming, 1);   // over the outgoing still, under the scrim and the text
        release(incoming);
        incoming.setImageDrawable(drawable);
        incoming.setTag(bitmap);
        kenBurns(incoming);

        if (dissolve != null) dissolve.cancel();
        incoming.setAlpha(0f);
        long fade = animations ? (reduced ? TvMotion.ms(TvDesign.Motion.ENTER_MS, true) : DISSOLVE_MS) : 0;
        dissolve = ObjectAnimator.ofFloat(incoming, View.ALPHA, 0f, 1f).setDuration(fade);
        // The alpha change composites a cached layer instead of redrawing the picture each frame.
        incoming.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        dissolve.setInterpolator(TvMotion.easeInOut());
        ImageView outgoing = back;
        dissolve.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                incoming.setAlpha(1f);
                incoming.setLayerType(View.LAYER_TYPE_NONE, null);
                if (cancelled) return;
                release(outgoing);
                showCaption(still.title);
            }
        });
        if (caption.getAlpha() > 0f) hideCaption();
        dissolve.start();
        handler.postDelayed(next, SLIDE_MS);
    }

    /**
     * A slow push in or out with a little drift, never showing an edge; still under reduced motion. Scale and
     * translation are RenderNode properties, so the picture is drawn once and only moved by the GPU.
     */
    private void kenBurns(ImageView v) {
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        if (!animations || reduced) {
            v.setScaleX(1f);
            v.setScaleY(1f);
            return;
        }
        boolean in = random.nextBoolean();
        float near = 1.04f, far = 1.14f;
        float from = in ? near : far, to = in ? far : near;
        // The drift stays inside the margin the smaller scale leaves on each side.
        float room = (near - 1f) / 2f;
        double angle = random.nextDouble() * Math.PI * 2;
        float dx = (float) Math.cos(angle) * room * root.getWidth();
        float dy = (float) Math.sin(angle) * room * root.getHeight();
        v.setScaleX(from);
        v.setScaleY(from);
        v.setTranslationX(-dx);
        v.setTranslationY(-dy);
        v.animate().scaleX(to).scaleY(to).translationX(dx).translationY(dy)
                .setDuration(SLIDE_MS + DISSOLVE_MS * 2).setInterpolator(new LinearInterpolator()).start();
    }

    private void release(ImageView v) {
        v.animate().cancel();
        v.setImageDrawable(null);
        Object old = v.getTag();
        v.setTag(null);
        if (old instanceof Bitmap && ((Bitmap) old).isMutable()) {
            if (spare != null) spare.recycle();
            spare = (Bitmap) old;
        } else if (old instanceof Bitmap) {
            ((Bitmap) old).recycle();
        }
    }

    // The caption.

    private void showCaption(String title) {
        handler.removeCallbacks(hideCaption);
        if (title == null || title.isEmpty()) return;
        caption.setText(captionText(title));
        caption.animate().cancel();
        if (caption.getAlpha() == 0f) caption.setTranslationY(TvDesign.px(caption.getContext(), 12));
        caption.animate().alpha(1f).translationY(0f)
                .setDuration(animations ? TvMotion.ms(900, reduced) : 0).setInterpolator(TvMotion.easeOut()).start();
        handler.postDelayed(hideCaption, CAPTION_MS);
    }

    /** "Place · credit" from captions.txt: the place in Title3, the credit under it in a smaller, dimmer Caption1. */
    private CharSequence captionText(String title) {
        int dot = title.indexOf(" · ");
        if (dot <= 0) return title;
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(title.substring(0, dot))
                .append('\n');
        int start = b.length();
        b.append(title.substring(dot + 3).trim());
        int end = b.length();
        b.setSpan(new android.text.style.AbsoluteSizeSpan((int) TvDesign.px(caption.getContext(),
                TvDesign.Type.CAPTION1.size)), start, end, 0);
        b.setSpan(new android.text.style.ForegroundColorSpan(0xB3FFFFFF), start, end, 0);
        return b;
    }

    private void hideCaption() {
        handler.removeCallbacks(hideCaption);
        caption.animate().cancel();
        caption.animate().alpha(0f).setDuration(animations ? TvMotion.ms(700, reduced) : 0)
                .setInterpolator(TvMotion.easeInOut()).start();
    }
}
