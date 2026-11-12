package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow (ClockScene).
 * No network, no images: cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    private ClockScene scene;
    /** design_language tvos27: the Aerial-style screensaver in the clock's place. */
    private AerialScene aerial;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        Config cfg = Config.load(this);
        setScreenBright(cfg.tvos27());   // an Aerial is meant to be seen; the clock dims the screen
        setContentView(cfg.tvos27() ? R.layout.dream_aerial : R.layout.dream_clock);
        ClockFormat.apply(getWindow().getDecorView(), cfg.clockFormat);
        if (cfg.tvos27()) {
            aerial = new AerialScene(getWindow().getDecorView(), cfg);
            return;
        }
        scene = new ClockScene(getWindow().getDecorView());
        scene.useHomeWallpaper(this);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (aerial != null) aerial.start();
        else scene.start();
    }

    @Override
    public void onDreamingStopped() {
        if (aerial != null) aerial.stop();
        else scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        if (aerial != null) aerial.stop();
        super.onDetachedFromWindow();
    }
}
