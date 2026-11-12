package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * The clock screensaver shown by Home itself after home_idle_clock_minutes without input. Some TVs' own
 * screens (CVTE's live TV) do not keep the screen on, so the system screensaver would cut into programmes;
 * this one only ever starts from Home. Any key returns to Home.
 */
public class ClockActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }


    private ClockScene scene;
    /** design_language tvos27: the Aerial-style screensaver in the clock's place. */
    private AerialScene aerial;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Config cfg = Config.load(this);
        setContentView(cfg.tvos27() ? R.layout.dream_aerial : R.layout.dream_clock);
        ClockFormat.apply(getWindow().getDecorView(), cfg.clockFormat);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
        if (cfg.tvos27()) {
            aerial = new AerialScene(getWindow().getDecorView(), cfg);
            getWindow().setBackgroundDrawable(null);   // the layout paints black itself: no second full-screen fill
            animations = cfg.animations;
            // Home stays put while the Aerial dissolves in over it.
            if (animations) overridePendingTransition(R.anim.aerial_enter, R.anim.aerial_hold);
            return;
        }
        scene = new ClockScene(getWindow().getDecorView());
        scene.useHomeWallpaper(this);
    }

    private boolean animations;

    @Override
    protected void onResume() {
        super.onResume();
        if (aerial != null) aerial.start();
        else scene.start();
    }

    @Override
    protected void onPause() {
        if (aerial != null) aerial.stop();
        else scene.stop();
        super.onPause();
        finish();   // never left behind in the back stack
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The key that wakes the clock should not also act on Home, so it is consumed here.
        if (aerial != null && isArrow(event.getKeyCode())) {
            // tvos27: the arrows stand in for touching the Apple TV remote, which shows where the Aerial is.
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) aerial.reveal();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    private static boolean isArrow(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        finish();
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void finish() {
        super.finish();
        if (aerial != null) overridePendingTransition(0, animations ? R.anim.aerial_exit : 0);
        else overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
