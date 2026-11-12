package com.gathin.tvhome;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;

/**
 * design_language tvos27: the Google account for the avatar in Home's time capsule and Control Center's profile tile.
 * Since Android 8 an app sees only the accounts it is allowed to: none, until the person picks one in the system's
 * account chooser ({@link #chooser}), which makes that account visible to the launcher from then on. A real photo
 * needs OAuth, so the avatar is a monogram on a colour taken from the address, or a person when there is none.
 */
final class GoogleAccount {
    static final String TYPE = "com.google";
    static final int REQUEST_CHOOSE = 0x6A01;
    private static final String PREFS = "google_account", NAME = "name";
    /**
     * Google TV's Accounts and profiles screen: the signed-in account, Add account, and each account's own Sync and
     * Remove page. Google TV's profile chooser lives in launcherx, which is disabled once this launcher is Home.
     */
    private static final ComponentName ACCOUNTS = new ComponentName("com.android.tv.settings",
            "com.google.android.tv.settings.AccountActivity");
    /** Android TV's plain accounts list, where Google TV's screen is missing. */
    private static final ComponentName ACCOUNTS_AOSP = new ComponentName("com.android.tv.settings",
            "com.android.tv.settings.accounts.AccountsActivity");

    private static volatile String email;
    private static volatile boolean loaded;

    interface Listener {
        void onAccount(String email);
    }

    private GoogleAccount() {
    }

    /** The address last read, or null; {@link #load} fills it. */
    static String cached() {
        return email;
    }

    /**
     * Reads the visible Google account off the main thread, once per process unless {@code fresh}: the one picked in
     * the chooser when it is still there, else the first.
     */
    static void load(Context ctx, boolean fresh, Listener listener) {
        if (loaded && !fresh) {
            listener.onAccount(email);
            return;
        }
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String picked = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(NAME, null);
            String found = null;
            try {
                for (Account a : AccountManager.get(app).getAccountsByType(TYPE)) {
                    if (found == null || a.name.equals(picked)) found = a.name;
                }
            } catch (RuntimeException ignored) {
                // No account service: the person glyph stays.
            }
            email = found;
            loaded = true;
            String result = found;
            main.post(() -> listener.onAccount(result));
        }, "google-account").start();
    }

    /**
     * The system's account chooser for Google accounts: picking one lets the launcher see it. Android's own, named
     * outright: Google TV points newChooseAccountIntent at GMS's picker, which hands it to launcherx's profile chooser
     * and crashes once launcherx is disabled. Android's chooser takes a single account without asking.
     */
    static Intent chooser() {
        return AccountManager.newChooseAccountIntent(null, null, new String[]{TYPE}, null, null, null, null)
                .setComponent(new ComponentName("android", "android.accounts.ChooseTypeAndAccountActivity"));
    }

    /**
     * The profile control. With no account visible yet, the system's account chooser, for a result: picking there
     * lets the launcher see the account (true: {@link #chosen} follows in onActivityResult). With one, Google TV's
     * Accounts and profiles, then Android TV's accounts list, then Settings (false).
     */
    static boolean open(Activity a) {
        PackageManager pm = a.getPackageManager();
        if (email == null) {
            Intent choose = chooser();
            if (choose.resolveActivity(pm) != null) {
                try {
                    a.startActivityForResult(choose, REQUEST_CHOOSE);
                    return true;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    // Accounts and profiles, below.
                }
            }
        }
        openAccounts(a);
        return false;
    }

    /** Accounts and profiles. Never the bare SYNC_SETTINGS: with no account named it shows "Unknown account". */
    static void openAccounts(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent[] tries = {new Intent().setComponent(ACCOUNTS), new Intent().setComponent(ACCOUNTS_AOSP),
                new Intent(Settings.ACTION_SETTINGS)};
        for (Intent i : tries) {
            if (i.resolveActivity(pm) == null) continue;
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Next one.
            }
        }
    }

    /** The chooser's answer: the account picked, now visible to the launcher, or null when it was cancelled. */
    static String chosen(Context ctx, int resultCode, Intent data) {
        String name = resultCode == Activity.RESULT_OK && data != null
                ? data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) : null;
        if (name == null || name.isEmpty()) return null;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(NAME, name).apply();
        email = name;
        loaded = true;
        return name;
    }

    /** A round avatar for {@code address}: its first letter on a colour of its own, or a person when null. */
    static Drawable avatar(Context ctx, String address) {
        return new Avatar(address, TvDesign.font(ctx, TvDesign.SEMIBOLD));
    }

    /** Muted colours in the spirit of Google's monogram avatars. */
    private static final int[] COLORS = {
            0xFF5C6BC0, 0xFF26A69A, 0xFFEF6C00, 0xFF8E24AA, 0xFF43A047, 0xFFD81B60, 0xFF1E88E5, 0xFF6D4C41};

    private static final class Avatar extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final String letter;

        Avatar(String address, Typeface face) {
            boolean known = address != null && !address.isEmpty();
            letter = known ? address.substring(0, 1).toUpperCase(java.util.Locale.ROOT) : null;
            fill.setColor(known ? COLORS[(address.toLowerCase(java.util.Locale.ROOT).hashCode() & 0x7FFFFFFF)
                    % COLORS.length] : 0xFF8E8E93);
            ink.setColor(0xFFFFFFFF);
            ink.setTypeface(face);
            ink.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public void draw(@NonNull Canvas c) {
            Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f, cx = b.exactCenterX(), cy = b.exactCenterY();
            c.drawCircle(cx, cy, r, fill);
            if (letter != null) {
                ink.setTextSize(r * 1.05f);
                Paint.FontMetrics m = ink.getFontMetrics();
                c.drawText(letter, cx, cy - (m.ascent + m.descent) / 2f, ink);
                return;
            }
            // A person: head and shoulders.
            c.drawCircle(cx, cy - r * 0.24f, r * 0.32f, ink);
            oval.set(cx - r * 0.58f, cy + r * 0.2f, cx + r * 0.58f, cy + r * 0.92f);
            c.drawOval(oval, ink);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            ink.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            fill.setColorFilter(cf);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
