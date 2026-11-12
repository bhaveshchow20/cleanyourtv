package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * tvos27 Control Center's Sleep and Sleep Timer tiles. Sleep is the accessibility "lock screen" global action
 * (Android 9 and later), which puts a TV in standby, as the Sleep Timer add-on and the "sleep" action word do; it
 * needs one of the launcher's key helpers (HomeKeyService or InputKeyService) bound, since an ordinary app cannot
 * sleep the device.
 *
 * The timer keeps its end time in prefs. A main-thread Handler fires it on time while the process lives, which it
 * does whenever a key helper is bound, and an AlarmManager alarm is the backstop after the process is killed: exact
 * where the app may schedule exact alarms, else within a window. Nothing runs in between.
 */
final class SleepTimer {
    /** Off, then the choices in minutes. */
    static final int[] MINUTES = {0, 15, 30, 60, 90};
    private static final String PREFS = "sleep_timer", END = "end", CHOICE = "minutes";
    private static final String ACTION = "com.gathin.tvhome.SLEEP_TIMER";
    /** Made on first use: a Handler built while the class loads would break the JVM tests. */
    private static Handler main;

    private static Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }
    private static Runnable pending;
    /** Home's time capsule moon, while Home is up. */
    private static java.lang.ref.WeakReference<View> moon = new java.lang.ref.WeakReference<>(null);

    private SleepTimer() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** When the timer ends, in wall-clock millis, or 0 when it is off. */
    static long end(Context ctx) {
        long end = prefs(ctx).getLong(END, 0);
        return end > System.currentTimeMillis() ? end : 0;
    }

    /** Whole minutes left from {@code now} to {@code end}, rounded up so the last minute reads 1; 0 when past. */
    static int minutesLeft(long end, long now) {
        return end <= now ? 0 : (int) Math.min(Integer.MAX_VALUE, (end - now + 59_999) / 60_000);
    }

    /** When a timer of {@code minutes} started at {@code now} ends; 0 (off) for Off or anything not a choice. */
    static long endFor(int minutes, long now) {
        return minutes > 0 ? now + minutes * 60_000L : 0;
    }

    /** The choice in effect: 0 when off. */
    static int choice(Context ctx) {
        return end(ctx) == 0 ? 0 : prefs(ctx).getInt(CHOICE, 0);
    }

    /** Whole minutes left, rounded up, or 0 when off. */
    static int minutesLeft(Context ctx) {
        return minutesLeft(end(ctx), System.currentTimeMillis());
    }

    /** Starts a timer of {@code minutes}, replacing any other; 0 cancels it. */
    static void set(Context ctx, int minutes) {
        Context app = ctx.getApplicationContext();
        cancelScheduled(app);
        long end = endFor(minutes, System.currentTimeMillis());
        if (end == 0) {
            prefs(app).edit().remove(END).remove(CHOICE).apply();
        } else {
            long ms = minutes * 60_000L;
            prefs(app).edit().putLong(END, end).putInt(CHOICE, minutes).apply();
            pending = () -> fire(app);
            main().postAtTime(pending, SystemClock.uptimeMillis() + ms);
            AlarmManager am = app.getSystemService(AlarmManager.class);
            if (am != null) {
                PendingIntent pi = alarm(app);
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC, end, pi);
                } else {
                    am.setWindow(AlarmManager.RTC, end, 60_000, pi);
                }
            }
        }
        showMoon(minutes > 0);
    }

    private static void cancelScheduled(Context app) {
        if (pending != null) main().removeCallbacks(pending);
        pending = null;
        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(alarm(app));
    }

    private static PendingIntent alarm(Context app) {
        return PendingIntent.getBroadcast(app, 0, new Intent(app, Alarm.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The timer ran out (Handler or alarm, whichever comes first): the other finds it already off. */
    private static void fire(Context app) {
        long end = prefs(app).getLong(END, 0);
        if (end == 0 || System.currentTimeMillis() < end - 2_000) return;
        set(app, 0);
        if (!sleepNow()) {
            // No key helper bound: the screensaver is the nearest thing, and the TV's own idle sleep follows it.
            // AOSP's SystemUI starts it (Somnambulator); a build without one (Fire OS, some vendor UIs) gets the
            // launcher's own clock instead, which is dark and lets the TV's idle sleep follow too.
            Intent dream = new Intent(Intent.ACTION_MAIN)
                    .setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (dream.resolveActivity(app.getPackageManager()) == null) {
                dream = new Intent(app, ClockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            try {
                app.startActivity(dream);
            } catch (RuntimeException ignored) {
                // nothing left to show: the TV's own idle timeout still applies
            }
        }
    }

    /** Whether Sleep can work now: Android 9 or later with a key helper bound. */
    static boolean canSleep() {
        return Build.VERSION.SDK_INT >= 28 && (HomeKeyService.canOpenPowerMenu() || InputKeyService.isRunning());
    }

    /** Puts the TV to sleep now; false when no key helper is bound to do it. */
    static boolean sleepNow() {
        if (Build.VERSION.SDK_INT < 28) return false;
        return HomeKeyService.sleep() || InputKeyService.sleep();
    }

    /** tvos27 Home: a small moon at the start of the time capsule, shown while a timer runs. */
    static void addMoon(LinearLayout capsule, int sizePx, int gapPx) {
        ImageView m = new ImageView(capsule.getContext());
        m.setImageResource(R.drawable.ic_moon);
        m.setImageTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{TvDesign.Glass.FOCUSED_TEXT, TvDesign.Color.LABEL}));
        m.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
        lp.setMarginEnd(gapPx);
        capsule.addView(m, 0, lp);
        moon = new java.lang.ref.WeakReference<>(m);
        m.setVisibility(end(capsule.getContext()) != 0 ? View.VISIBLE : View.GONE);
    }

    private static void showMoon(boolean on) {
        View m = moon.get();
        if (m != null) m.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** The backstop alarm. Not exported: only this app's PendingIntent reaches it. */
    public static final class Alarm extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION.equals(intent.getAction())) fire(context.getApplicationContext());
        }
    }
}
