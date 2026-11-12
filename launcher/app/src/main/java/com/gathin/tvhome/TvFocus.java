package com.gathin.tvhome;

import android.view.KeyEvent;
import android.view.View;

/**
 * The tvOS 27 focus effect for any focusable card or button (design_language tvos27), after the HIG's Focus and
 * selection: the focused item grows about a tenth and rises on a soft shadow, with no overshoot and no change of
 * colour; Select presses it down; and with focus_parallax a D-pad press tilts it gently towards that direction and
 * back. Nothing sweeps across it on a focus change. Values: {@link TvDesign.Focus}, {@link TvDesign.Motion}.
 *
 * <pre>
 * card.setOnFocusChangeListener((v, f) -&gt; TvFocus.focus(v, f, spec));
 * // in the card's key listener:  TvFocus.key(v, event, spec)  (returns false: the key still goes on)
 * </pre>
 *
 * The view should clip to its outline (rounded corners) so the shadow follows its shape.
 */
final class TvFocus {

    /** How a screen wants focus to move: from Config's animations, reduced_motion and focus_parallax. */
    static final class Spec {
        final boolean animations, reduced, parallax;
        /** False for tiles packed edge to edge (Control Center): focus never scales, lifts or tilts them. */
        final boolean grow;

        Spec(boolean animations, boolean reduced, boolean parallax) {
            this(animations, reduced, parallax, true);
        }

        private Spec(boolean animations, boolean reduced, boolean parallax, boolean grow) {
            this.animations = animations;
            this.reduced = reduced;
            this.parallax = parallax && animations && !reduced && grow;
            this.grow = grow;
        }

        static Spec of(Config cfg) {
            return new Spec(cfg.animations, cfg.reducedMotion, cfg.focusParallax);
        }

        /** The same motion, with focus shown by the fill and a shadow only: the item keeps its size and place. */
        Spec flat() {
            return new Spec(animations, reduced, false, false);
        }
    }

    private TvFocus() {
    }

    /** Focus in or out of {@code v}. Call from its OnFocusChangeListener. */
    static void focus(View v, boolean focused, Spec spec) {
        v.animate().cancel();
        float px = TvDesign.px(v.getContext(), 1f);
        float s = focused && spec.grow ? TvDesign.Focus.SCALE : 1f;
        float lift = focused && spec.grow ? -TvDesign.Focus.LIFT * px : 0f;
        float z = focused ? TvDesign.Focus.SHADOW * px : 0f;
        if (!spec.animations) {
            set(v, 1f, 0f, focused ? z : 0f);
            level(v);
            return;
        }
        v.animate().scaleX(s).scaleY(s).translationY(lift).translationZ(z).rotationX(0f).rotationY(0f)
                .setDuration(TvMotion.ms(focused ? TvDesign.Motion.FOCUS_MS : TvDesign.Motion.UNFOCUS_MS, spec.reduced))
                .setInterpolator(TvMotion.focusEase())
                .start();
    }

    /** Puts {@code v} straight into its resting or focused look, e.g. when a recycled view is bound. */
    static void snap(View v, boolean focused, Spec spec) {
        v.animate().cancel();
        float px = TvDesign.px(v.getContext(), 1f);
        boolean grow = focused && spec.animations && spec.grow;
        set(v, grow ? TvDesign.Focus.SCALE : 1f,
                grow ? -TvDesign.Focus.LIFT * px : 0f,
                focused ? TvDesign.Focus.SHADOW * px : 0f);
        level(v);
    }

    /**
     * No tilt or wobble left on {@code v}: a parallax nudge whose return was cancelled, or a tile that left edit
     * mode mid-wobble, would otherwise keep its angle until something animated it again.
     */
    static void level(View v) {
        v.setRotation(0f);
        v.setRotationX(0f);
        v.setRotationY(0f);
        v.setTranslationX(0f);
    }

    private static void set(View v, float scale, float lift, float z) {
        v.setScaleX(scale);
        v.setScaleY(scale);
        v.setTranslationY(lift);
        v.setTranslationZ(z);
    }

    /**
     * Select pressed ({@code down}) or let go. Pressing sinks the item back towards its resting size and onto a
     * smaller shadow; letting go springs it back up if it still has focus.
     */
    static void press(View v, boolean down, Spec spec) {
        if (!spec.animations) return;
        float px = TvDesign.px(v.getContext(), 1f);
        v.animate().cancel();
        if (down) {
            float s = spec.grow ? TvDesign.Focus.PRESS_SCALE : 1f;
            v.animate().scaleX(s).scaleY(s).translationY(0f)
                    .translationZ(TvDesign.Focus.SHADOW * px / 3f).rotationX(0f).rotationY(0f)
                    .setDuration(TvMotion.ms(TvDesign.Motion.PRESS_MS, spec.reduced)).setInterpolator(TvMotion.easeOut())
                    .start();
        } else {
            focus(v, v.isFocused(), spec);
        }
    }

    /**
     * Handles the focus effect's keys for a focused {@code v}: Select's press, and with parallax a D-pad nudge.
     * Always returns false, so the key still moves focus or clicks.
     */
    static boolean key(View v, KeyEvent event, Spec spec) {
        int code = event.getKeyCode();
        boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (select) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) press(v, true, spec);
            else if (event.getAction() == KeyEvent.ACTION_UP) press(v, false, spec);
        } else if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            nudge(v, code, spec);
        }
        return false;
    }

    /** The parallax nudge: a gentle tilt towards the D-pad direction and back. No light sweeps across (HIG). */
    static void nudge(View v, int keyCode, Spec spec) {
        float[] t = tilt(keyCode);
        if (!spec.parallax || t == null) return;
        v.setCameraDistance(v.getResources().getDisplayMetrics().density * 8000f);
        int half = TvDesign.Motion.NUDGE_MS / 2;
        v.animate().rotationX(t[0]).rotationY(t[1]).setDuration(half).setInterpolator(TvMotion.easeOut())
                .withEndAction(() -> v.animate().rotationX(0f).rotationY(0f).setDuration(half)
                        .setInterpolator(TvMotion.easeInOut()).start())
                .start();
    }

    /**
     * {rotationX, rotationY} in degrees for a D-pad key, or null for any other key. The side the remote pushes
     * towards dips away from the viewer, as a tvOS icon does under the thumb.
     */
    static float[] tilt(int keyCode) {
        float d = TvDesign.Focus.TILT_DEG;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return new float[]{0f, -d};
            case KeyEvent.KEYCODE_DPAD_RIGHT: return new float[]{0f, d};
            case KeyEvent.KEYCODE_DPAD_UP: return new float[]{d, 0f};
            case KeyEvent.KEYCODE_DPAD_DOWN: return new float[]{-d, 0f};
            default: return null;
        }
    }
}
