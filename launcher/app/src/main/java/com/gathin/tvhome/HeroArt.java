package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * The cinematic shelf's backdrop: an app's artwork reduced to 48×27 pixels, saturated and smoothed,
 * then stretched over the screen by its ImageView. Bilinear filtering of an image that small is a
 * blur that costs one tiny texture; a real blur would mean RenderScript or a shader pass per frame,
 * which a 1 GB TV cannot spare.
 */
final class HeroArt {
    static final int W = 48, H = 27;
    private static final float SATURATION = 1.7f;

    private HeroArt() {
    }

    /** The softened artwork, about 5 KB; null when there is nothing to draw. */
    static Bitmap from(Drawable live) {
        return from(live, W, H, SATURATION, 3, 2);
    }

    /**
     * design_language tvos27's Top Shelf backdrop: no app data to show, so not the banner itself but a soft gradient in
     * its colours. Two or three are sampled from the banner (dominant, a second hue, a muted base) and flow into
     * near-black at the bottom and the edges. 240x135, stretched over the screen by its ImageView.
     */
    static Bitmap shelf(Drawable live) {
        if (live == null) return null;
        int[] c = palette(live);
        int w = 240, h = 135;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(c[2]);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setShader(new RadialGradient(w * 0.28f, h * 0.3f, w * 0.78f, c[0], c[0] & 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new RadialGradient(w * 0.82f, h * 0.22f, w * 0.55f, c[1] & 0x00FFFFFF | 0xCC000000,
                c[1] & 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new LinearGradient(0, h * 0.42f, 0, h, 0x00050507, 0xF5050507, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new RadialGradient(w * 0.5f, h * 0.4f, w * 0.72f, new int[]{0x00000000, 0x00000000, 0xB3000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        return out;
    }

    /** Dominant, second and muted colours of the artwork, toned for a dark backdrop; graphite when it has no hue. */
    static int[] palette(Drawable live) {
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        Bitmap small = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        c.drawColor(Color.BLACK);
        d.setBounds(0, 0, 32, 18);
        d.draw(c);
        d.setBounds(saved);
        int[] px = new int[32 * 18];
        small.getPixels(px, 0, 32, 0, 0, 32, 18);
        small.recycle();
        // Twelve 30-degree hue bins, each pixel weighted by how colourful and how bright it is.
        float[] weight = new float[12], sumR = new float[12], sumG = new float[12], sumB = new float[12];
        float[] hsv = new float[3];
        for (int p : px) {
            Color.colorToHSV(p, hsv);
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
            int bin = (int) (hsv[0] / 30f) % 12;
            float wt = hsv[1] * hsv[2];
            weight[bin] += wt;
            sumR[bin] += Color.red(p) * wt;
            sumG[bin] += Color.green(p) * wt;
            sumB[bin] += Color.blue(p) * wt;
        }
        int first = -1;
        for (int i = 0; i < 12; i++) if (weight[i] > 0 && (first < 0 || weight[i] > weight[first])) first = i;
        if (first < 0 || weight[first] < 2f) {
            return new int[]{tone(0xFF5A5F6E, 0.10f, 0.42f), tone(0xFF3A4152, 0.12f, 0.30f), 0xFF0E0F13};
        }
        int dominant = Color.rgb(Math.round(sumR[first] / weight[first]), Math.round(sumG[first] / weight[first]),
                Math.round(sumB[first] / weight[first]));
        int second = -1;
        for (int i = 0; i < 12; i++) {
            int dist = Math.min(Math.abs(i - first), 12 - Math.abs(i - first));
            if (dist >= 2 && weight[i] > weight[first] * 0.12f && (second < 0 || weight[i] > weight[second])) second = i;
        }
        int secondary;
        if (second >= 0) {
            secondary = Color.rgb(Math.round(sumR[second] / weight[second]), Math.round(sumG[second] / weight[second]),
                    Math.round(sumB[second] / weight[second]));
            secondary = tone(secondary, 0.8f, 0.5f);
        } else {
            // One hue only: a neighbour of it, 30 degrees round and deeper, so the gradient still moves.
            Color.colorToHSV(dominant, hsv);
            hsv[0] = (hsv[0] + 30f) % 360f;
            secondary = tone(Color.HSVToColor(hsv), 0.75f, 0.4f);
        }
        return new int[]{tone(dominant, 0.85f, 0.62f), secondary, tone(dominant, 0.45f, 0.16f)};
    }

    /** The colour's hue at no more than {@code maxSat} saturation and at {@code value} brightness. */
    private static int tone(int color, float maxSat, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(hsv[1], maxSat);
        hsv[2] = value;
        return Color.HSVToColor(hsv);
    }

    private static Bitmap from(Drawable live, int W, int H, float saturation, int passes, int radius) {
        if (live == null) return null;
        // Draw a private copy: changing the bounds of the drawable a tile shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        Bitmap big = Bitmap.createBitmap(W * 4, H * 4, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(big);
        c.drawColor(Color.BLACK);
        d.setBounds(0, 0, big.getWidth(), big.getHeight());
        d.draw(c);
        d.setBounds(saved);
        // Halve twice: each filtered halving averages 2×2 pixels, where one 4× step would skip most of them.
        Bitmap half = Bitmap.createScaledBitmap(big, W * 2, H * 2, true);
        big.recycle();
        Bitmap small = Bitmap.createScaledBitmap(half, W, H, true);
        half.recycle();

        int[] px = new int[W * H];
        small.getPixels(px, 0, W, 0, 0, W, H);
        saturate(px, saturation);
        // Three box passes approximate a gaussian, so the stretched image shows no seams between pixels.
        for (int pass = 0; pass < passes; pass++) {
            px = boxBlur(px, W, H, radius, true);
            px = boxBlur(px, W, H, radius, false);
        }
        Bitmap out = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        if (out != small) small.recycle();
        out.setPixels(px, 0, W, 0, 0, W, H);
        return out;
    }

    private static void saturate(int[] px, float saturation) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Color.red(p), g = Color.green(p), b = Color.blue(p);
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            px[i] = Color.rgb(clamp(lum + (r - lum) * saturation), clamp(lum + (g - lum) * saturation),
                    clamp(lum + (b - lum) * saturation));
        }
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
    }

    /** One box blur pass along rows (horizontal) or columns, edges clamped. */
    private static int[] boxBlur(int[] src, int w, int h, int radius, boolean horizontal) {
        int[] dst = new int[src.length];
        int lines = horizontal ? h : w, length = horizontal ? w : h, span = 2 * radius + 1;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < length; i++) {
                int r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int j = Math.max(0, Math.min(length - 1, i + k));
                    int p = horizontal ? src[line * w + j] : src[j * w + line];
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                }
                dst[horizontal ? line * w + i : i * w + line] = Color.rgb(r / span, g / span, b / span);
            }
        }
        return dst;
    }
}
