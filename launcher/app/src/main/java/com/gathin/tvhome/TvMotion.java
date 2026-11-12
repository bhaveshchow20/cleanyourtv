package com.gathin.tvhome;

import android.app.Activity;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * tvOS 27 motion: the two curves, the focus spring, and how a screen or panel comes in and goes out. Every call
 * respects reduced_motion (then it is {@link Motion}'s short, bounce-free version) and animations=false (no motion).
 */
final class TvMotion {

    private static Interpolator easeOut, easeInOut;

    private TvMotion() {
    }

    static Interpolator easeOut() {
        if (easeOut == null) easeOut = curve(TvDesign.Motion.EASE_OUT);
        return easeOut;
    }

    private static Interpolator focusEase;

    /** The focus glide: Home cards and every TvFocus tile growing, settling, brightening and dimming. */
    static Interpolator focusEase() {
        if (focusEase == null) focusEase = curve(TvDesign.Motion.FOCUS_EASE);
        return focusEase;
    }

    static Interpolator easeInOut() {
        if (easeInOut == null) easeInOut = curve(TvDesign.Motion.EASE_IN_OUT);
        return easeInOut;
    }

    private static Interpolator curve(float[] c) {
        return new PathInterpolator(c[0], c[1], c[2], c[3]);
    }

    /** A duration under reduced_motion: never longer than {@link Motion#REDUCED_MS}. */
    static int ms(int ms, boolean reduced) {
        return reduced ? Math.min(ms, Motion.REDUCED_MS) : ms;
    }

    /** Puts the focus spring on {@code spring}, or reduced motion's. */
    static void focusSpring(SpringAnimation spring, boolean reduced) {
        if (reduced) {
            Motion.reduce(spring);
        } else {
            spring.setSpring(new SpringForce().setStiffness(TvDesign.Motion.SPRING_STIFFNESS)
                    .setDampingRatio(TvDesign.Motion.SPRING_DAMPING));
        }
    }

    /** A panel or row materialises: fades in from 96% size, rising a little. */
    static void enter(View v, boolean animations, boolean reduced) {
        v.animate().cancel();
        if (!animations) {
            v.setAlpha(1f);
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setTranslationY(0f);
            return;
        }
        float rise = 12f * v.getResources().getDisplayMetrics().density;
        v.setAlpha(0f);
        if (!reduced) {
            v.setScaleX(0.96f);
            v.setScaleY(0.96f);
            v.setTranslationY(rise);
        }
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(ms(TvDesign.Motion.ENTER_MS, reduced)).setInterpolator(easeOut()).start();
    }

    /** The reverse of {@link #enter}; {@code done} runs when it has gone (at once without animations). */
    static void exit(View v, boolean animations, boolean reduced, Runnable done) {
        v.animate().cancel();
        if (!animations) {
            v.setAlpha(0f);
            if (done != null) done.run();
            return;
        }
        v.animate().alpha(0f).scaleX(reduced ? 1f : 0.96f).scaleY(reduced ? 1f : 0.96f)
                .setDuration(ms(TvDesign.Motion.EXIT_MS, reduced)).setInterpolator(easeInOut())
                .withEndAction(done).start();
    }

    /**
     * The transition for an activity just started from {@code from}: the new screen zooms up out of the focused
     * item while Home recedes behind it. Call straight after startActivity.
     */
    @SuppressWarnings("deprecation")
    static void open(Activity from, boolean animations, boolean reduced) {
        if (!animations) from.overridePendingTransition(0, 0);
        else if (reduced) from.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        else from.overridePendingTransition(R.anim.tvos_open_enter, R.anim.tvos_open_exit);
    }

    /** The way back: call from finish() of a launcher screen opened with {@link #open}. */
    @SuppressWarnings("deprecation")
    static void close(Activity closing, boolean animations, boolean reduced) {
        if (!animations) closing.overridePendingTransition(0, 0);
        else if (reduced) closing.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        else closing.overridePendingTransition(R.anim.tvos_close_enter, R.anim.tvos_close_exit);
    }
}
