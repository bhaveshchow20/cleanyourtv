package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/** On-demand installed-app picker. Uses the launcher's glass resources; no backdrop capture or blur. */
final class AppLibraryDialog {
    /**
     * The catalogue, the labels and the icons load here, off the UI thread: each label and icon opens the app's
     * resources, and on a TCL G09 that froze Home for 60-140 ms (2 to 4 janky frames) every time the library opened.
     */
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** Labels kept between openings (LOADER thread only), until the app is updated. */
    private static final Map<String, Known> KNOWN = new HashMap<>();

    private static final class Known {
        final long updated;
        final String label;

        Known(long updated, String label) {
            this.updated = updated;
            this.label = label;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Icons loaded for this opening, dropped with it. */
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Set<String> iconsLoading = new HashSet<>();
    private final Runnable dismissed;
    private boolean closed;
    /** Only the latest load fills the catalogue, and it shows the popup if an opening is waiting. */
    private final LibraryLoads loads = new LibraryLoads();
    private final Activity activity;
    private final Predicate<String> onHome;
    private final Runnable changed;
    private final LinearLayout panel;
    private final boolean reducedMotion;
    private final boolean selectionMode;
    /** library_ok_opens without app_library_selection: OK opens every app, Add to Home is in the hold menu. */
    private final boolean okOpens;
    private final boolean animations;
    private final boolean rtl;
    private TextView instructions;
    private int totalApps;
    final PopupWindow popup;
    private final float density;
    private List<ResolveInfo> apps = new ArrayList<>();
    private String selectedPackage;
    private int selectedPosition;
    private int selectedOffset;
    private final Map<String, Long> installTimes = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private long catalogTime;
    private PopupWindow actionPopup;
    private RecyclerView catalog;
    private FrameLayout shelf;
    private GlassView highlight;
    private SpringAnimation spring;
    private float scrollOffset;
    private boolean highlightShown;
    private final int catalogWidth;
    /** design_language tvos27: a full-screen grid over the focused app's colours, see {@link #showTvosCatalog}. */
    private final boolean tvos;
    private final TvFocus.Spec spec;
    private final Set<String> banners = new HashSet<>();
    private final Map<String, int[]> palettes = new HashMap<>();
    private Drawable backdrop;
    private String backdropKey;
    private Runnable backdropUpdate;
    private int columns;

    private void stopHighlight() {
        if (spring != null) spring.cancel();
        if (catalog != null) catalog.stopScroll();
        catalog = null;
        highlightShown = false;
    }

    private void highlight(View card, boolean animate) {
        if (highlight == null || card.getParent() == null) return;
        Rect rect = new Rect(0, 0, card.getWidth(), card.getHeight());
        shelf.offsetDescendantRectToMyCoords(card, rect);
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        lp.width = rect.width() + dp(12);
        lp.height = rect.height() + dp(12);
        highlight.setLayoutParams(lp);
        highlight.setTranslationY(rect.top - dp(6));
        float x = rect.left - dp(6) + scrollOffset;
        if (!highlightShown || !animate || !animations) {
            spring.cancel();
            highlight.setTranslationX(x - scrollOffset);
        } else spring.animateToFinalPosition(x);
        highlightShown = true;
        highlight.setAlpha(1f);
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), 0x18FFFFFF);
        return bg;
    }

    private void resize(int width, int height) {
        popup.setWidth(width);
        popup.setHeight(height);
        if (popup.isShowing()) popup.update(width, height);
    }

    /** library_excluded_apps (config.xml), never offered in the catalogue; empty by default, so existing
     *  setups are unaffected. See LibraryApps.excludedFromCatalog(). */
    private final Set<String> excludedApps;

    AppLibraryDialog(Activity activity, boolean animations, boolean reducedMotion, boolean selectionMode, boolean okOpens, Predicate<String> onHome, Runnable changed, Runnable dismissed, Set<String> excludedApps) {
        this.activity = activity;
        this.animations = animations;
        this.rtl = activity.getResources().getBoolean(R.bool.rtl_app_grid);
        this.reducedMotion = reducedMotion;
        this.selectionMode = selectionMode;
        this.okOpens = okOpens && !selectionMode;
        this.onHome = onHome;
        this.changed = changed;
        this.dismissed = dismissed;
        this.excludedApps = excludedApps;
        density = activity.getResources().getDisplayMetrics().density;
        panel = new LinearLayout(activity);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(12));
        panel.setBackgroundResource(R.drawable.glass_panel);
        View decor = activity.getWindow().getDecorView();
        catalogWidth = Math.min(dp(800), Math.max(dp(200), decor.getWidth() - dp(64)));
        int width = catalogWidth;
        int height = dp(280);
        popup = new PopupWindow(panel, width, height, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        Config cfg = Config.load(activity);
        tvos = cfg.tvos27();
        spec = TvFocus.Spec.of(cfg);
        if (tvos) {
            columns = Math.max(4, Math.min(8, cfg.columns));
            panel.setPadding(0, 0, 0, 0);
            panel.setBackground(TvDesign.backdrop());
            popup.setWidth(ViewGroup.LayoutParams.MATCH_PARENT);
            popup.setHeight(ViewGroup.LayoutParams.MATCH_PARENT);
            if (animations && !reducedMotion) popup.setAnimationStyle(R.style.TvosLibraryWindow);
            else if (animations) popup.setAnimationStyle(android.R.style.Animation_Dialog);
        }
        popup.setOnDismissListener(() -> {
            closed = true;
            if (backdropUpdate != null) main.removeCallbacks(backdropUpdate);
            dismissActions();
            stopHighlight();
            icons.clear();
            dismissed.run();
        });
    }

