package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * tvos27 Control Center's Now Playing module, as tvOS shows it: the artwork (or the app's icon), the title, the
 * artist or the app under it, and previous, play/pause and next as round glass buttons that fill white under focus.
 * It reads the active media session through MediaSessionManager, which needs NowPlayingListener allowed. Without
 * that it is a one-line hint; with nothing playing it is hidden. Callbacks live only while Control Center is open.
 */
final class NowPlaying {
    private static final float ART = 96, BUTTON = 64, GAP = 18;

    private final Activity a;
    private final TvFocus.Spec spec;
    private final FrameLayout root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessions;
    private ComponentName listener;
    private MediaController controller;
    private ImageView art, badge, playPause;
    private TextView title, subtitle;
    private boolean started;
    /** Where focus goes when the module hides under it. */
    View fallback;

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = list -> pick(list);
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            showState();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            showMetadata();
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    };

    NowPlaying(Activity activity, TvFocus.Spec spec) {
        a = activity;
        this.spec = spec;
        root = new FrameLayout(a);
        root.setClipChildren(false);
        root.setVisibility(View.GONE);
    }

    View view() {
        return root;
    }

    /** Builds the module for what is playing now and listens for changes; false when access is missing. */
    boolean start(View hint) {
        sessions = a.getSystemService(MediaSessionManager.class);
        if (sessions == null) return false;
        for (Class<?> c : new Class<?>[]{NowPlayingListener.class, GuardRevival.class}) {
            ComponentName cn = new ComponentName(a, c);
            try {
                List<MediaController> list = sessions.getActiveSessions(cn);
                sessions.addOnActiveSessionsChangedListener(changed, cn, main);
                listener = cn;
                started = true;
                buildPlayer();
                pick(list);
                return true;
            } catch (SecurityException ignored) {
                // Not allowed for this listener; try the next.
            }
        }
        root.addView(hint);
        root.setVisibility(View.VISIBLE);
        return false;
    }

    void stop() {
        if (!started) return;
        started = false;
        try {
            sessions.removeOnActiveSessionsChangedListener(changed);
        } catch (RuntimeException ignored) {
        }
        setController(null);
    }

    private void refresh() {
        if (!started) return;
        try {
            pick(sessions.getActiveSessions(listener));
        } catch (SecurityException e) {
            setController(null);
        }
    }

    /** Playing first, then paused or buffering with something to show; else nothing. */
    private void pick(List<MediaController> list) {
        if (!started) return;
        MediaController best = null;
        int bestRank = 0;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                int st = s != null ? s.getState() : PlaybackState.STATE_NONE;
                int rank = st == PlaybackState.STATE_PLAYING ? 3
                        : st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_BUFFERING ? 2
                        : c.getMetadata() != null && st != PlaybackState.STATE_STOPPED
                        && st != PlaybackState.STATE_NONE && st != PlaybackState.STATE_ERROR ? 1 : 0;
                if (rank > bestRank) {
                    best = c;
                    bestRank = rank;
                }
            }
        }
        if (best != null && controller != null && best.getSessionToken().equals(controller.getSessionToken())) return;
        setController(best);
    }

    private void setController(MediaController c) {
        if (controller != null) controller.unregisterCallback(callback);
        controller = c;
        boolean had = root.getVisibility() == View.VISIBLE;
        boolean focusInside = root.hasFocus();
        if (c == null) {
            root.setVisibility(View.GONE);
            if (art != null) art.setImageDrawable(null);
            if (focusInside && fallback != null) fallback.requestFocus();
            return;
        }
        c.registerCallback(callback, main);
        showMetadata();
        showState();
        root.setVisibility(View.VISIBLE);
        if (!had && spec.animations) {
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(TvMotion.ms(TvDesign.Motion.FOCUS_MS, spec.reduced))
                    .setInterpolator(TvMotion.easeOut()).start();
        }
    }

    private void showMetadata() {
        MediaController c = controller;
        if (c == null || title == null) return;
        MediaMetadata m = c.getMetadata();
        String pkg = c.getPackageName();
        CharSequence appName = null;
        Drawable icon = null;
        PackageManager pm = a.getPackageManager();
        try {
            appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            icon = pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        CharSequence t = null, sub = null;
        Bitmap picture = null;
        if (m != null) {
            t = first(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            sub = first(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
            for (String key : new String[]{MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
                picture = m.getBitmap(key);
                if (picture != null) break;
            }
        }
        if (TextUtils.isEmpty(t)) t = appName != null ? appName : a.getString(R.string.tvos_now_playing);
        if (appName != null && !TextUtils.equals(appName, t)) sub = TextUtils.isEmpty(sub) ? appName : sub + " \u00B7 " + appName;
        title.setText(t);
        subtitle.setText(sub);
        if (picture != null) {
            art.setImageBitmap(picture);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setPadding(0, 0, 0, 0);
            badge.setImageDrawable(icon);
            badge.setVisibility(icon != null ? View.VISIBLE : View.GONE);
        } else {
            art.setImageDrawable(icon != null ? icon : a.getDrawable(R.drawable.ic_music));
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int p = px(icon != null ? 14 : 26);
            art.setPadding(p, p, p, p);
            badge.setVisibility(View.GONE);
        }
        subtitle.setVisibility(TextUtils.isEmpty(sub) ? View.GONE : View.VISIBLE);
        root.setContentDescription(TextUtils.isEmpty(sub) ? t : t + ", " + sub);
    }

    private static CharSequence first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (!TextUtils.isEmpty(v)) return v;
        }
        return null;
    }

    private boolean playing() {
        PlaybackState s = controller != null ? controller.getPlaybackState() : null;
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    private void showState() {
        if (playPause == null) return;
        boolean on = playing();
        playPause.setImageResource(on ? R.drawable.ic_pause : R.drawable.ic_play);
        playPause.setContentDescription(a.getString(on ? R.string.tvos_pause : R.string.tvos_play));
    }

    private void buildPlayer() {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        float r = TvDesign.px(a, TvDesign.Radius.CARD);
        card.setBackground(new LiquidGlass(r, TvDesign.px(a, TvDesign.Glass.RIM)).nested());
        int pad = px(20);
        card.setPadding(pad, pad, pad, pad);

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout artBox = new FrameLayout(a);
        art = new ImageView(a);
        GradientDrawable well = new GradientDrawable();
        well.setColor(TvDesign.Color.WELL);
        well.setCornerRadius(px(14));
        art.setBackground(well);
        art.setImageTintList(null);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(14));
            }
        });
        art.setClipToOutline(true);
        artBox.addView(art, new FrameLayout.LayoutParams(px(ART), px(ART)));
        badge = new ImageView(a);
        badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badge.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(7));
            }
        });
        badge.setClipToOutline(true);
        badge.setElevation(px(4));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(px(34), px(34), Gravity.BOTTOM | Gravity.END);
        bp.setMargins(0, 0, -px(6), -px(6));
        artBox.addView(badge, bp);
        artBox.setClipChildren(false);
        top.addView(artBox, new LinearLayout.LayoutParams(px(ART), px(ART)));

        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(TvDesign.Type.CAPTION2, TvDesign.Color.SECONDARY);
        label.setText(R.string.tvos_now_playing);
        words.addView(label);
        title = text(TvDesign.Type.BODY, TvDesign.Color.LABEL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = px(2);
        words.addView(title, tp);
        subtitle = text(TvDesign.Type.CAPTION1, TvDesign.Color.SECONDARY);
        words.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
        wp.setMarginStart(px(GAP));
        top.addView(words, wp);
        card.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(a);
        controls.setGravity(Gravity.CENTER);
        controls.setClipChildren(false);
        controls.addView(button(R.drawable.ic_skip_prev, R.string.tvos_previous, v -> {
            if (controller != null) controller.getTransportControls().skipToPrevious();
        }));
        playPause = button(R.drawable.ic_play, R.string.tvos_play, v -> {
            if (controller == null) return;
            if (playing()) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        });
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(px(BUTTON + 8), px(BUTTON + 8));
        mid.setMargins(px(36), 0, px(36), 0);
        controls.addView(playPause, mid);
        controls.addView(button(R.drawable.ic_skip_next, R.string.tvos_next, v -> {
            if (controller != null) controller.getTransportControls().skipToNext();
        }));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = px(14);
        card.addView(controls, cp);
        root.addView(card, new FrameLayout.LayoutParams(-1, -2));
    }

    private ImageView button(int icon, int label, View.OnClickListener click) {
        ImageView b = new ImageView(a);
        b.setImageResource(icon);
        b.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{TvDesign.Glass.FOCUSED_TEXT, TvDesign.Color.LABEL}));
        b.setBackground(LiquidGlass.control(a));
        int p = px(18);
        b.setPadding(p, p, p, p);
        b.setFocusable(true);
        b.setClickable(true);
        b.setContentDescription(a.getString(label));
        b.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        b.setOnFocusChangeListener((v, f) -> TvFocus.focus(v, f, spec));
        b.setOnKeyListener((v, code, e) -> {
            TvFocus.key(v, e, spec);
            // A row of three: Up and Down leave it, sideways stays inside.
            return e.getAction() == KeyEvent.ACTION_DOWN
                    && (code == KeyEvent.KEYCODE_DPAD_LEFT && v == ((LinearLayout) v.getParent()).getChildAt(0)
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT && v == ((LinearLayout) v.getParent()).getChildAt(2));
        });
        b.setOnClickListener(click);
        b.setLayoutParams(new LinearLayout.LayoutParams(px(BUTTON), px(BUTTON)));
        return b;
    }

    private TextView text(TvDesign.Type style, int color) {
        TextView t = new TextView(a);
        TvDesign.type(t, style);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Opens the TV's notification access screen where it has one; else says how to allow it over adb. */
    static void openAccess(Activity a) {
        Intent i = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Android TV builds often leave the screen out, and Fire OS points it at a compliance stub that opens nothing.
        android.content.pm.ResolveInfo r = a.getPackageManager().resolveActivity(i, 0);
        if (r != null && r.activityInfo != null && !r.activityInfo.name.contains("CTSDummy")) {
            try {
                a.startActivity(i);
                return;
            } catch (RuntimeException ignored) {
                // fall through to the hint
            }
        }
        Toast.makeText(a, a.getString(R.string.tvos_now_playing_adb), Toast.LENGTH_LONG).show();
    }

    private int px(float pt) {
        return Math.round(TvDesign.px(a, pt));
    }
}
