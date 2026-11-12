package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

import androidx.annotation.NonNull;

/**
 * tvOS 27's Liquid Glass as a background drawable (design_language tvos27): a light tint over whatever is behind,
 * a sheen across the top, and a rim that is bright at the top edge and fades down the sides. There is no blur: the
 * launcher never blurs on a 1 GB TV, so the tint is what keeps text on it readable. Values: {@link TvDesign.Glass},
 * which the light appearance swaps for a frosted white tint whose rim fades to a faint grey, a soft shadow edge.
 *
 * <pre>
 * panel.setBackground(LiquidGlass.panel(ctx));             // a 44 pt-cornered platter
 * button.setBackground(LiquidGlass.control(ctx));          // glass capsule, white when focused
 * </pre>
 */
final class LiquidGlass extends Drawable {

    /** A radius for {@link #LiquidGlass(float, float)} that makes a capsule: half the height, whatever it is. */
    static final float CAPSULE = -1f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;
    private final float rimWidth;
    private int alpha = 255;
    /** Glass on glass (a module in a panel): in light it sinks a shade below the panel instead of adding more white. */
    private boolean nested;

    /** {@code radiusPx} or {@link #CAPSULE}; {@code rimPx} is the rim's width. */
    LiquidGlass(float radiusPx, float rimPx) {
        radius = radiusPx;
        rimWidth = rimPx;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(rimPx);
    }

    /** A glass platter for panels, with {@link TvDesign.Radius#PANEL} corners. */
    static LiquidGlass panel(Context ctx) {
        return new LiquidGlass(TvDesign.px(ctx, TvDesign.Radius.PANEL), TvDesign.px(ctx, TvDesign.Glass.RIM));
    }

    /** A capsule for a button: glass at rest, the white platter tvOS fills a focused control with. */
    static StateListDrawable control(Context ctx) {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(TvDesign.Glass.FOCUSED_FILL);
        // Larger than any control is tall, so GradientDrawable clamps it to a capsule.
        focused.setCornerRadius(10000f);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, new LiquidGlass(CAPSULE, TvDesign.px(ctx, TvDesign.Glass.RIM)));
        return states;
    }

    /** A rounded glass tile ({@code radiusPt} corners) that fills white when focused, as Control Center's tiles do. */
    static StateListDrawable tile(Context ctx, float radiusPt) {
        float r = TvDesign.px(ctx, radiusPt);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(TvDesign.Glass.FOCUSED_FILL);
        focused.setCornerRadius(r);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, new LiquidGlass(r, TvDesign.px(ctx, TvDesign.Glass.RIM)).nested());
        return states;
    }

    /** Marks this glass as sitting inside another glass panel; see {@link #nested}. */
    LiquidGlass nested() {
        nested = true;
        return this;
    }

    /**
     * A panel that floats over anything, video included: the glass over a dark base, since without a blur a light
     * tint alone leaves white text unreadable on a bright picture.
     */
    static Drawable platter(Context ctx) {
        float r = TvDesign.px(ctx, TvDesign.Radius.PANEL);
        GradientDrawable base = new GradientDrawable();
        base.setColor(TvDesign.Glass.PLATTER_BASE);
        base.setCornerRadius(r);
        return new android.graphics.drawable.LayerDrawable(new Drawable[]{base, panel(ctx)});
    }

    @Override
    protected void onBoundsChange(@NonNull Rect b) {
        super.onBoundsChange(b);
        float top = b.top, bottom = b.bottom;
        if (nested && TvDesign.light) {
            // tvOS light Control Center: the modules are a faint grey well in the white panel; focus turns them white.
            fill.setShader(new LinearGradient(0, top, 0, bottom, 0x08000000, 0x12000000, Shader.TileMode.CLAMP));
        } else {
            fill.setShader(new LinearGradient(0, top, 0, bottom, white(TvDesign.Glass.TINT_TOP),
                    white(TvDesign.Glass.TINT_BOTTOM), Shader.TileMode.CLAMP));
        }
        sheen.setShader(new LinearGradient(0, top, 0, top + b.height() * 0.5f, white(TvDesign.Glass.SHEEN),
                0x00FFFFFF, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(0, top, 0, bottom, white(TvDesign.Glass.RIM_TOP),
                TvDesign.Glass.RIM_BOTTOM_COLOR, Shader.TileMode.CLAMP));
    }

    private static int white(int a) {
        return a << 24 | 0xFFFFFF;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float r = cornerRadius(radius, b.height());
        int layer = alpha < 255 ? canvas.saveLayerAlpha(b.left, b.top, b.right, b.bottom, alpha) : -1;
        rect.set(b);
        canvas.drawRoundRect(rect, r, r, fill);
        // The sheen over the top half; clipRect stays on the GPU where clipPath does not on some TV chips.
        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.centerY());
        canvas.drawRoundRect(rect, r, r, sheen);
        canvas.restore();
        float in = rimWidth / 2f;
        rect.inset(in, in);
        canvas.drawRoundRect(rect, Math.max(0f, r - in), Math.max(0f, r - in), rim);
        if (layer >= 0) canvas.restoreToCount(layer);
    }

    /** The corner radius to draw for {@code radius} (or {@link #CAPSULE}) on a shape {@code height} tall. */
    static float cornerRadius(float radius, float height) {
        float half = height / 2f;
        return radius < 0 ? half : Math.min(radius, half);
    }

    @Override
    public void setAlpha(int a) {
        alpha = a;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        fill.setColorFilter(filter);
        sheen.setColorFilter(filter);
        rim.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