    void close() {
        dismissActions();
        if (popup.isShowing()) {
            popup.dismiss();
        } else if (!closed) {
            closed = true;   // still loading: it never shows
            dismissed.run();
        }
    }

    void show() {
        load(true);
    }

    private void reveal() {
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        if (tvos) {
            NavSound.watch(panel);
            return;
        }
        View container = panel.getRootView();
        if (container.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            params.dimAmount = 0.55f;
            activity.getWindowManager().updateViewLayout(container, params);
        }
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(reducedMotion ? Motion.REDUCED_MS : 180).start();
    }

    /** Called by the activity's existing package receiver; an uninstall cannot leave a stale action. */
    /** Packages with a leanback entry, from the last refresh. */
    private final java.util.Set<String> tvPackages = new java.util.HashSet<>();

    void refresh() {
        load(false);
    }

    /**
     * Reads the catalogue on LOADER, then fills it on the UI thread. reveal: from opening the library; the popup
     * appears once the latest load has filled it, even when a refresh replaced the opening's own load.
     */
    private void load(boolean reveal) {
        dismissActions();
        int load = loads.start(reveal);
        PackageManager pm = activity.getPackageManager();
        String self = activity.getPackageName();
        LOADER.execute(() -> {
            Map<String, ResolveInfo> unique = new LinkedHashMap<>();
            Set<String> tv = new HashSet<>();
            // Prefer the TV entry when an app declares both categories.
            for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                    if (info.activityInfo == null || !info.activityInfo.exported) continue;
                    String pkg = info.activityInfo.packageName;
                    if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) tv.add(pkg);
                    if (!pkg.equals(self) && !LibraryApps.excludedFromCatalog(excludedApps, pkg) && !unique.containsKey(pkg)) unique.put(pkg, info);
                }
            }
            Map<String, String> names = new HashMap<>();
            Map<String, Long> installed = new HashMap<>();
            for (Map.Entry<String, ResolveInfo> entry : unique.entrySet()) {
                String pkg = entry.getKey();
                long updated = 0;
                try {
                    PackageInfo info = pm.getPackageInfo(pkg, 0);
                    installed.put(pkg, info.firstInstallTime);
                    updated = info.lastUpdateTime;
                } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                    installed.put(pkg, 0L);
                }
                Known known = KNOWN.get(pkg);
                if (known == null || known.updated != updated) {
                    known = new Known(updated, loadLabel(pm, entry.getValue()));
                    KNOWN.put(pkg, known);
                }
                names.put(pkg, known.label);
            }
            KNOWN.keySet().retainAll(unique.keySet());
            main.post(() -> {
                if (closed || !loads.isLatest(load)) return;
                tvPackages.clear();
                tvPackages.addAll(tv);
                installTimes.clear();
                installTimes.putAll(installed);
                labels.clear();
                labels.putAll(names);
                fill(new ArrayList<>(unique.values()));
                if (loads.takeReveal()) reveal();
            });
        });
    }

    private void fill(List<ResolveInfo> catalogue) {
        totalApps = catalogue.size();
        apps = catalogue;
        Map<String, Boolean> home = new HashMap<>();
        for (ResolveInfo app : apps) {
            String pkg = app.activityInfo.packageName;
            home.put(pkg, onHome.test(pkg));
        }
        catalogTime = System.currentTimeMillis();
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> {
            String pa = a.activityInfo.packageName, pb = b.activityInfo.packageName;
            if (selectionMode) return LibraryApps.compareCatalog(home.get(pa), label(a), pa,
                    home.get(pb), label(b), pb, collator);
            int priority = LibraryApps.comparePriority(home.get(pa), installTimes.get(pa),
                    home.get(pb), installTimes.get(pb), catalogTime);
            if (priority != 0) return priority;
            int byLabel = collator.compare(label(a), label(b));
            return byLabel != 0 ? byLabel : pa.compareTo(pb);
        });
        showCatalog();
    }

    private String label(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        String cached = labels.get(pkg);
        if (cached != null) return cached;
        String value = loadLabel(activity.getPackageManager(), app);
        labels.put(pkg, value);
        return value;
    }

    private static String loadLabel(PackageManager pm, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        try { return Cards.cleanLabel(String.valueOf(app.loadLabel(pm)), pkg); }
        catch (RuntimeException unavailable) { return pkg; }
    }

    /** The app's icon from this opening's cache, or loaded on LOADER and set once it arrives. */
    private void bindIcon(ImageView icon, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        icon.setTag(pkg);
        Drawable cached = icons.get(pkg);
        icon.setImageDrawable(cached);
        if (tvos) styleArt(icon, pkg, cached);
        if (cached != null || !iconsLoading.add(pkg)) return;
        PackageManager pm = activity.getPackageManager();
        LOADER.execute(() -> {
            Drawable loaded;
            boolean banner = false;
            try {
                loaded = tvos ? banner(app, pm) : null;
                banner = loaded != null;
                if (loaded == null) loaded = app.loadIcon(pm);
            }
            catch (RuntimeException unavailable) { loaded = null; }
            Drawable result = loaded;
            boolean isBanner = banner;
            main.post(() -> {
                iconsLoading.remove(pkg);
                if (closed) return;
                Drawable shown = result != null ? result : activity.getDrawable(R.drawable.ic_apps);
                if (isBanner) banners.add(pkg);
                icons.put(pkg, shown);
                if (tvos && pkg.equals(icon.getTag())) {
                    styleArt(icon, pkg, shown);
                    if (pkg.equals(selectedPackage)) scheduleBackdrop(pkg);
                }
                // The view may show another app by now; a view bound to it later takes it from the cache.
                if (pkg.equals(icon.getTag())) icon.setImageDrawable(shown);
            });
        });
    }

    private void heading(String text) {
        TextView title = text(text, 18, 0xFFFFFFFF);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(12), dp(10), dp(12), dp(8));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Reuse translated actions instead of shipping another hint in every locale. */
    private String hint(boolean home) {
        if (selectionMode) return activity.getString(android.R.string.ok) + ": "
                + activity.getString(home ? R.string.library_remove_shortcut : R.string.library_add_home);
        return activity.getString(home || okOpens ? R.string.library_hint_open : R.string.library_hint_add);
    }

    private void showCatalog() {
        if (tvos) {
            showTvosCatalog();
            return;
        }
        stopHighlight();
        resize(catalogWidth, dp(280));
        panel.removeAllViews();
        LinearLayout header = new LinearLayout(activity);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(4), dp(8), dp(4));
        TextView title = text(activity.getString(R.string.app_library), 22, Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
        title.setGravity(Gravity.CENTER_VERTICAL | (rtl ? Gravity.RIGHT : Gravity.LEFT));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView count = text(activity.getResources().getQuantityString(
                R.plurals.library_app_count, totalApps, totalApps), 13, 0xA6FFFFFF);
        count.setSingleLine(true);
        header.addView(count, new LinearLayout.LayoutParams(-2, -2));
        // The launcher has no global supportsRtl: explicitly mirror this horizontal row.
        if (rtl) {
            header.removeView(count);
            header.addView(count, 0);
            count.setPadding(0, 0, dp(12), 0);
        }
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(44)));
        instructions = text(hint(false), 13, 0xBBFFFFFF);
        instructions.setSingleLine(true);
        instructions.setEllipsize(TextUtils.TruncateAt.END);
        instructions.setPadding(dp(12), 0, dp(12), dp(6));
        panel.addView(instructions, new LinearLayout.LayoutParams(-1, dp(26)));
        panel.setFocusableInTouchMode(apps.isEmpty());
        if (apps.isEmpty()) {
            instructions.setVisibility(View.GONE);
            heading(activity.getString(R.string.library_empty));
            panel.setFocusableInTouchMode(true);
            panel.requestFocus();
            return;
        }
        shelf = new FrameLayout(activity);
        // The glass uses physical x coordinates; only the catalogue mirrors its item order.
        shelf.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        shelf.setClipChildren(false);
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        catalog = list;
        list.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false) {
            @Override protected boolean isLayoutRTL() { return rtl; }
        });
        list.setItemAnimator(null);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setPadding(dp(10), dp(8), dp(10), dp(8));
        list.setAdapter(new Catalog());
        shelf.addView(list, new FrameLayout.LayoutParams(-1, -1));
        highlight = new GlassView(activity, null);
        highlight.setRadius(dp(20));
        highlight.setAlpha(0f);
        highlight.setFocusable(false);
        highlight.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        shelf.addView(highlight, new FrameLayout.LayoutParams(dp(144), dp(180)));
        scrollOffset = 0;
        spring = new SpringAnimation(highlight, new FloatPropertyCompat<View>("libraryScrollX") {
            @Override public float getValue(View view) { return view.getTranslationX() + scrollOffset; }
            @Override public void setValue(View view, float value) { view.setTranslationX(value - scrollOffset); }
        });
        if (reducedMotion) Motion.reduce(spring);
        else spring.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                scrollOffset += dx;
                highlight.setTranslationX(highlight.getTranslationX() - dx);
            }
        });
        panel.addView(shelf, new LinearLayout.LayoutParams(-1, 0, 1));
        int position = Math.min(selectedPosition, apps.size() - 1);
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).activityInfo.packageName.equals(selectedPackage)) { position = i; break; }
        }
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, selectedOffset);
        final int target = position;
        list.post(() -> {
            if (catalog != list || !popup.isShowing()) return;
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    // ---- tvOS 27 (design_language tvos27) -----------------------------------------------------------------

    /** The app's TV banner, which tvOS-style cards show edge to edge; null when it has none. */
    private static Drawable banner(ResolveInfo app, PackageManager pm) {
        Drawable b = app.activityInfo.loadBanner(pm);
        if (b == null && app.activityInfo.applicationInfo != null) b = app.activityInfo.applicationInfo.loadBanner(pm);
        return b;
    }

    private int pt(float value) {
        return Math.round(TvDesign.px(activity, value));
    }

    private int[] palette(String pkg) {
        int[] p = palettes.get(pkg);
        Drawable art = icons.get(pkg);
        if (p == null && art != null) {
            p = HeroArt.palette(art);
            palettes.put(pkg, p);
        }
        return p;
    }

    /** A banner fills its card; an icon sits in the middle of a card tinted with its own colours. */
    private void styleArt(ImageView art, String pkg, Drawable shown) {
        View card = (View) art.getParent();
        if (shown != null && banners.contains(pkg)) {
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setPadding(0, 0, 0, 0);
            card.setBackground(cardSurface(new int[]{0xFF2C2C30, 0xFF1C1C1E}));
            return;
        }
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int h = card.getLayoutParams() != null ? card.getLayoutParams().height : 0;
        int pad = Math.max(0, (h - pt(96)) / 2);
        art.setPadding(pad, pad, pad, pad);
        int[] p = shown != null ? palette(pkg) : null;
        card.setBackground(cardSurface(p != null ? new int[]{p[0], p[2]} : placeholder()));
    }

    private GradientDrawable cardSurface(int[] colors) {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        bg.setCornerRadius(TvDesign.px(activity, TvDesign.Radius.CARD));
        return bg;
    }

    /** A card with nothing of its own to show yet: graphite in dark, white in light. */
    private static int[] placeholder() {
        return TvDesign.light ? new int[]{TvDesign.Color.CARD_TOP, TvDesign.Color.CARD_BOTTOM}
                : new int[]{0xFF3A3A3F, 0xFF1C1C1E};
    }

    /**
     * The backdrop in the focused app's colours, fading to near black at the bottom, as Home's does. In light the
     * colours are a faint wash over light grey, fading to the light scrim.
     */
    private Drawable backdropFor(int[] p) {
        int a = TvDesign.light ? 0x4D000000 : 0xFF000000;
        GradientDrawable hue = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{p[0] & 0xFFFFFF | a, p[1] & 0xFFFFFF | a, p[2] & 0xFFFFFF | a});
        GradientDrawable fall = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{TvDesign.scrim(0x40), TvDesign.scrim(0x99), TvDesign.scrim(0xF2)});
        return new android.graphics.drawable.LayerDrawable(new Drawable[]{
                new ColorDrawable(TvDesign.light ? TvDesign.Color.BACKDROP_TOP : 0xFF000000), hue, fall});
    }

    /** Cross-fades the backdrop to {@code pkg}'s colours once focus has rested on it for a moment. */
    private void scheduleBackdrop(String pkg) {
        if (backdropUpdate != null) main.removeCallbacks(backdropUpdate);
        backdropUpdate = () -> {
            if (closed || pkg.equals(backdropKey)) return;
            int[] p = palette(pkg);
            if (p == null) return;
            backdropKey = pkg;
            Drawable next = backdropFor(p);
            int ms = TvMotion.ms(420, reducedMotion);
            if (backdrop == null || !animations) {
                panel.setBackground(next);
            } else {
                android.graphics.drawable.TransitionDrawable fade =
                        new android.graphics.drawable.TransitionDrawable(new Drawable[]{backdrop, next});
                fade.setCrossFadeEnabled(true);
                panel.setBackground(fade);
                fade.startTransition(ms);
            }
            backdrop = next;
        };
        main.postDelayed(backdropUpdate, backdrop == null ? 0 : 140);
    }

    /**
     * The library as tvOS lays out its app grid: a full screen of cards in the Home's columns inside the safe area,
     * the name under a card only while it has focus, over a backdrop in the focused app's colours.
     */
    private void showTvosCatalog() {
        stopHighlight();
        panel.removeAllViews();
        int side = pt(TvDesign.Layout.SAFE_SIDE), gutter = pt(TvDesign.Layout.GUTTER);
        LinearLayout header = new LinearLayout(activity);
        header.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        header.setGravity(Gravity.BOTTOM);
        header.setPadding(side, pt(TvDesign.Layout.SAFE_TOP), side, 0);
        TextView title = new TextView(activity);
        title.setText(R.string.app_library);
        TvDesign.type(title, TvDesign.Type.TITLE2);
        title.setTypeface(TvDesign.font(activity, TvDesign.BOLD));
        title.setTextColor(TvDesign.Color.LABEL);
        title.setSingleLine(true);
        title.setIncludeFontPadding(false);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView count = new TextView(activity);
        count.setText(activity.getResources().getQuantityString(R.plurals.library_app_count, totalApps, totalApps));
        TvDesign.type(count, TvDesign.Type.CALLOUT);
        count.setTextColor(TvDesign.Color.SECONDARY);
        count.setIncludeFontPadding(false);
        header.addView(count, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));
        instructions = new TextView(activity);
        instructions.setText(hint(false));
        TvDesign.type(instructions, TvDesign.Type.CALLOUT);
        instructions.setTextColor(TvDesign.Color.SECONDARY);
        instructions.setSingleLine(true);
        instructions.setEllipsize(TextUtils.TruncateAt.END);
        instructions.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_FIRST_STRONG);
        instructions.setPadding(side, pt(10), side, 0);
        panel.addView(instructions, new LinearLayout.LayoutParams(-1, -2));
        if (apps.isEmpty()) {
            instructions.setVisibility(View.GONE);
            TextView empty = new TextView(activity);
            empty.setText(R.string.library_empty);
            TvDesign.type(empty, TvDesign.Type.HEADLINE);
            empty.setTextColor(TvDesign.Color.SECONDARY);
            empty.setPadding(side, pt(80), side, 0);
            panel.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            panel.setBackground(backdropFor(new int[]{0xFF2A2C33, 0xFF1A1C22, 0xFF0E0F13}));
            panel.setFocusableInTouchMode(true);
            panel.requestFocus();
            return;
        }
        RecyclerView list = new RecyclerView(activity);
        catalog = list;
        list.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        androidx.recyclerview.widget.GridLayoutManager grid =
                new androidx.recyclerview.widget.GridLayoutManager(activity, columns);
        list.setLayoutManager(grid);
        list.setItemAnimator(null);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        list.setPadding(side - gutter / 2, pt(44), side - gutter / 2, pt(TvDesign.Layout.SAFE_BOTTOM));
        list.setAdapter(new TvosCatalog());
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        int position = Math.min(selectedPosition, apps.size() - 1);
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).activityInfo.packageName.equals(selectedPackage)) { position = i; break; }
        }
        grid.scrollToPositionWithOffset(position, selectedOffset);
        final int target = position;
        list.post(() -> {
            if (catalog != list || !popup.isShowing()) return;
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
        });
        if (backdrop == null) panel.setBackground(backdropFor(new int[]{0xFF2A2C33, 0xFF1A1C22, 0xFF0E0F13}));
    }

    private final class TvosCatalog extends RecyclerView.Adapter<Row> {
        @NonNull @Override public Row onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            int gutter = pt(TvDesign.Layout.GUTTER);
            int width = (parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()) / columns - gutter;
            if (width <= 0) width = pt(TvDesign.Layout.columnWidth(columns));
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(gutter / 2, 0, gutter / 2, pt(20));
            item.setClipChildren(false);
            item.setClipToPadding(false);
            item.setFocusable(true);
            item.setFocusableInTouchMode(true);
            item.setClickable(true);
            item.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            FrameLayout card = new FrameLayout(activity);
            card.setId(R.id.panel);
            card.setClipToOutline(true);
            card.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            card.setBackground(cardSurface(placeholder()));
            ImageView art = new ImageView(activity);
            art.setId(R.id.icon);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1));
            View check = new View(activity);
            check.setId(R.id.dot);
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(pt(30), pt(30), Gravity.TOP | Gravity.END);
            badge.setMargins(0, pt(12), pt(12), 0);
            card.addView(check, badge);
            item.addView(card, new LinearLayout.LayoutParams(width, Math.round(width * 0.6f)));
            TextView name = new TextView(activity);
            name.setId(R.id.title);
            TvDesign.type(name, TvDesign.Type.CAPTION1);
            name.setTextColor(TvDesign.Color.LABEL);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setAlpha(0f);
            LinearLayout.LayoutParams nameAt = new LinearLayout.LayoutParams(-1, -2);
            nameAt.topMargin = pt(24);
            item.addView(name, nameAt);
            TextView status = new TextView(activity);
            status.setId(R.id.status);
            TvDesign.type(status, TvDesign.Type.CAPTION2);
            status.setTextColor(TvDesign.Color.SECONDARY);
            status.setGravity(Gravity.CENTER);
            status.setSingleLine(true);
            status.setAlpha(0f);
            item.addView(status, new LinearLayout.LayoutParams(-1, -2));
            return new Row(item);
        }

        @Override public void onBindViewHolder(@NonNull Row holder, int position) {
            ResolveInfo app = apps.get(position);
            String pkg = app.activityInfo.packageName;
            View item = holder.itemView;
            View card = item.findViewById(R.id.panel);
            bindIcon(item.findViewById(R.id.icon), app);
            TextView name = item.findViewById(R.id.title);
            name.setText(label(app));
            card.setContentDescription(label(app));
            boolean home = onHome.test(pkg);
            boolean recent = !selectionMode && LibraryApps.recent(installTimes.getOrDefault(pkg, 0L), catalogTime);
            TextView status = item.findViewById(R.id.status);
            status.setText(home ? R.string.library_on_home : recent ? R.string.library_recent : R.string.library_add_home);
            status.setVisibility(home || !okOpens || recent ? View.VISIBLE : View.INVISIBLE);
            View check = item.findViewById(R.id.dot);
            int size = pt(home ? 30 : 14);
            check.getLayoutParams().width = size;
            check.getLayoutParams().height = size;
            if (home) check.setBackgroundResource(R.drawable.library_check);
            else {
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                dot.setColor(0xFF30D158);
                check.setBackground(dot);
            }
            check.setVisibility(home || recent ? View.VISIBLE : View.GONE);
            boolean focused = item.isFocused();
            // When OK opens every app, a tick on every card is noise: it shows on the focused card only.
            boolean quiet = okOpens && home;
            check.setAlpha(quiet && !focused ? 0f : 1f);
            TvFocus.snap(card, focused, spec);
            name.setAlpha(focused ? 1f : 0f);
            status.setAlpha(focused ? 1f : 0f);
            item.setOnClickListener(v -> {
                NavSound.open(v);
                boolean onHomeNow = onHome.test(pkg);
                if (LibraryApps.okOpens(selectionMode, okOpens, onHomeNow)) open(app);
                else changeHome(app, !onHomeNow);
            });
            item.setOnLongClickListener(selectionMode ? null : v -> { showActions(app, card); return true; });
            item.setOnFocusChangeListener((v, has) -> {
                TvFocus.focus(card, has, spec);
                int ms = TvMotion.ms(has ? TvDesign.Motion.FOCUS_MS : TvDesign.Motion.UNFOCUS_MS, reducedMotion);
                name.animate().alpha(has ? 1f : 0f).setDuration(animations ? ms : 0).start();
                status.animate().alpha(has ? 1f : 0f).setDuration(animations ? ms : 0).start();
                if (quiet) check.animate().alpha(has ? 1f : 0f).setDuration(animations ? ms : 0).start();
                if (!has) return;
                selectedPackage = pkg;
                instructions.setText(hint(onHome.test(pkg)));
                int index = holder.getBindingAdapterPosition();
                if (index != RecyclerView.NO_POSITION) selectedPosition = index;
                scheduleBackdrop(pkg);
            });
            item.setOnKeyListener((v, key, event) -> {
                TvFocus.key(card, event, spec);
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int index = holder.getBindingAdapterPosition();
                if (index == RecyclerView.NO_POSITION) return false;
                int col = index % columns;
                int start = rtl ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
                int end = rtl ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
                if (key == start) return col == 0;
                if (key == end) return col == columns - 1 || index == apps.size() - 1;
                if (key == KeyEvent.KEYCODE_DPAD_UP) return index < columns;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int lastRow = (apps.size() - 1) / columns;
                    if (index / columns >= lastRow) return true;
                    // A short last row: Down from a column past its end lands on its last app.
                    if (index + columns >= apps.size()) {
                        RecyclerView.ViewHolder last = catalog != null
                                ? catalog.findViewHolderForAdapterPosition(apps.size() - 1) : null;
                        if (last != null) last.itemView.requestFocus();
                        return true;
                    }
                }
                return false;
            });
        }

        @Override public int getItemCount() { return apps.size(); }
    }

    private void dismissActions() {
        PopupWindow menu = actionPopup;
        actionPopup = null;
        if (menu != null) menu.dismiss();
    }

    private void restoreCardFocus() {
        if (catalog == null || !popup.isShowing()) return;
        catalog.post(() -> {
            if (catalog == null || !popup.isShowing() || actionPopup != null) return;
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void changeHome(ResolveInfo app, boolean add) {
        if (!LibraryApps.save(activity, app.activityInfo.packageName, add)) {
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (selectionMode) {
            updateSelectionFocus(app, add);
            changed.run();
            fill(new ArrayList<>(apps));
            return;
        }
        selectedOffset = 0;
        if (add && catalog != null) {
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (holder != null) {
                View card = holder.itemView;
                RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) card.getLayoutParams();
                selectedOffset = tvos
                        ? catalog.getLayoutManager().getDecoratedTop(card) - params.topMargin - catalog.getPaddingTop()
                        : rtl
                        ? catalog.getWidth() - catalog.getPaddingRight()
                            - catalog.getLayoutManager().getDecoratedRight(card) - params.rightMargin
                        : catalog.getLayoutManager().getDecoratedLeft(card)
                            - params.leftMargin - catalog.getPaddingLeft();
            }
            // Advance through missing shortcuts instead of following the newly checked app
            // into the Home group. Preserve its screen slot when the catalogue is rebuilt.
            int next = LibraryApps.nextFocus(selectedPosition, apps.size(),
                    index -> !onHome.test(apps.get(index).activityInfo.packageName));
            if (next >= 0) {
                selectedPackage = apps.get(next).activityInfo.packageName;
                selectedPosition = next;
            }
        }
        dismissActions();
        changed.run();
        refresh();
    }

    private void updateSelectionFocus(ResolveInfo app, boolean add) {
        int position = apps.indexOf(app);
        int next = add ? -1 : LibraryApps.nextAfterRemoval(position, apps.size());
        selectedOffset = 0;
        if (!add && catalog != null) {
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                View card = holder.itemView;
                RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) card.getLayoutParams();
                selectedOffset = tvos
                        ? catalog.getLayoutManager().getDecoratedTop(card) - params.topMargin - catalog.getPaddingTop()
                        : rtl
                        ? catalog.getWidth() - catalog.getPaddingRight()
                            - catalog.getLayoutManager().getDecoratedRight(card) - params.rightMargin
                        : catalog.getLayoutManager().getDecoratedLeft(card)
                            - params.leftMargin - catalog.getPaddingLeft();
            }
        }
        selectedPackage = next >= 0 ? apps.get(next).activityInfo.packageName : app.activityInfo.packageName;
    }

    private void open(ResolveInfo app) {
        close();
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(
                new ComponentName(app.activityInfo.packageName, app.activityInfo.name));
        // TV apps keep the leanback launch intent, so an app already running resumes instead of restarting.
        if (tvPackages.contains(app.activityInfo.packageName)) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { activity.startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
        }
    }

    private void showActions(ResolveInfo app, View anchor) {
        dismissActions();
        selectedPackage = app.activityInfo.packageName;
        String pkg = selectedPackage;
        if (spring != null) spring.cancel();
        View menuPanel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, panel, false);
        LinearLayout rows = menuPanel.findViewById(R.id.rows);
        if (tvos) {
            menuPanel.setBackground(LiquidGlass.platter(activity));
            int pad = Math.round(TvDesign.px(activity, 14));
            menuPanel.setPadding(pad, pad, pad, pad);
        }
        PopupWindow menu = new PopupWindow(menuPanel, dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        actionPopup = menu;
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setAnimationStyle(0);
        menu.setElevation(0f);
        menu.setOnDismissListener(() -> {
            if (actionPopup == menu) actionPopup = null;
            restoreCardFocus();
        });
        addAction(rows, R.string.library_open, () -> open(app));
        boolean home = onHome.test(pkg);
        addAction(rows, home ? R.string.library_remove_shortcut : R.string.library_add_home,
                () -> changeHome(app, !home));
        Intent info = SystemSettings.appInfo(activity, pkg);
        if (info != null) addAction(rows, R.string.library_app_info, () -> {
            close();
            try {
                activity.startActivity(info);
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
            }
        });
        int menuWidth = dp(220);
        for (int i = 0; i < rows.getChildCount(); i++) {
            TextView title = rows.getChildAt(i).findViewById(R.id.title);
            menuWidth = Math.max(menuWidth, (int) Math.ceil(title.getPaint().measureText(title.getText().toString())) + dp(58));
            title.setEllipsize(TextUtils.TruncateAt.END);
        }
        menuWidth = Math.min(menuWidth, panel.getWidth() - dp(24));
        menu.setWidth(menuWidth);
        menuPanel.measure(View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int[] panelLocation = new int[2];
        panel.getLocationOnScreen(panelLocation);
        Rect screen = new Rect(panelLocation[0], panelLocation[1],
                panelLocation[0] + panel.getWidth(), panelLocation[1] + panel.getHeight());
        int x = Math.max(screen.left + dp(12), Math.min(location[0] + anchor.getWidth() - dp(12), screen.right - menuWidth - dp(12)));
        int y = Math.max(screen.top + dp(12), Math.min(location[1] + dp(8), screen.bottom - menuPanel.getMeasuredHeight() - dp(12)));
        menuPanel.setAlpha(0f);
        menuPanel.setPivotY(0f);
        menuPanel.setScaleY(0.92f);
        menuPanel.setTranslationY(-dp(8));
        menu.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.START, x, y);
        menuPanel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(reducedMotion ? Motion.REDUCED_MS : 220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addAction(LinearLayout rows, int label, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((TextView) row.findViewById(R.id.title)).setText(label);
        if (tvos) ((TextView) row.findViewById(R.id.title)).setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{TvDesign.Glass.FOCUSED_TEXT, TvDesign.Color.LABEL}));
        row.setFocusableInTouchMode(true);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> action.run());
        rows.addView(row);
        row.setOnKeyListener((v, key, event) -> {
            int index = rows.indexOfChild(v);
            return event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_LEFT
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP && index == 0
                    || key == KeyEvent.KEYCODE_DPAD_DOWN && index == rows.getChildCount() - 1);
        });
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_FIRST_STRONG);
        view.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(tvos ? TvDesign.font(activity, TvDesign.MEDIUM) : UiFont.get(activity, R.string.font_medium));
        view.setLetterSpacing(0.01f);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(int value) { return Math.round(value * density); }

    private final class Catalog extends RecyclerView.Adapter<Row> {
        @NonNull @Override public Row onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout row = new FrameLayout(activity);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(dp(132), dp(170));
            params.setMargins(dp(6), dp(2), dp(6), dp(2));
            row.setLayoutParams(params);
            row.setPadding(dp(10), dp(12), dp(10), dp(10));
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            row.addView(content, new FrameLayout.LayoutParams(-1, -1));
            ImageView icon = new ImageView(activity);
            icon.setId(R.id.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(icon, new LinearLayout.LayoutParams(dp(60), dp(60)));
            TextView name = text("", 15, Color.WHITE);
            name.setId(R.id.title);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, dp(44));
            nameParams.topMargin = dp(10);
            content.addView(name, nameParams);
            TextView status = text("", 11, 0xBBFFFFFF);
            status.setId(R.id.status);
            status.setGravity(Gravity.CENTER);
            status.setMaxLines(2);
            content.addView(status, new LinearLayout.LayoutParams(-1, -2));
            View check = new View(activity);
            check.setId(R.id.dot);
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.END);
            badge.setMargins(0, dp(2), dp(2), 0);
            row.addView(check, badge);
            return new Row(row);
        }
        @Override public void onBindViewHolder(@NonNull Row holder, int position) {
            ResolveInfo app = apps.get(position);
            String pkg = app.activityInfo.packageName;
            bindIcon(holder.itemView.findViewById(R.id.icon), app);
            TextView name = holder.itemView.findViewById(R.id.title);
            name.setText(label(app));
            name.setEllipsize(TextUtils.TruncateAt.END);
            TextView status = holder.itemView.findViewById(R.id.status);
            boolean home = onHome.test(pkg);
            boolean recent = !selectionMode && LibraryApps.recent(installTimes.getOrDefault(pkg, 0L), catalogTime);
            status.setText(home ? R.string.library_on_home : recent ? R.string.library_recent : R.string.library_add_home);
            status.setVisibility(home || !okOpens ? View.VISIBLE : View.INVISIBLE);
            holder.itemView.setBackground(surface(home ? 0x1475BBA4 : 0x0CFFFFFF, 18));
            View check = holder.itemView.findViewById(R.id.dot);
            check.getLayoutParams().width = dp(home ? 18 : 8);
            check.getLayoutParams().height = dp(home ? 18 : 8);
            if (home) check.setBackgroundResource(R.drawable.library_check);
            else check.setBackground(surface(0xFF70D9A0, 8));
            check.setVisibility(home || recent ? View.VISIBLE : View.GONE);
            holder.itemView.setOnClickListener(v -> {
                boolean onHomeNow = onHome.test(pkg);
                if (LibraryApps.okOpens(selectionMode, okOpens, onHomeNow)) open(app);
                else changeHome(app, !onHomeNow);
            });
            holder.itemView.setOnLongClickListener(selectionMode ? null : v -> { showActions(app, v); return true; });
            holder.itemView.setOnFocusChangeListener((v, focused) -> {
                if (focused) {
                    selectedPackage = pkg;
                    instructions.setText(hint(onHome.test(pkg)));
                    v.post(() -> { if (v.hasFocus() && catalog != null) highlight(v, true); });
                    int index = holder.getBindingAdapterPosition();
                    if (index != RecyclerView.NO_POSITION) selectedPosition = index;
                }
            });
            holder.itemView.setOnKeyListener((v, key, event) -> {
                int index = holder.getBindingAdapterPosition();
                return event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_UP
                        || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || (rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT) && index == 0
                        || (rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT) && index == apps.size() - 1);
            });
        }
        @Override public int getItemCount() { return apps.size(); }
    }

    private static final class Row extends RecyclerView.ViewHolder {
        Row(View view) { super(view); }
    }
}
