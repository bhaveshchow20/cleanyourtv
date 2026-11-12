package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Launcher settings in the tvOS 27 Settings layout (design_language tvos27): the screen's title centred at the top,
 * a large illustration on the left with the focused row's description under it, and the list on the right. A row
 * shows its value in grey and a chevron when it leads to another level; the focused row fills white with dark text.
 * A choice opens its own level, which slides in from the right while the one before slides away; Back slides it out
 * again and returns focus to the row it came from. Confirmations are tvOS alerts: a dimmed screen with stacked glass
 * buttons. Every change goes through {@link LauncherSettingsActivity}, so both looks save exactly the same choices.
 */
final class TvosSettings {

    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private static final int[] CLOCK_LABELS = {R.string.clock_format_system, R.string.clock_format_12,
            R.string.clock_format_24};
    private static final String[] APPEARANCES = {TvDesign.DARK, TvDesign.LIGHT, TvDesign.AUTO};
    private static final int[] APPEARANCE_LABELS = {R.string.tvos_appearance_dark, R.string.tvos_appearance_light,
            R.string.tvos_appearance_auto};

    /** List metrics in points, from tvOS Settings at 1920x1080. */
    private static final float ROW_H = 78, ROW_RADIUS = 20, ROW_PAD = 34, ROW_GAP = 6;
    private static final float LIST_START = 980, LIST_TOP = 190, ART = 420;
    /** A focused row grows just enough to lift off the list, far less than a card does. */
    private static final float ROW_SCALE = 1.035f;
    /** How far a level slides on a push or pop. */
    private static final float PUSH = 120;

    private final LauncherSettingsActivity a;
    private final Config cfg;
    private final boolean motion, reduced;
    private final FrameLayout root;
    private final TextView title;
    private final ImageView art;
    private final TextView description;
    private final FrameLayout listHost;
    private final List<Page> stack = new ArrayList<>();
    private FrameLayout alert;
    /** Runs once the open alert or sheet has gone, however it was closed. */
    private Runnable alertGone;

