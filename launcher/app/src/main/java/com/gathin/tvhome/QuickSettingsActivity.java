package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The panel the remote's Settings button opens when {@code settings_key_panel} is on (HomeKeyService):
 * a few glass rows under the Settings pill, over Home or whatever app is playing. Translucent and in a
 * task of its own, so the app behind keeps running and Back returns to it. Pressing Settings again
 * closes the panel.
 */
public final class QuickSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }


    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        Config cfg = Config.load(this);
        if (cfg.tvos27()) {
            TvDesign.applyAppearance(this, cfg.appearance);
            NavSound.acquire(this, cfg.navigationSounds);
            controlCenter(cfg);
            NavSound.watch(getWindow().getDecorView());
            return;
        }
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            View panel = findViewById(R.id.panel);
            android.widget.FrameLayout.LayoutParams position =
                    (android.widget.FrameLayout.LayoutParams) panel.getLayoutParams();
            position.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            int inset = position.getMarginEnd();
            position.setMarginEnd(0);
            position.setMargins(inset, position.topMargin, 0, position.bottomMargin);
            panel.setLayoutParams(position);
        }
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        LinearLayout rows = findViewById(R.id.rows);
        addRow(rows, R.drawable.ic_settings, R.string.tv_settings, 0, SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        addRow(rows, R.drawable.ic_picture, R.string.quick_picture, 0, SystemSettings.displayIntent(this));
        addRow(rows, R.drawable.ic_wifi, R.string.quick_network, 0, SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        addRow(rows, R.drawable.ic_apps, R.string.quick_apps, R.string.quick_apps_hint,
                SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        addRow(rows, R.drawable.ic_home_settings, R.string.launcher_settings, 0,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        rows.getChildAt(0).requestFocus();
    }

    // ---- tvOS 27 Control Center (design_language tvos27) ---------------------------------------------------

    private static final float UNIT = 124, GAP = 20, PAD = 32;
    /** Back from the Inputs panel: open with its tile focused, already in place. */
    static final String EXTRA_FOCUS_INPUTS = "com.gathin.tvhome.FOCUS_INPUTS";
    private View ccPanel, ccScrim;
    private TvFocus.Spec spec;
    private boolean leaving, toInputs;
    /** The account chooser is up for the profile tile: the panel waits under it for the answer. */
    private boolean choosing;
    private View profile;
    private static final int LARGE = 0, WIDE = 1, SQUARE = 2;
    private View sleepTile, timerTile, timerPanel;
    private NowPlaying nowPlaying;
    private final android.os.Handler clock = new android.os.Handler(android.os.Looper.getMainLooper());
    /** While a sleep timer runs, its minutes left on the Sleep and timer tiles, every 15 s. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.removeCallbacks(this);
            if (sleepTile == null) return;
            setStatus(sleepTile, sleepStatus());
            setStatus(timerTile, null);
            ((TextView) ((LinearLayout) ((LinearLayout) timerTile).getChildAt(1)).getChildAt(0)).setText(timerLabel());
            int left = SleepTimer.minutesLeft(QuickSettingsActivity.this);
            timerTile.setContentDescription(getString(R.string.tvos_sleep_timer) + ", " + (left > 0
                    ? getString(R.string.tvos_sleep_left, left) : getString(R.string.tvos_off)));
            if (SleepTimer.end(QuickSettingsActivity.this) != 0) clock.postDelayed(this, 15_000);
        }
    };

    /**
     * tvOS 26/27's Control Center: a glass panel down the right side over a soft dim, the time on top, then glass
     * tiles that fill white under focus: the profile, tvOS's large Sleep tile, network and display tiles where it has
     * Wi-Fi and Do Not Disturb, the sleep timer, a row of Settings, Inputs (when the TV has any; it opens TvosInputs
     * in this panel's place), Apps and Launcher settings, then Now Playing. It slides in from the right edge and back
     * out on Back.
     */
    private void controlCenter(Config cfg) {
        // The tiles sit a gap apart, so a focused one fills white in place rather than growing over its neighbours.
        spec = TvFocus.Spec.of(cfg).flat();
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        android.widget.FrameLayout root = findViewById(R.id.scrim);
        findViewById(R.id.panel).setVisibility(View.GONE);
        root.setOnClickListener(v -> finish());
        ccScrim = new View(this);
        ccScrim.setBackground(new android.graphics.drawable.GradientDrawable(rtl
                ? android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT
                : android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{TvDesign.scrim(0x33), TvDesign.scrim(0x66), TvDesign.scrim(0xB3)}));
        root.addView(ccScrim, 0, new android.widget.FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(LiquidGlass.platter(this));
        int pad = px(PAD);
        panel.setPadding(pad, px(28), pad, pad);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        android.widget.FrameLayout.LayoutParams at = new android.widget.FrameLayout.LayoutParams(
                px(4 * UNIT + 3 * GAP + 2 * PAD), -2,
                android.view.Gravity.TOP | (rtl ? android.view.Gravity.LEFT : android.view.Gravity.RIGHT));
        at.topMargin = px(TvDesign.Layout.SAFE_TOP);
        at.leftMargin = at.rightMargin = px(TvDesign.Layout.SAFE_SIDE);
        root.addView(panel, at);
        ccPanel = panel;

        panel.addView(header(cfg), new LinearLayout.LayoutParams(-1, -2));

        // The profile, as at the top of tvOS's Control Center: the Google account's avatar, its address, and the
        // switcher (GoogleAccount) behind it.
        View profile = profileTile();
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(-1, px(UNIT));
        under.topMargin = px(24);
        panel.addView(profile, under);

        int wide = px(2 * UNIT + GAP), unit = px(UNIT), gap = px(GAP);
        // tvOS's large Sleep tile, then network and display, with the sleep timer beside display.
        LinearLayout top = new LinearLayout(this);
        top.setClipChildren(false);
        sleepTile = tile(R.drawable.ic_power, getString(R.string.tvos_sleep), sleepStatus(), LARGE, null);
        sleepTile.setOnClickListener(v -> sleep());
        top.addView(sleepTile, new LinearLayout.LayoutParams(wide, wide));
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setClipChildren(false);
        String[] net = network();
        side.addView(tile(Integer.parseInt(net[2]), net[0], net[1], false,
                SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS)), new LinearLayout.LayoutParams(wide, unit));
        LinearLayout pair = new LinearLayout(this);
        pair.setClipChildren(false);
        pair.addView(tile(R.drawable.ic_picture, getString(R.string.quick_picture), null, SQUARE,
                SystemSettings.displayIntent(this)), new LinearLayout.LayoutParams(unit, unit));
        timerTile = tile(R.drawable.ic_moon, timerLabel(), null, SQUARE, null);
        timerTile.setOnClickListener(v -> showTimerPanel());
        LinearLayout.LayoutParams after = new LinearLayout.LayoutParams(unit, unit);
        after.setMarginStart(gap);
        pair.addView(timerTile, after);
        LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(wide, unit);
        below.topMargin = gap;
        side.addView(pair, below);
        LinearLayout.LayoutParams beside = new LinearLayout.LayoutParams(wide, wide);
        beside.setMarginStart(gap);
        top.addView(side, beside);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2);
        first.topMargin = px(GAP);
        panel.addView(top, first);

        // Settings, Inputs, Apps and Launcher settings as a row of small tiles. Inputs opens TvosInputs in this
        // panel's place, as tvOS's Control Center opens a picker such as audio output; only on a TV with inputs.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setClipChildren(false);
        boolean hasInputs = MainActivity.inputsAvailable;
        View settings = tile(R.drawable.ic_settings, getString(R.string.settings), null, hasInputs ? SQUARE : WIDE,
                SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        bottom.addView(settings, new LinearLayout.LayoutParams(hasInputs ? unit : wide, unit));
        View inputs = null;
        if (hasInputs) {
            inputs = tile(R.drawable.ic_input, getString(R.string.inputs), null, SQUARE, null);
            inputs.setOnClickListener(v -> {
                toInputs = true;
                startActivity(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_VIEW_INPUTS)
                        .putExtra(MainActivity.EXTRA_FROM_CONTROL_CENTER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
                overridePendingTransition(0, 0);
            });
            String now = TvosInputs.current(this);
            if (now != null) inputs.setContentDescription(getString(R.string.inputs) + ", " + now);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(unit, unit);
            lp.setMarginStart(gap);
            bottom.addView(inputs, lp);
        }
        LinearLayout.LayoutParams apps = new LinearLayout.LayoutParams(unit, unit);
        apps.setMarginStart(gap);
        bottom.addView(tile(R.drawable.ic_apps, getString(R.string.quick_apps), null, SQUARE,
                SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)), apps);
        LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(unit, unit);
        next.setMarginStart(gap);
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        View launcher = tile(R.drawable.ic_home_settings, getString(R.string.tvos_launcher), null, SQUARE,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        launcher.setContentDescription(getString(R.string.launcher_settings));
        bottom.addView(launcher, next);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(-1, -2);
        second.topMargin = gap;
        panel.addView(bottom, second);

        // Now Playing, under the tiles: the active media session with its controls, or how to allow it.
        nowPlaying = new NowPlaying(this, spec);
        nowPlaying.fallback = settings;
        View hint = tile(R.drawable.ic_music, getString(R.string.tvos_now_playing),
                getString(R.string.tvos_now_playing_access), WIDE, null);
        hint.setOnClickListener(v -> NowPlaying.openAccess(this));
        hint.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, unit));
        LinearLayout.LayoutParams third = new LinearLayout.LayoutParams(-1, -2);
        third.topMargin = gap;
        panel.addView(nowPlaying.view(), third);
        nowPlaying.start(hint);
        tick.run();

        boolean back = getIntent().getBooleanExtra(EXTRA_FOCUS_INPUTS, false) && inputs != null;
        (back ? inputs : settings).requestFocus();
        MainActivity.controlCenterShown(true);
        boolean animate = cfg.animations;
        if (animate && back) {
            // Back from the Inputs panel, which stood in this one's place over the same dim: only settle in.
            panel.setTranslationX(cfg.reducedMotion ? 0f : px(64f / 3) * (rtl ? -1 : 1));
            panel.setAlpha(0f);
            panel.animate().translationX(0f).alpha(1f).setDuration(TvMotion.ms(TvDesign.Motion.ENTER_MS, cfg.reducedMotion))
                    .setInterpolator(TvMotion.easeOut()).start();
        } else if (animate) {
            float from = px(64) * (rtl ? -1 : 1);
            panel.setTranslationX(from);
            panel.setAlpha(0f);
            ccScrim.setAlpha(0f);
            int ms = TvMotion.ms(TvDesign.Motion.ENTER_MS, cfg.reducedMotion);
            if (cfg.reducedMotion) panel.setTranslationX(0f);
            panel.animate().translationX(0f).alpha(1f).setDuration(ms).setInterpolator(TvMotion.easeOut()).start();
            ccScrim.animate().alpha(1f).setDuration(ms).setInterpolator(TvMotion.easeOut()).start();
        }
    }

    /** The time, large, with the date beside it, as at the top of tvOS's Control Center. */
    private View header(Config cfg) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.BOTTOM);
        android.widget.TextClock time = new android.widget.TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        TvDesign.type(time, TvDesign.Type.TITLE3);
        time.setTextColor(TvDesign.Color.LABEL);
        time.setIncludeFontPadding(false);
        row.addView(time, new LinearLayout.LayoutParams(-2, -2));
        android.widget.TextClock date = new android.widget.TextClock(this);
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(
                getResources().getConfiguration().getLocales().get(0), "EEEEMMMd");
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
        TvDesign.type(date, TvDesign.Type.CAPTION1);
        date.setTextColor(TvDesign.Color.SECONDARY);
        date.setIncludeFontPadding(false);
        date.setGravity(android.view.Gravity.END);
        date.setSingleLine(true);
        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(0, -2, 1);
        fill.bottomMargin = px(6);
        row.addView(date, fill);
        ClockFormat.apply(row, cfg.clockFormat);
        return row;
    }

    /** {name, status, icon}: the network now, the way Home's Wi-Fi pill names it. */
    @SuppressWarnings("deprecation")
    private String[] network() {
        String status = getString(R.string.offline);
        int icon = R.drawable.ic_wifi;
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            boolean wifi = false, ethernet = false;
            for (android.net.Network n : cm != null ? cm.getAllNetworks() : new android.net.Network[0]) {
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (ethernet) {
                icon = R.drawable.ic_ethernet;
                status = getString(R.string.connected);
            } else if (wifi) {
                status = getString(R.string.connected);
                android.net.wifi.WifiManager wm = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
                android.net.wifi.WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) status = ssid;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return new String[]{getString(icon == R.drawable.ic_ethernet ? R.string.ethernet : R.string.wifi), status,
                String.valueOf(icon)};
    }

    /**
     * A Control Center tile: glass at rest and white under focus, with its icon in a round well. A large tile stacks
     * a big well over its name; a wide one sets the well beside its name and status.
     */
    private View profileTile() {
        String address = GoogleAccount.cached();
        View t = tile(0, address != null ? address : getString(R.string.tvos_account),
                getString(address != null ? R.string.tvos_account_switch : R.string.tvos_account_choose), false, null);
        ImageView glyph = (ImageView) ((LinearLayout) t).getChildAt(0);
        glyph.setImageTintList(null);
        glyph.setBackground(null);
        glyph.setPadding(0, 0, 0, 0);
        glyph.setImageDrawable(GoogleAccount.avatar(this, address));
        t.setOnClickListener(v -> {
            if (GoogleAccount.open(this)) choosing = true;
            else finish();
        });
        profile = t;
        GoogleAccount.load(this, false, this::showAccount);
        return t;
    }

    /** The profile tile with {@code a}'s monogram and address, or the person and "Choose account". */
    private void showAccount(String a) {
        LinearLayout t = (LinearLayout) profile;
        ((ImageView) t.getChildAt(0)).setImageDrawable(GoogleAccount.avatar(this, a));
        LinearLayout words = (LinearLayout) t.getChildAt(1);
        ((TextView) words.getChildAt(0)).setText(a != null ? a : getString(R.string.tvos_account));
        ((TextView) words.getChildAt(1)).setText(a != null ? R.string.tvos_account_switch : R.string.tvos_account_choose);
        t.setContentDescription(((TextView) words.getChildAt(0)).getText() + ", " + ((TextView) words.getChildAt(1)).getText());
    }

    /** The account chooser answered: show the account picked; the tile opens Accounts and profiles from now on. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != GoogleAccount.REQUEST_CHOOSE) return;
        choosing = false;
        String a = GoogleAccount.chosen(this, resultCode, data);
        if (a != null && profile != null) showAccount(a);
        if (profile != null) profile.requestFocus();
    }

    /** tvos27: the open sound on Select. */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (ccPanel != null) NavSound.key(event, getCurrentFocus());
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clock.removeCallbacks(tick);
        if (nowPlaying != null) nowPlaying.stop();
        if (ccPanel != null) NavSound.release();
    }

    private View tile(int icon, String title, String status, boolean large, Intent target) {
        return tile(icon, title, status, large ? LARGE : WIDE, target);
    }

    /** A large tile stacks a big well over its name; a wide one sets the well beside its name; a square one is small. */
    private View tile(int icon, String title, String status, int kind, Intent target) {
        boolean large = kind != WIDE, square = kind == SQUARE;
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(large ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        tile.setGravity(large ? android.view.Gravity.CENTER : android.view.Gravity.CENTER_VERTICAL);
        tile.setBackground(LiquidGlass.tile(this, TvDesign.Radius.CARD));
        int inset = px(square ? 10 : large ? 20 : 22);
        tile.setPadding(inset, inset, inset, inset);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription(status != null ? title + ", " + status : title);

        android.content.res.ColorStateList ink = new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{TvDesign.Glass.FOCUSED_TEXT, TvDesign.Color.LABEL});
        android.content.res.ColorStateList dim = new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{TvDesign.Glass.FOCUSED_SECONDARY, TvDesign.Color.SECONDARY});

        ImageView glyph = new ImageView(this);
        if (icon != 0) glyph.setImageResource(icon);
        glyph.setImageTintList(ink);
        glyph.setDuplicateParentStateEnabled(true);
        glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int well = px(square ? 60 : large ? 116 : 64);
        int glyphPad = px(square ? 16 : large ? 28 : 17);
        glyph.setPadding(glyphPad, glyphPad, glyphPad, glyphPad);
        android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
        on.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        on.setColor(0x1A000000);
        android.graphics.drawable.GradientDrawable off = new android.graphics.drawable.GradientDrawable();
        off.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        off.setColor(TvDesign.Color.WELL);
        android.graphics.drawable.StateListDrawable wellBg = new android.graphics.drawable.StateListDrawable();
        wellBg.addState(new int[]{android.R.attr.state_focused}, on);
        wellBg.addState(new int[]{}, off);
        glyph.setBackground(wellBg);
        tile.addView(glyph, new LinearLayout.LayoutParams(well, well));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(large ? android.view.Gravity.CENTER_HORIZONTAL : android.view.Gravity.START);
        words.setDuplicateParentStateEnabled(true);
        TextView name = new TextView(this);
        name.setText(title);
        TvDesign.type(name, square ? TvDesign.Type.CAPTION2 : TvDesign.Type.CAPTION1);
        name.setTextColor(ink);
        name.setMaxLines(status == null && !large ? 2 : 1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setDuplicateParentStateEnabled(true);
        words.addView(name, new LinearLayout.LayoutParams(-2, -2));
        if (status != null) {
            TextView sub = new TextView(this);
            sub.setText(status);
            TvDesign.type(sub, TvDesign.Type.CAPTION2);
            sub.setTextColor(dim);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setDuplicateParentStateEnabled(true);
            words.addView(sub, new LinearLayout.LayoutParams(-2, -2));
        }
        LinearLayout.LayoutParams wordsAt = new LinearLayout.LayoutParams(large ? -2 : 0, -2, large ? 0 : 1);
        if (large) wordsAt.topMargin = px(square ? 6 : 18);
        else wordsAt.setMarginStart(px(16));
        tile.addView(words, wordsAt);

        tile.setOnFocusChangeListener((v, focused) -> TvFocus.focus(v, focused, spec));
        tile.setOnKeyListener((v, key, event) -> TvFocus.key(v, event, spec));
        if (target != null) tile.setOnClickListener(v -> {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
        return tile;
    }

    /** A tile's status line: shown with {@code status}, hidden with null. */
    private void setStatus(View tile, String status) {
        LinearLayout words = (LinearLayout) ((LinearLayout) tile).getChildAt(1);
        TextView sub = words.getChildCount() > 1 ? (TextView) words.getChildAt(1) : null;
        if (sub == null) {
            if (status == null) return;
            sub = new TextView(this);
            TvDesign.type(sub, TvDesign.Type.CAPTION2);
            sub.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                    new int[]{TvDesign.Glass.FOCUSED_SECONDARY, TvDesign.Color.SECONDARY}));
            sub.setSingleLine(true);
            sub.setDuplicateParentStateEnabled(true);
            words.addView(sub, new LinearLayout.LayoutParams(-2, -2));
        }
        sub.setText(status);
        sub.setVisibility(status != null ? View.VISIBLE : View.GONE);
        CharSequence title = ((TextView) words.getChildAt(0)).getText();
        tile.setContentDescription(status != null ? title + ", " + status : title);
    }

    /** "In 23 min" under Sleep while a timer runs; else nothing. */
    private String sleepStatus() {
        int left = SleepTimer.minutesLeft(this);
        return left > 0 ? getString(R.string.tvos_sleep_in, left) : null;
    }

    /** The timer tile's label: "Timer" when off, else the minutes left. */
    private String timerLabel() {
        int left = SleepTimer.minutesLeft(this);
        return left > 0 ? getString(R.string.tvos_sleep_short, left) : getString(R.string.tvos_timer);
    }

    /** Sleep: the TV goes to standby now, ending any timer; a key helper must be bound to do it. */
    private void sleep() {
        if (!SleepTimer.canSleep()) {
            Toast.makeText(this, R.string.tvos_sleep_helper, Toast.LENGTH_LONG).show();
            return;
        }
        SleepTimer.set(this, 0);
        finish();
        overridePendingTransition(0, 0);
        SleepTimer.sleepNow();
    }

    /**
     * The sleep timer's choices, as tvOS opens a picker from Control Center: a list in this panel's place with the
     * current choice checked and focused. Picking one starts it (or Off cancels it) and returns to Control Center.
     */
    private void showTimerPanel() {
        if (timerPanel != null) return;
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(LiquidGlass.platter(this));
        list.setPadding(px(26), px(28), px(26), px(18));
        list.setClipChildren(false);
        list.setLayoutDirection(ccPanel.getLayoutDirection());
        list.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                (android.widget.FrameLayout.LayoutParams) ccPanel.getLayoutParams()));
        TextView head = new TextView(this);
        head.setText(R.string.tvos_sleep_timer);
        TvDesign.type(head, TvDesign.Type.HEADLINE);
        head.setTextColor(TvDesign.Color.LABEL);
        head.setIncludeFontPadding(false);
        head.setPaddingRelative(px(10), 0, 0, px(20));
        list.addView(head);
        int current = SleepTimer.choice(this);
        View focus = null;
        for (int m : SleepTimer.MINUTES) {
            String name = m == 0 ? getString(R.string.tvos_off) : getString(R.string.tvos_minutes, m);
            String detail = m != 0 && m == current ? getString(R.string.tvos_sleep_left, SleepTimer.minutesLeft(this)) : null;
            View row = timerRow(name, detail, m == current);
            row.setOnClickListener(v -> {
                SleepTimer.set(this, m);
                closeTimerPanel();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(detail != null ? 92 : 80));
            if (list.getChildCount() > 1) lp.topMargin = px(8);
            list.addView(row, lp);
            if (m == current) focus = row;
        }
        ((android.view.ViewGroup) ccPanel.getParent()).addView(list);
        timerPanel = list;
        ccPanel.setVisibility(View.INVISIBLE);
        if (focus != null) focus.requestFocus();
        settle(list);
    }

    private void closeTimerPanel() {
        View list = timerPanel;
        timerPanel = null;
        ((android.view.ViewGroup) list.getParent()).removeView(list);
        ccPanel.setVisibility(View.VISIBLE);
        tick.run();
        timerTile.requestFocus();
        settle(ccPanel);
    }

    /** A panel taking another's place over the same dim: it only settles in from a little to the side. */
    private void settle(View v) {
        if (!spec.animations) return;
        boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        v.setTranslationX(spec.reduced ? 0f : px(64f / 3) * (rtl ? -1 : 1));
        v.setAlpha(0f);
        v.animate().translationX(0f).alpha(1f).setDuration(TvMotion.ms(TvDesign.Motion.ENTER_MS, spec.reduced))
                .setInterpolator(TvMotion.easeOut()).start();
    }

    /** One choice: its name, the time left under the running one, and a checkmark; white with dark text in focus. */
    private View timerRow(String name, String detail, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(px(26), 0, px(24), 0);
        row.setFocusable(true);
        row.setClickable(true);
        float r = px(24);
        android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
        on.setColor(TvDesign.Glass.FOCUSED_FILL);
        on.setCornerRadius(r);
        android.graphics.drawable.GradientDrawable off = new android.graphics.drawable.GradientDrawable();
        off.setColor(TvDesign.Color.FILL);
        off.setCornerRadius(r);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, off);
        row.setBackground(bg);
        row.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        row.setContentDescription(detail == null ? name : name + ", " + detail);
        android.content.res.ColorStateList ink = new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{TvDesign.Glass.FOCUSED_TEXT, TvDesign.Color.LABEL});
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setDuplicateParentStateEnabled(true);
        TextView t = new TextView(this);
        t.setText(name);
        TvDesign.type(t, TvDesign.Type.BODY);
        t.setTextColor(ink);
        t.setIncludeFontPadding(false);
        t.setDuplicateParentStateEnabled(true);
        words.addView(t);
        if (detail != null) {
            TextView d = new TextView(this);
            d.setText(detail);
            TvDesign.type(d, TvDesign.Type.CAPTION2);
            d.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                    new int[]{TvDesign.Glass.FOCUSED_SECONDARY, TvDesign.Color.SECONDARY}));
            d.setIncludeFontPadding(false);
            d.setDuplicateParentStateEnabled(true);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, -2);
            dp.topMargin = px(4);
            words.addView(d, dp);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView check = new ImageView(this);
        check.setImageResource(R.drawable.ic_check);
        check.setImageTintList(ink);
        check.setDuplicateParentStateEnabled(true);
        check.setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
        row.addView(check, new LinearLayout.LayoutParams(px(34), px(34)));
        row.setOnFocusChangeListener((v, f) -> TvFocus.focus(v, f, spec));
        row.setOnKeyListener((v, code, e) -> {
            TvFocus.key(v, e, spec);
            return code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        return row;
    }

    /** Back slides Control Center out to the right before it goes; from the timer's list, back to Control Center. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (timerPanel != null) {
            closeTimerPanel();
            return;
        }
        if (ccPanel == null || leaving || !spec.animations) {
            super.onBackPressed();
            return;
        }
        leaving = true;
        MainActivity.controlCenterShown(false);
        int ms = TvMotion.ms(TvDesign.Motion.EXIT_MS, spec.reduced);
        boolean rtl = ccPanel.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        ccScrim.animate().alpha(0f).setDuration(ms).setInterpolator(TvMotion.easeInOut()).start();
        ccPanel.animate().translationX(spec.reduced ? 0f : px(64) * (rtl ? -1 : 1)).alpha(0f).setDuration(ms)
                .setInterpolator(TvMotion.easeInOut()).withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, 0);
                }).start();
    }

    private int px(float pt) {
        return Math.round(TvDesign.px(this, pt));
    }

    /** Home's time capsule comes back as the panel goes, unless the Inputs panel takes its place. */
    @Override
    public void finish() {
        if (ccPanel != null && !toInputs) MainActivity.controlCenterShown(false);
        clock.removeCallbacks(tick);
        if (nowPlaying != null) nowPlaying.stop();
        super.finish();
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    @SuppressWarnings("deprecation")
    protected void onResume() {
        super.onResume();
        if (ccPanel != null) NavSound.resumed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ccPanel != null) NavSound.paused();
        if (choosing) return;
        finish();
        overridePendingTransition(0, 0);
    }

    private void addRow(LinearLayout rows, int icon, int title, int status, Intent target) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView hint = row.findViewById(R.id.status);
        if (status != 0) hint.setText(status);
        else hint.setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
        rows.addView(row);
    }
}