    TvosSettings(LauncherSettingsActivity activity, Config config) {
        a = activity;
        cfg = config;
        motion = config.animations;
        reduced = config.reducedMotion;
        root = new FrameLayout(a);
        // The gradient is the window's own background, so no full-screen layer is drawn over it.
        a.getWindow().setBackgroundDrawable(background());
        if (a.getResources().getBoolean(R.bool.rtl_menu_bar)) root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        title = new TextView(a);
        TvDesign.type(title, TvDesign.Type.TITLE3);
        title.setTextColor(TvDesign.Color.LABEL);
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tp.topMargin = px(TvDesign.Layout.SAFE_TOP + 20);
        root.addView(title, tp);

        // The left half: the level's illustration, centred, and the focused row's description under it.
        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        art = new ImageView(a);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(TvDesign.Radius.PANEL));
            }
        });
        art.setClipToOutline(true);
        art.setElevation(px(30));
        left.addView(art, new LinearLayout.LayoutParams(px(ART), px(ART)));
        description = new TextView(a);
        TvDesign.type(description, TvDesign.Type.CAPTION1);
        description.setTextColor(TvDesign.Color.SECONDARY);
        description.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(px(640), -2);
        dp.topMargin = px(48);
        left.addView(description, dp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(LIST_START - TvDesign.Layout.SAFE_SIDE - 40),
                -2, Gravity.START | Gravity.CENTER_VERTICAL);
        lp.setMarginStart(px(TvDesign.Layout.SAFE_SIDE));
        lp.topMargin = px(70);
        root.addView(left, lp);

        listHost = new FrameLayout(a);
        listHost.setClipChildren(false);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -1);
        hp.setMarginStart(px(LIST_START));
        hp.setMarginEnd(px(TvDesign.Layout.SAFE_SIDE));
        hp.topMargin = px(LIST_TOP);
        root.addView(listHost, hp);
    }

    View root() {
        return root;
    }

    /** An intent extra naming the level to reopen after a recreate: an appearance change rebuilds this screen. */
    static final String EXTRA_PAGE = "tvos_page", PAGE_APPEARANCE = "appearance";

    void start() {
        Page root = rootPage();
        if (!PAGE_APPEARANCE.equals(a.getIntent().getStringExtra(EXTRA_PAGE))) {
            push(root, false);
        } else {
            // Rebuilt for a new appearance: the root level stays unbuilt underneath, with Back returning to its row.
            a.getIntent().removeExtra(EXTRA_PAGE);
            for (int i = 0; i < root.rows.size(); i++) {
                if (root.rows.get(i).label.equals(a.getString(R.string.tvos_appearance))) root.focused = i;
            }
            stack.add(root);
            Page page = appearancePage();
            for (int i = 0; i < page.rows.size(); i++) {
                if (page.rows.get(i).label.equals(a.getString(APPEARANCE_LABELS[appearanceIndex()]))) page.focused = i;
            }
            stack.add(page);
            show(page, null, true);
        }
    }

    /** Back: closes an alert, or pops one level. False at the top level, so the screen closes. */
    boolean back() {
        if (alert != null) {
            dismissAlert();
            return true;
        }
        if (stack.size() <= 1) return false;
        Page leaving = stack.remove(stack.size() - 1);
        Page showing = stack.get(stack.size() - 1);
        show(showing, leaving, false);
        return true;
    }

    /** A choice was saved: every visible row re-reads its value. */
    void refresh() {
        if (stack.isEmpty()) return;
        Page p = stack.get(stack.size() - 1);
        for (Row r : p.rows) r.bind();
    }

    // ---- Levels ------------------------------------------------------------------------------------------

    private Page rootPage() {
        Page p = new Page(a.getString(R.string.launcher_settings), Glyph.GEAR);
        p.header(R.string.tvos_section_home);
        if (cfg.launcherSettingsButton) {
            p.row(a.getString(R.string.reorder), null, Accessory.NONE).action(a::reorder).glyph(Glyph.GRID);
        }
        p.row(a.getString(R.string.columns_label), () -> String.valueOf(a.columns()), Accessory.CHEVRON)
                .glyph(Glyph.GRID).action(() -> push(numbers(R.string.columns_label, Glyph.GRID, 4, 7,
                        a::columns, a::setColumns, ""), true));
        p.row(a.getString(R.string.rows_below_dock_label), () -> String.valueOf(a.rowsBelowDock()), Accessory.CHEVRON)
                .glyph(Glyph.ROWS).action(() -> push(numbers(R.string.rows_below_dock_label, Glyph.ROWS, 0, 3,
                        a::rowsBelowDock, a::setRowsBelowDock, ""), true));
        p.row(a.getString(R.string.dock_opacity_label), () -> a.dockOpacity() + "%", Accessory.CHEVRON)
                .glyph(Glyph.DOCK).action(() -> push(dockPage(), true));

        p.header(R.string.tvos_section_appearance);
        p.row(a.getString(R.string.tvos_appearance), () -> a.getString(APPEARANCE_LABELS[appearanceIndex()]),
                Accessory.CHEVRON).glyph(Glyph.APPEARANCE).describe(a.getString(R.string.tvos_appearance_help))
                .action(() -> push(appearancePage(), true));
        p.row(a.getString(R.string.wallpaper_label), this::wallpaperLabel, Accessory.CHEVRON)
                .glyph(Glyph.PICTURE).action(() -> push(wallpaperPage(), true));
        p.row(a.getString(R.string.dream_name), () -> a.getString(CLOCK_LABELS[clockIndex()]), Accessory.CHEVRON)
                .glyph(Glyph.CLOCK).action(() -> push(clockPage(), true));
        p.row(a.getString(R.string.tvos_animations), () -> onOff(a.animations()), Accessory.NONE)
                .glyph(Glyph.SPARKLE).action(a::toggleAnimations);
        p.row(a.getString(R.string.advanced_effects), () -> onOff(a.advancedEffects()), Accessory.NONE)
                .glyph(Glyph.SPARKLE).enabled(a::animations)
                .describe(a.getString(R.string.advanced_effects_warning))
                .action(() -> {
                    if (a.advancedEffects()) a.setAdvanced(false);
                    else showAlert(a.getString(R.string.advanced_effects),
                            a.getString(R.string.advanced_effects_warning),
                            a.getString(R.string.enable), () -> a.setAdvanced(true));
                });

        p.header(R.string.tvos_section_general);
        if (cfg.openOnStartChoice) {
            p.row(a.getString(R.string.open_on_start_label), a::openOnStartLabel, Accessory.CHEVRON)
                    .glyph(Glyph.PLAY).action(() -> push(openOnStartPage(), true));
        }
        p.row(a.getString(R.string.navigation_sounds), () -> onOff(a.navigationSounds()), Accessory.NONE)
                .glyph(Glyph.SPARKLE).describe(a.getString(R.string.navigation_sounds_help))
                .action(a::toggleNavigationSounds);
        p.row(a.getString(R.string.follow_theme), null, Accessory.CHECK)
                .checked(() -> !a.hasOnTvChoices()).glyph(Glyph.GEAR)
                .describe(a.getString(R.string.follow_theme_hint)).action(a::followTheme);
        p.row(a.getString(R.string.home_settings), null, Accessory.CHEVRON).glyph(Glyph.HOME)
                .describe(a.getString(R.string.home_settings_help)).action(a::openHomeSettings);
        p.row(a.getString(R.string.tv_settings), null, Accessory.CHEVRON).glyph(Glyph.TV)
                .action(a::openTvSettings);
        return p;
    }

    private interface IntSetter {
        void set(int v);
    }

    private interface IntGetter {
        int get();
    }

    private Page numbers(int titleRes, Glyph glyph, int from, int to, IntGetter get, IntSetter set, String suffix) {
        Page p = new Page(a.getString(titleRes), glyph);
        p.choice = true;
        for (int v = from; v <= to; v++) {
            final int value = v;
            p.row(value + suffix, null, Accessory.CHECK).checked(() -> get.get() == value)
                    .action(() -> set.set(value));
        }
        return p;
    }

    private Page dockPage() {
        Page p = new Page(a.getString(R.string.dock_opacity_label), Glyph.DOCK);
        p.choice = true;
        for (int v : DOCK_OPACITIES) {
            p.row(v + "%", null, Accessory.CHECK).checked(() -> a.dockOpacity() == v)
                    .action(() -> a.setDockOpacity(v));
        }
        return p;
    }

    private int appearanceIndex() {
        for (int i = 0; i < APPEARANCES.length; i++) if (APPEARANCES[i].equals(a.appearance())) return i;
        return 0;
    }

    /** Dark, Light and Automatic, as Apple TV's Settings > General > Appearance. */
    private Page appearancePage() {
        Page p = new Page(a.getString(R.string.tvos_appearance), Glyph.APPEARANCE);
        p.choice = true;
        for (int i = 0; i < APPEARANCES.length; i++) {
            String v = APPEARANCES[i];
            Row row = p.row(a.getString(APPEARANCE_LABELS[i]), null, Accessory.CHECK)
                    .checked(() -> v.equals(a.appearance())).action(() -> a.setAppearance(v));
            if (TvDesign.AUTO.equals(v)) row.describe(a.getString(R.string.tvos_appearance_auto_help));
        }
        return p;
    }

    private Page clockPage() {
        Page p = new Page(a.getString(R.string.dream_name), Glyph.CLOCK);
        p.choice = true;
        for (int i = 0; i < CLOCK_FORMATS.length; i++) {
            String v = CLOCK_FORMATS[i];
            p.row(a.getString(CLOCK_LABELS[i]), null, Accessory.CHECK).checked(() -> v.equals(a.clockFormat()))
                    .action(() -> a.setClockFormat(v));
        }
        return p;
    }

    /**
     * Theme default, every picture (the left side previews the focused one), the photos sent from a phone as one
     * choice, then None; under them, sending photos from a phone and removing them.
     */
    private Page wallpaperPage() {
        Page p = new Page(a.getString(R.string.wallpaper_label), Glyph.PICTURE);
        p.choice = true;
        Supplier<String> current = () -> a.choices().getString(Config.WALLPAPER_CHOICE_KEY, null);
        p.row(a.getString(R.string.wallpaper_theme_default), null, Accessory.CHECK)
                .checked(() -> current.get() == null).action(() -> a.chooseWallpaper(null));
        for (File f : a.wallpapers()) {
            if (PhoneUpload.isPhone(f)) continue;
            String name = f.getName();
            p.row(name, null, Accessory.CHECK).checked(() -> name.equals(current.get())).preview(f)
                    .action(() -> a.chooseWallpaper(name));
        }
        List<File> phone = PhoneUpload.photos(a);
        if (!phone.isEmpty()) {
            String count = a.getResources().getQuantityString(R.plurals.phone_photo_count, phone.size(), phone.size());
            p.row(a.getString(R.string.wallpaper_phone_photos), () -> count, Accessory.CHECK)
                    .checked(() -> WallpaperRotation.CHOICE_PHONE.equals(current.get())).preview(phone.get(0))
                    .action(() -> a.chooseWallpaper(WallpaperRotation.CHOICE_PHONE));
        }
        p.row(a.getString(R.string.wallpaper_none), null, Accessory.CHECK)
                .checked(() -> "none".equals(current.get())).action(() -> a.chooseWallpaper("none"));

        p.header(R.string.wallpaper_from_phone_section);
        p.row(a.getString(R.string.wallpaper_choose_phone), null, Accessory.NONE)
                .describe(a.getString(R.string.wallpaper_choose_phone_help)).action(this::showPhoneSheet);
        if (!phone.isEmpty()) {
            p.row(a.getString(R.string.wallpaper_remove_phone), null, Accessory.NONE)
                    .action(() -> showAlert(a.getString(R.string.wallpaper_remove_phone),
                            a.getString(R.string.wallpaper_remove_phone_message), a.getString(R.string.remove), () -> {
                                PhoneUpload.clear(a);
                                if (WallpaperRotation.CHOICE_PHONE.equals(current.get())) a.chooseWallpaper(null);
                                reloadWallpaperPage(R.string.wallpaper_choose_phone);
                            }));
        }
        return p;
    }

    /** The Wallpaper level again, after phone photos came or went, with focus on the row labelled {@code focus}. */
    private void reloadWallpaperPage(int focus) {
        if (stack.isEmpty()) return;
        Page old = stack.get(stack.size() - 1);
        if (!old.title.equals(a.getString(R.string.wallpaper_label))) return;
        Page fresh = wallpaperPage();
        String label = a.getString(focus);
        for (int i = 0; i < fresh.rows.size(); i++) if (fresh.rows.get(i).label.equals(label)) fresh.focused = i;
        stack.set(stack.size() - 1, fresh);
        listHost.removeView(old.view);
        show(fresh, null, true);
    }

    private Page openOnStartPage() {
        Page p = new Page(a.getString(R.string.open_on_start_label), Glyph.PLAY);
        p.choice = true;
        Supplier<String> current = () -> a.choices().getString(OpenOnStart.KEY, "");
        p.row(a.getString(R.string.off), null, Accessory.CHECK).checked(() -> current.get().isEmpty())
                .action(() -> a.setOpenOnStart(""));
        for (String[] app : a.tvApps()) {
            p.row(app[0], null, Accessory.CHECK).checked(() -> app[1].equals(current.get()))
                    .action(() -> a.setOpenOnStart(app[1]));
        }
        return p;
    }

    private String wallpaperLabel() {
        String w = a.choices().getString(Config.WALLPAPER_CHOICE_KEY, null);
        if (WallpaperRotation.CHOICE_PHONE.equals(w)) return a.getString(R.string.wallpaper_phone_photos);
        return "none".equals(w) ? a.getString(R.string.wallpaper_none)
                : w != null ? w : a.getString(R.string.wallpaper_theme_default);
    }

    private int clockIndex() {
        for (int i = 0; i < CLOCK_FORMATS.length; i++) if (CLOCK_FORMATS[i].equals(a.clockFormat())) return i;
        return 0;
    }

    private String onOff(boolean on) {
        return a.getString(on ? R.string.on : R.string.off);
    }

    // ---- Push and pop ------------------------------------------------------------------------------------

    private void push(Page page, boolean animate) {
        Page from = stack.isEmpty() ? null : stack.get(stack.size() - 1);
        if (from != null) from.focused = from.focusedIndex();
        stack.add(page);
        show(page, animate ? from : null, true);
    }

    /** Builds {@code page} and slides it in: from the right on a push, from the left on a pop. */
    private void show(Page page, Page leaving, boolean forward) {
        View old = leaving == null ? null : leaving.view;
        ScrollView list = page.build();
        listHost.addView(list, new FrameLayout.LayoutParams(-1, -1));
        crossfade(title, () -> title.setText(page.title));
        setArt(page.glyph, null);
        boolean animate = old != null && motion;
        float d = px(PUSH) * (forward ? 1 : -1);
        if (old != null) {
            if (animate) {
                old.animate().cancel();
                old.animate().translationX(reduced ? 0 : -d).alpha(0f)
                        .setDuration(TvMotion.ms(TvDesign.Motion.EXIT_MS, reduced))
                        .setInterpolator(TvMotion.easeInOut())
                        .withEndAction(() -> listHost.removeView(old)).start();
            } else {
                listHost.removeView(old);
            }
        }
        if (animate) {
            list.setAlpha(0f);
            list.setTranslationX(reduced ? 0 : d);
            list.animate().translationX(0f).alpha(1f)
                    .setStartDelay(TvMotion.ms(60, reduced))
                    .setDuration(TvMotion.ms(TvDesign.Motion.ENTER_MS, reduced))
                    .setInterpolator(TvMotion.easeOut()).start();
        }
        page.refocus();
    }

    private void crossfade(View v, Runnable change) {
        if (!motion || v.getAlpha() == 0f) {
            change.run();
            v.setAlpha(1f);
            return;
        }
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(TvMotion.ms(110, reduced)).setInterpolator(TvMotion.easeInOut())
                .withEndAction(() -> {
                    change.run();
                    v.animate().alpha(1f).setDuration(TvMotion.ms(200, reduced))
                            .setInterpolator(TvMotion.easeOut()).start();
                }).start();
    }

    private Glyph artGlyph;
    private File artFile;
    private Bitmap artBitmap;

    private final java.util.concurrent.ExecutorService decoder =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.Map<Glyph, Drawable> tiles = new java.util.EnumMap<>(Glyph.class);

    /**
     * The left illustration: a wallpaper's picture when a preview is focused, the level's glyph otherwise. A picture
     * is decoded small on a background thread; if focus has moved on by then, it is dropped.
     */
    private void setArt(Glyph glyph, File preview) {
        if (glyph == artGlyph && (preview == null ? artFile == null : preview.equals(artFile))) return;
        artGlyph = glyph;
        artFile = preview;
        Drawable tile = tiles.get(glyph);
        if (tile == null) tiles.put(glyph, tile = new GlyphTile(glyph));
        Drawable fallback = tile;
        if (preview == null) {
            crossfade(art, () -> showArt(null, fallback));
            return;
        }
        int size = px(ART);
        decoder.execute(() -> {
            Bitmap b = decode(preview, size);
            art.post(() -> {
                if (!preview.equals(artFile) || a.isDestroyed()) {
                    if (b != null) b.recycle();
                    return;
                }
                crossfade(art, () -> showArt(b, fallback));
            });
        });
    }

    private void showArt(Bitmap b, Drawable fallback) {
        Bitmap old = artBitmap;
        artBitmap = b;
        if (b != null) art.setImageBitmap(b);
        else art.setImageDrawable(fallback);
        if (old != null && old != b) old.recycle();
    }

    /** The screen left the front: an open phone sheet closes, and its server with it. */
    void pause() {
        if (alertGone != null) dismissAlert();
    }

    /** The screen is going: stops the decoder and frees the preview. */
    void destroy() {
        decoder.shutdownNow();
        art.setImageDrawable(null);
        if (artBitmap != null) artBitmap.recycle();
        artBitmap = null;
    }

    private static Drawable background() {
        return TvDesign.backdrop();
    }

    /** A wallpaper scaled down to about {@code size} pixels high, never decoded at full size on a 1 GB TV. */
    private static Bitmap decode(File f, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outHeight / (sample * 2) >= size) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    // ---- Alert -------------------------------------------------------------------------------------------

    /** A tvOS alert: the screen dims, the title and message sit in the middle, and the buttons stack under them. */
    private void showAlert(String heading, String message, String confirm, Runnable onConfirm) {
        if (alert != null) return;
        View before = root.findFocus();
        alert = new FrameLayout(a);
        alert.setBackgroundColor(TvDesign.scrim(0xE6));
        alert.setElevation(px(200));
        alert.setClickable(true);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView h = new TextView(a);
        TvDesign.type(h, TvDesign.Type.TITLE3);
        h.setTextColor(TvDesign.Color.LABEL);
        h.setGravity(Gravity.CENTER);
        h.setText(heading);
        box.addView(h, new LinearLayout.LayoutParams(-2, -2));
        TextView m = new TextView(a);
        TvDesign.type(m, TvDesign.Type.CALLOUT);
        m.setTextColor(TvDesign.Color.SECONDARY);
        m.setGravity(Gravity.CENTER);
        m.setText(message);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(px(900), -2);
        mp.topMargin = px(24);
        mp.bottomMargin = px(56);
        box.addView(m, mp);
        Runnable[] actions = {() -> {
            dismissAlert();
            onConfirm.run();
        }, this::dismissAlert};
        String[] labels = {confirm, a.getString(android.R.string.cancel)};
        View first = null;
        for (int i = 0; i < labels.length; i++) {
            TextView b = new TextView(a);
            TvDesign.type(b, TvDesign.Type.CALLOUT);
            b.setText(labels[i]);
            b.setGravity(Gravity.CENTER);
            b.setFocusable(true);
            b.setClickable(true);
            b.setBackground(LiquidGlass.control(a));
            b.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_focused}, {}},
                    new int[]{TvDesign.Glass.FOCUSED_TEXT, TvDesign.Color.LABEL}));
            Runnable run = actions[i];
            b.setOnClickListener(v -> run.run());
            b.setOnFocusChangeListener((v, f) -> lift(v, f, 1.06f));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(px(560), px(TvDesign.Radius.BUTTON_H + 12));
            bp.topMargin = px(i == 0 ? 0 : 22);
            box.addView(b, bp);
            if (first == null) first = b;
        }
        alert.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        alert.setTag(before);
        root.addView(alert, new FrameLayout.LayoutParams(-1, -1));
        listHost.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        first.requestFocus();
        TvMotion.enter(box, motion, reduced);
        if (motion) {
            alert.setAlpha(0f);
            alert.animate().alpha(1f).setDuration(TvMotion.ms(TvDesign.Motion.ENTER_MS, reduced))
                    .setInterpolator(TvMotion.easeOut()).start();
        }
    }

    private void dismissAlert() {
        FrameLayout gone = alert;
        if (gone == null) return;
        alert = null;
        Runnable after = alertGone;
        alertGone = null;
        listHost.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View before = (View) gone.getTag();
        if (before != null) before.requestFocus();
        TvMotion.exit(gone, motion, reduced, () -> root.removeView(gone));
        if (after != null) after.run();
    }

    // ---- Choose from phone -------------------------------------------------------------------------------

    /**
     * The phone-upload sheet: a glass platter with the QR code on the left, and on the right what to do, the address
     * as text, and how many photos have arrived. PhoneUpload's server runs exactly as long as the sheet is up.
     * Closing it with photos received makes them the wallpaper.
     */
    private void showPhoneSheet() {
        if (alert != null) return;
        int[] received = {0};
        TextView status = new TextView(a);
        PhoneUpload upload = new PhoneUpload(a, new PhoneUpload.Listener() {
            @Override
            public void received(int count) {
                received[0] = count;
                status.setText(a.getResources().getQuantityString(R.plurals.phone_photos_received, count, count));
            }

            @Override
            public void done(int count) {
                received[0] = count;
                status.setText(count > 0 ? a.getResources().getQuantityString(R.plurals.phone_sheet_done, count, count)
                        : a.getString(R.string.phone_sheet_nothing));
            }

            @Override
            public void expired() {
                dismissAlert();
            }
        });
        String url = upload.start();

        View before = root.findFocus();
        alert = new FrameLayout(a);
        alert.setBackgroundColor(TvDesign.scrim(0xE6));
        alert.setElevation(px(200));
        alert.setClickable(true);
        LinearLayout sheet = new LinearLayout(a);
        sheet.setOrientation(LinearLayout.HORIZONTAL);
        sheet.setGravity(Gravity.CENTER_VERTICAL);
        sheet.setBackground(LiquidGlass.platter(a));
        int pad = px(56);
        sheet.setPadding(pad, pad, pad, pad);
        // The Done capsule's shadow reaches past its bounds: nothing between it and the platter may clip it.
        sheet.setClipChildren(false);
        sheet.setClipToPadding(false);

        int side = px(420);
        if (url != null) {
            ImageView code = new ImageView(a);
            code.setImageBitmap(qr(url, side));
            float r = px(TvDesign.Radius.CARD);
            code.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                }
            });
            code.setClipToOutline(true);
            code.setBackgroundColor(0xFFFFFFFF);
            code.setContentDescription(url);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(side, side);
            cp.setMarginEnd(px(56));
            sheet.addView(code, cp);
        }

        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setClipChildren(false);
        words.setClipToPadding(false);
        TextView h = new TextView(a);
        TvDesign.type(h, TvDesign.Type.TITLE3);
        h.setTextColor(TvDesign.Color.LABEL);
        h.setText(R.string.wallpaper_choose_phone_title);
        words.addView(h, new LinearLayout.LayoutParams(-2, -2));
        TextView steps = new TextView(a);
        TvDesign.type(steps, TvDesign.Type.CALLOUT);
        steps.setTextColor(TvDesign.Color.SECONDARY);
        steps.setText(url != null ? R.string.phone_sheet_steps : R.string.phone_sheet_offline);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(px(760), -2);
        sp.topMargin = px(20);
        words.addView(steps, sp);
        if (url != null) {
            TextView address = new TextView(a);
            TvDesign.type(address, TvDesign.Type.BODY);
            address.setTextColor(TvDesign.Color.LABEL);
            address.setText(url);
            address.setTextIsSelectable(false);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(px(760), -2);
            ap.topMargin = px(28);
            words.addView(address, ap);
            TvDesign.type(status, TvDesign.Type.HEADLINE);
            status.setTextColor(TvDesign.Color.LABEL);
            status.setText(R.string.phone_sheet_waiting);
            LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(px(760), -2);
            stp.topMargin = px(36);
            words.addView(status, stp);
        }
        TextView done = new TextView(a);
        TvDesign.type(done, TvDesign.Type.CALLOUT);
        done.setText(R.string.done);
        done.setGravity(Gravity.CENTER);
        done.setFocusable(true);
        done.setClickable(true);
        done.setBackground(LiquidGlass.control(a));
        done.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        done.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{TvDesign.Glass.FOCUSED_TEXT, TvDesign.Color.LABEL}));
        done.setOnClickListener(v -> dismissAlert());
        // Its only button: focus shows as the white fill, so it never grows past the sheet's edge.
        done.setOnFocusChangeListener((v, f) -> lift(v, f, 1f));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(px(360), px(TvDesign.Radius.BUTTON_H + 12));
        dp.topMargin = px(44);
        words.addView(done, dp);
        sheet.addView(words, new LinearLayout.LayoutParams(-2, -2));

        alert.addView(sheet, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        alert.setTag(before);
        alertGone = () -> {
            upload.stop();
            if (received[0] > 0) {
                a.chooseWallpaper(WallpaperRotation.CHOICE_PHONE);
                reloadWallpaperPage(R.string.wallpaper_phone_photos);
            }
        };
        root.addView(alert, new FrameLayout.LayoutParams(-1, -1));
        listHost.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        done.requestFocus();
        TvMotion.enter(sheet, motion, reduced);
        if (motion) {
            alert.setAlpha(0f);
            alert.animate().alpha(1f).setDuration(TvMotion.ms(TvDesign.Motion.ENTER_MS, reduced))
                    .setInterpolator(TvMotion.easeOut()).start();
        }
    }

    /** The QR code for {@code text}, whole pixels per module with a four-module quiet zone, about {@code side} wide. */
    private static Bitmap qr(String text, int side) {
        boolean[][] m = Qr.encode(text);
        if (m == null) return null;
        int n = m.length + 8, scale = Math.max(1, side / n), size = n * scale;
        int[] pixels = new int[size * size];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        for (int y = 0; y < m.length; y++) {
            for (int x = 0; x < m.length; x++) {
                if (!m[y][x]) continue;
                for (int dy = 0; dy < scale; dy++) {
                    java.util.Arrays.fill(pixels, ((y + 4) * scale + dy) * size + (x + 4) * scale,
                            ((y + 4) * scale + dy) * size + (x + 5) * scale, 0xFF000000);
                }
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565);
    }


    // ---- Rows --------------------------------------------------------------------------------------------

    /** A focused row (or alert button) rises a little on its shadow. */
    private void lift(View v, boolean focused, float scale) {
        float s = focused ? scale : 1f;
        float z = focused ? px(18) : 0f;
        v.animate().cancel();
        if (!motion) {
            v.setScaleX(s);
            v.setScaleY(s);
            v.setTranslationZ(z);
            return;
        }
        v.animate().scaleX(s).scaleY(s).translationZ(z)
                .setDuration(TvMotion.ms(focused ? TvDesign.Motion.FOCUS_MS : TvDesign.Motion.UNFOCUS_MS, reduced))
                .setInterpolator(TvMotion.easeOut()).start();
    }

    private enum Accessory {NONE, CHEVRON, CHECK}

    private final class Page {
        final String title;
        final Glyph glyph;
        final List<Object> items = new ArrayList<>();
        final List<Row> rows = new ArrayList<>();
        ScrollView view;
        int focused = -1;
        /** A list of options to pick one from: it opens on the checked one. */
        boolean choice;

        Page(String title, Glyph glyph) {
            this.title = title;
            this.glyph = glyph;
        }

        void header(int res) {
            items.add(a.getString(res));
        }

        Row row(String label, Supplier<String> value, Accessory accessory) {
            Row r = new Row(this, label, value, accessory);
            items.add(r);
            rows.add(r);
            return r;
        }

        ScrollView build() {
            ScrollView sv = new FocusScroll(a);
            sv.setVerticalScrollBarEnabled(false);
            sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            sv.setClipChildren(false);
            sv.setClipToPadding(false);
            sv.setPadding(0, px(14), 0, px(TvDesign.Layout.SAFE_BOTTOM + 40));
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setClipChildren(false);
            col.setClipToPadding(false);
            col.setPadding(px(12), 0, px(12), 0);
            boolean first = true;
            for (Object item : items) {
                if (item instanceof String) {
                    TextView h = new TextView(a);
                    TvDesign.type(h, TvDesign.Type.CAPTION1);
                    h.setTextColor(TvDesign.Color.SECONDARY);
                    h.setText((String) item);
                    h.setPaddingRelative(px(ROW_PAD), 0, px(ROW_PAD), 0);
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
                    p.topMargin = px(first ? 0 : 36);
                    p.bottomMargin = px(12);
                    col.addView(h, p);
                } else {
                    Row r = (Row) item;
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, px(ROW_H));
                    p.topMargin = px(first ? 0 : ROW_GAP);
                    col.addView(r.build(), p);
                    r.bind();
                }
                first = false;
            }
            sv.addView(col, new ScrollView.LayoutParams(-1, -2));
            view = sv;
            return sv;
        }

        int focusedIndex() {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).view.hasFocus()) return i;
            return focused;
        }

        /** The row this level was left from, else its checked choice, else its first row. */
        void refocus() {
            Row target = null;
            if (focused >= 0 && focused < rows.size()) target = rows.get(focused);
            if (choice && (target == null || !target.view.isFocusable())) {
                for (Row r : rows) if (r.accessory == Accessory.CHECK && r.isChecked() && r.view.isFocusable()) {
                    target = r;
                    break;
                }
            }
            if (target == null || !target.view.isFocusable()) {
                for (Row r : rows) if (r.view.isFocusable()) {
                    target = r;
                    break;
                }
            }
            if (target != null) {
                target.view.requestFocus();
            }
        }
    }

    private final class Row {
        final Page page;
        final String label;
        final Supplier<String> value;
        final Accessory accessory;
        BooleanSupplier checked, enabled;
        Runnable action;
        Glyph glyph;
        File preview;
        String help;
        LinearLayout view;
        TextView labelView, valueView, accessoryView;

        Row(Page page, String label, Supplier<String> value, Accessory accessory) {
            this.page = page;
            this.label = label;
            this.value = value;
            this.accessory = accessory;
        }

        Row action(Runnable r) { action = r; return this; }
        Row checked(BooleanSupplier c) { checked = c; return this; }
        Row enabled(BooleanSupplier e) { enabled = e; return this; }
        Row glyph(Glyph g) { glyph = g; return this; }
        Row preview(File f) { preview = f; return this; }
        Row describe(String d) { help = d; return this; }

        boolean isChecked() {
            return checked != null && checked.getAsBoolean();
        }

        View build() {
            view = new LinearLayout(a);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPaddingRelative(px(ROW_PAD), 0, px(ROW_PAD), 0);
            view.setFocusable(true);
            view.setClickable(true);
            view.setBackground(rowBackground());
            view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            labelView = text(TvDesign.Type.BODY, TvDesign.Color.LABEL);
            labelView.setText(label);
            labelView.setSingleLine(true);
            labelView.setEllipsize(TextUtils.TruncateAt.END);
            view.addView(labelView, new LinearLayout.LayoutParams(0, -2, 1f));
            valueView = text(TvDesign.Type.BODY, TvDesign.Color.SECONDARY);
            valueView.setSingleLine(true);
            valueView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            valueView.setMaxWidth(px(380));
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
            vp.setMarginStart(px(24));
            view.addView(valueView, vp);
            accessoryView = text(TvDesign.Type.BODY, TvDesign.Color.SECONDARY);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(px(30), -2);
            ap.setMarginStart(px(18));
            accessoryView.setGravity(Gravity.CENTER);
            view.addView(accessoryView, ap);
            view.setOnClickListener(v -> {
                if (action != null && view.isEnabled()) {
                    press(v);
                    action.run();
                }
            });
            view.setOnFocusChangeListener((v, f) -> {
                lift(v, f, ROW_SCALE);
                tint(f);
                if (f) {
                    page.focused = page.rows.indexOf(this);
                    description.setText(help == null ? "" : help);
                    setArt(glyph != null ? glyph : page.glyph, preview);
                }
            });
            view.setOnKeyListener((v, code, e) -> code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_LEFT);
            return view;
        }

        private TextView text(TvDesign.Type style, int color) {
            TextView t = new TextView(a);
            TvDesign.type(t, style);
            t.setTextColor(color);
            t.setIncludeFontPadding(false);
            return t;
        }

        void bind() {
            if (view == null) return;
            boolean on = enabled == null || enabled.getAsBoolean();
            view.setEnabled(on);
            view.setFocusable(on);
            view.setAlpha(on ? 1f : 0.4f);
            valueView.setText(value == null ? "" : value.get());
            valueView.setVisibility(value == null ? View.GONE : View.VISIBLE);
            switch (accessory) {
                case CHEVRON: accessoryView.setText("\u203A"); break;
                case CHECK: accessoryView.setText(isChecked() ? "\u2713" : ""); break;
                default: accessoryView.setText("");
            }
            if (accessory == Accessory.CHEVRON) {
                accessoryView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(44));
            }
            tint(view.hasFocus());
        }

        /** White platter under focus: the label turns near-black and the value a dark grey. */
        void tint(boolean focused) {
            labelView.setTextColor(focused ? TvDesign.Glass.FOCUSED_TEXT : TvDesign.Color.LABEL);
            int secondary = focused ? TvDesign.Glass.FOCUSED_SECONDARY : TvDesign.Color.SECONDARY;
            valueView.setTextColor(secondary);
            accessoryView.setTextColor(accessory == Accessory.CHECK
                    ? (focused ? TvDesign.Glass.FOCUSED_TEXT : TvDesign.Color.LABEL) : secondary);
        }

        private Drawable rowBackground() {
            float r = px(ROW_RADIUS);
            GradientDrawable focused = new GradientDrawable();
            focused.setColor(TvDesign.Glass.FOCUSED_FILL);
            focused.setCornerRadius(r);
            GradientDrawable rest = new GradientDrawable();
            rest.setColor(TvDesign.Color.FILL);
            rest.setCornerRadius(r);
            StateListDrawable s = new StateListDrawable();
            s.addState(new int[]{android.R.attr.state_focused}, focused);
            s.addState(new int[]{}, rest);
            return s;
        }

        /** Select presses the row down, then it springs back to its focused size. */
        private void press(View v) {
            if (!motion) return;
            v.animate().cancel();
            v.animate().scaleX(1f).scaleY(1f).setDuration(TvMotion.ms(TvDesign.Motion.PRESS_MS, reduced))
                    .setInterpolator(TvMotion.easeInOut())
                    .withEndAction(() -> lift(v, v.hasFocus(), ROW_SCALE)).start();
        }
    }

    /**
     * Scrolls the way a tvOS list does: the focused row stays a little above the middle, so the rows before and
     * after it are always in view, and the list rests at its ends.
     */
    private static final class FocusScroll extends ScrollView {
        FocusScroll(Context ctx) {
            super(ctx);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            if (getChildCount() == 0) return 0;
            int max = Math.max(0, getChildAt(0).getHeight() + getPaddingTop() + getPaddingBottom() - getHeight());
            int target = Math.round(r.exactCenterY() - getHeight() * 0.42f);
            return Math.max(0, Math.min(max, target)) - getScrollY();
        }
    }

    // ---- Illustrations -----------------------------------------------------------------------------------

    enum Glyph {GEAR, GRID, ROWS, DOCK, PICTURE, CLOCK, SPARKLE, PLAY, HOME, TV, APPEARANCE}

    /** A large silver tile with a dark glyph, the way tvOS Settings shows its own icon beside the list. */
    static Drawable tile(Glyph glyph) {
        return new GlyphTile(glyph);
    }

    private static final class GlyphTile extends Drawable {
        private final Glyph glyph;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        GlyphTile(Glyph glyph) {
            this.glyph = glyph;
            ink.setColor(0xFF3A3A3F);
            line.setColor(0xFF3A3A3F);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onBoundsChange(@NonNull Rect b) {
            bg.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0xFFF2F2F7, 0xFF9C9CA3, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(@NonNull Canvas c) {
            Rect b = getBounds();
            float s = b.width();
            c.drawRect(b, bg);
            float cx = b.exactCenterX(), cy = b.exactCenterY(), u = s / 100f;
            line.setStrokeWidth(5 * u);
            switch (glyph) {
                case GEAR: {
                    path.reset();
                    int teeth = 10;
                    float ro = 34 * u, ri = 27 * u;
                    for (int i = 0; i < teeth * 2; i++) {
                        double a0 = Math.PI * 2 * i / (teeth * 2), a1 = Math.PI * 2 * (i + 1) / (teeth * 2);
                        float rad = i % 2 == 0 ? ro : ri;
                        float x0 = cx + (float) Math.cos(a0) * rad, y0 = cy + (float) Math.sin(a0) * rad;
                        float x1 = cx + (float) Math.cos(a1) * rad, y1 = cy + (float) Math.sin(a1) * rad;
                        if (i == 0) path.moveTo(x0, y0);
                        else path.lineTo(x0, y0);
                        path.lineTo(x1, y1);
                    }
                    path.close();
                    path.addCircle(cx, cy, 13 * u, Path.Direction.CW);
                    path.setFillType(Path.FillType.EVEN_ODD);
                    c.drawPath(path, ink);
                    break;
                }
                case GRID:
                    for (int y = 0; y < 3; y++)
                        for (int x = 0; x < 3; x++) {
                            r.set(cx - 33 * u + x * 23 * u, cy - 33 * u + y * 23 * u,
                                    cx - 33 * u + x * 23 * u + 20 * u, cy - 33 * u + y * 23 * u + 20 * u);
                            c.drawRoundRect(r, 5 * u, 5 * u, ink);
                        }
                    break;
                case ROWS:
                    for (int y = 0; y < 3; y++) {
                        r.set(cx - 34 * u, cy - 30 * u + y * 22 * u, cx + 34 * u, cy - 30 * u + y * 22 * u + 16 * u);
                        ink.setAlpha(y == 0 ? 255 : 150);
                        c.drawRoundRect(r, 5 * u, 5 * u, ink);
                    }
                    ink.setAlpha(255);
                    break;
                case DOCK:
                    r.set(cx - 36 * u, cy - 26 * u, cx + 36 * u, cy + 26 * u);
                    c.drawRoundRect(r, 7 * u, 7 * u, line);
                    r.set(cx - 30 * u, cy + 4 * u, cx + 30 * u, cy + 18 * u);
                    ink.setAlpha(170);
                    c.drawRoundRect(r, 4 * u, 4 * u, ink);
                    ink.setAlpha(255);
                    break;
                case PICTURE:
                    r.set(cx - 36 * u, cy - 28 * u, cx + 36 * u, cy + 28 * u);
                    c.drawRoundRect(r, 7 * u, 7 * u, line);
                    c.drawCircle(cx + 16 * u, cy - 10 * u, 7 * u, ink);
                    path.reset();
                    path.moveTo(cx - 30 * u, cy + 22 * u);
                    path.lineTo(cx - 10 * u, cy - 4 * u);
                    path.lineTo(cx + 4 * u, cy + 12 * u);
                    path.lineTo(cx + 12 * u, cy + 4 * u);
                    path.lineTo(cx + 30 * u, cy + 22 * u);
                    path.close();
                    c.drawPath(path, ink);
                    break;
                case CLOCK:
                    c.drawCircle(cx, cy, 32 * u, line);
                    c.drawLine(cx, cy, cx, cy - 20 * u, line);
                    c.drawLine(cx, cy, cx + 14 * u, cy + 8 * u, line);
                    break;
                case SPARKLE:
                    star(c, cx - 6 * u, cy + 4 * u, 26 * u);
                    star(c, cx + 22 * u, cy - 20 * u, 12 * u);
                    break;
                case PLAY:
                    c.drawCircle(cx, cy, 32 * u, line);
                    path.reset();
                    path.moveTo(cx - 9 * u, cy - 15 * u);
                    path.lineTo(cx + 15 * u, cy);
                    path.lineTo(cx - 9 * u, cy + 15 * u);
                    path.close();
                    c.drawPath(path, ink);
                    break;
                case HOME:
                    path.reset();
                    path.moveTo(cx - 32 * u, cy - 2 * u);
                    path.lineTo(cx, cy - 30 * u);
                    path.lineTo(cx + 32 * u, cy - 2 * u);
                    c.drawPath(path, line);
                    r.set(cx - 22 * u, cy - 8 * u, cx + 22 * u, cy + 28 * u);
                    c.drawRoundRect(r, 4 * u, 4 * u, line);
                    break;
                case TV:
                    r.set(cx - 38 * u, cy - 26 * u, cx + 38 * u, cy + 18 * u);
                    c.drawRoundRect(r, 6 * u, 6 * u, line);
                    c.drawLine(cx - 16 * u, cy + 30 * u, cx + 16 * u, cy + 30 * u, line);
                    break;
                case APPEARANCE:
                    // SF Symbols' circle.lefthalf.filled: Light and Dark side by side.
                    c.drawCircle(cx, cy, 32 * u, line);
                    r.set(cx - 32 * u, cy - 32 * u, cx + 32 * u, cy + 32 * u);
                    c.drawArc(r, 90, 180, true, ink);
                    break;
            }
        }

        private void star(Canvas c, float x, float y, float rr) {
            path.reset();
            path.moveTo(x, y - rr);
            path.quadTo(x, y, x + rr, y);
            path.quadTo(x, y, x, y + rr);
            path.quadTo(x, y, x - rr, y);
            path.quadTo(x, y, x, y - rr);
            path.close();
            c.drawPath(path, ink);
        }

        @Override
        public void setAlpha(int alpha) {
            bg.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            ink.setColorFilter(filter);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    private int px(float pt) {
        return Math.round(TvDesign.px(a, pt));
    }

    /**
     * The backdrop {@link HomeSettingsActivity} keeps behind the system Home picker, in the same look: the
     * Settings background, its title and the house tile on the left (the help is a toast over the picker).
     */
    static View homeBackdrop(Context ctx) {
        FrameLayout root = new FrameLayout(ctx);
        root.setBackground(background());
        TextView t = new TextView(ctx);
        TvDesign.type(t, TvDesign.Type.TITLE3);
        t.setTextColor(TvDesign.Color.LABEL);
        t.setGravity(Gravity.CENTER);
        t.setText(R.string.home_settings);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tp.topMargin = Math.round(TvDesign.px(ctx, TvDesign.Layout.SAFE_TOP + 20));
        root.addView(t, tp);
        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView art = new ImageView(ctx);
        art.setImageDrawable(tile(Glyph.HOME));
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), TvDesign.px(ctx, TvDesign.Radius.PANEL));
            }
        });
        art.setClipToOutline(true);
        int size = Math.round(TvDesign.px(ctx, ART));
        left.addView(art, new LinearLayout.LayoutParams(size, size));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.round(TvDesign.px(ctx, LIST_START - TvDesign.Layout.SAFE_SIDE - 40)), -2,
                Gravity.START | Gravity.CENTER_VERTICAL);
        lp.setMarginStart(Math.round(TvDesign.px(ctx, TvDesign.Layout.SAFE_SIDE)));
        lp.topMargin = Math.round(TvDesign.px(ctx, 70));
        root.addView(left, lp);
        return root;
    }
}
