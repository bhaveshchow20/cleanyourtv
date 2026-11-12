package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * The tvOS 27 design tokens (config.xml / theme design_language "tvos27"), in Apple's points. tvOS lays out a
 * 1920x1080 screen at 1 pt = 1 px, so {@link #px} scales a point to the TV's real width; a 4K or 720p panel keeps
 * the same proportions. Sources and how each value was chosen: docs/themes/tvos27.md.
 *
 * The tokens are plain values, so they and the parsing are tested on the JVM; only {@link #px(Context, float)} and
 * {@link #type} touch a view.
 */
final class TvDesign {

    static final String DEFAULT = "default", TVOS27 = "tvos27";

    /** A known design_language, or default (the launcher's own look) for anything else. */
    static String language(String value) {
        return value != null && TVOS27.equalsIgnoreCase(value.trim()) ? TVOS27 : DEFAULT;
    }

    /** The width tvOS designs for: one point is one pixel on it. */
    static final float CANVAS_W = 1920f;

    /** Points to pixels on a screen {@code screenWidthPx} wide (landscape). */
    static float px(float pt, int screenWidthPx) {
        return pt * (screenWidthPx > 0 ? screenWidthPx : CANVAS_W) / CANVAS_W;
    }

    /**
     * The panel's own pixels, landscape, from the display mode: many Google TVs draw their UI at 1080p on a 4K panel,
     * so DisplayMetrics says 1920 where the panel is 3840.
     */
    static android.util.Size panel(Context ctx) {
        android.view.Display d = ((android.hardware.display.DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE))
                .getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if (d == null) {
            DisplayMetrics m = ctx.getResources().getDisplayMetrics();
            return new android.util.Size(Math.max(m.widthPixels, m.heightPixels), Math.min(m.widthPixels, m.heightPixels));
        }
        android.view.Display.Mode mode = d.getMode();
        return new android.util.Size(Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight()),
                Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight()));
    }

    /**
     * The background where the person has no picture of their own (Home's grid, the App library, Settings, the
     * screensaver): Color.BACKDROP_TOP fading to BACKDROP_BOTTOM, drawn in code, so no image ships.
     */
    static android.graphics.drawable.Drawable backdrop() {
        return new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.BACKDROP_TOP, Color.BACKDROP_BOTTOM});
    }

    /** The dark gradient whatever the appearance: the screensaver stays dark, as tvOS's aerials do. */
    static android.graphics.drawable.Drawable darkBackdrop() {
        return new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF1C1F26, 0xFF08090B});
    }

    /** Points to pixels on this TV: the long side of the display is the 1920 pt canvas. */
    static float px(Context ctx, float pt) {
        DisplayMetrics m = ctx.getResources().getDisplayMetrics();
        return px(pt, Math.max(m.widthPixels, m.heightPixels));
    }

    /** Sets a tvOS text style on {@code view}: its size, its leading, and Medium or Regular weight. */
    static void type(TextView view, Type style) {
        Context ctx = view.getContext();
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(ctx, style.size));
        view.setLineSpacing(px(ctx, style.leading - style.size), 1f);
        view.setTypeface(font(ctx, style.medium ? MEDIUM : REGULAR));
    }

    /** Inter weights: SF Pro's licence keeps it on Apple devices, so tvos27 draws in Inter (OFL), its closest free match. */
    static final int REGULAR = 0, MEDIUM = 1, SEMIBOLD = 2, BOLD = 3;
    private static final int[] FONTS = {
            R.font.inter_regular, R.font.inter_medium, R.font.inter_semibold, R.font.inter_bold};
    private static final Typeface[] cache = new Typeface[4];

    /** Inter at one of REGULAR..BOLD, built once per process. */
    static Typeface font(Context ctx, int weight) {
        Typeface t = cache[weight];
        if (t == null) {
            try {
                t = ctx.getResources().getFont(FONTS[weight]);
            } catch (RuntimeException e) {
                t = Typeface.create(weight >= SEMIBOLD ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
            }
            cache[weight] = t;
        }
        return t;
    }

    /** Inter for a view built from a layout's Outfit: the same weight band, Light and below becoming Regular. */
    static Typeface interFor(Context ctx, Typeface from) {
        int w = from == null ? 400 : android.os.Build.VERSION.SDK_INT >= 28 ? from.getWeight()
                : from.isBold() ? 700 : 400;
        if (from != null && from.isBold() && w < 600) w = 700;
        return font(ctx, w >= 650 ? BOLD : w >= 560 ? SEMIBOLD : w >= 460 ? MEDIUM : REGULAR);
    }

    /** Swaps every TextView under {@code v} to Inter, once, after a layout inflates in Outfit. */
    static void useInter(android.view.View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            t.setTypeface(interFor(v.getContext(), t.getTypeface()));
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) useInter(g.getChildAt(i));
        }
    }

    private TvDesign() {
    }

    /** HIG Typography, tvOS built-in text styles: size and leading in points, true for the Medium weight. */
    static final class Type {
        final float size, leading;
        final boolean medium;

        private Type(float size, float leading, boolean medium) {
            this.size = size;
            this.leading = leading;
            this.medium = medium;
        }

        static final Type TITLE1 = new Type(76, 96, true);
        static final Type TITLE2 = new Type(57, 66, true);
        static final Type TITLE3 = new Type(48, 56, true);
        static final Type HEADLINE = new Type(38, 46, true);
        static final Type SUBTITLE1 = new Type(38, 46, false);
        static final Type CALLOUT = new Type(31, 38, true);
        static final Type BODY = new Type(29, 36, true);
        static final Type CAPTION1 = new Type(25, 32, true);
        static final Type CAPTION2 = new Type(23, 30, true);
        /** The smallest text tvOS allows (HIG Typography, minimum size). */
        static final float MIN_SIZE = 23;
    }

    /** HIG Layout: the safe area and the spacing between focusable items. */
    static final class Layout {
        static final float SAFE_TOP = 60, SAFE_BOTTOM = 60, SAFE_SIDE = 80;
        /** Horizontal gap between unfocused items in a grid. */
        static final float GUTTER = 40;
        /** Clearance a focused card keeps from the dock's glass edge, beyond its own growth. */
        static final float DOCK_CLEARANCE = 8;

        /**
         * The dock's one spacing, in points, for {@code columns} across the safe area: the gap between tiles, and the
         * glass's padding on every side, as tvOS 26 spaces its dock. At least the gutter, and enough that a focused end
         * tile (grown by Focus.SCALE) still clears the glass by DOCK_CLEARANCE.
         */
        static float dockGap(int columns) {
            float cell = (CANVAS_W - 2 * SAFE_SIDE) / Math.max(1, columns);
            return Math.max(GUTTER, cell * (Focus.SCALE - 1f) / 2f + DOCK_CLEARANCE);
        }

        /**
         * The dock glass's padding round its tiles, in points: the gap plus a focused tile's growth, so a focused end
         * tile is still a full gap from the glass, as its neighbours are from each other. The tiles narrow to make room.
         */
        static float dockPad(int columns) {
            int c = Math.max(1, columns);
            float gap = dockGap(c), k = (Focus.SCALE - 1f) / 2f;
            float tile = ((CANVAS_W - 2 * SAFE_SIDE - gap) / c - gap) / (1f + 2f * k / c);
            return gap + tile * k;
        }
        /** Minimum vertical gap between rows, so a focused item has room to grow. */
        static final float ROW_GAP = 100;
        /** Unfocused item widths for 2 to 9 columns inside the safe area. */
        private static final float[] COLUMN_W = {860, 560, 410, 320, 260, 217, 184, 160};

        static float columnWidth(int columns) {
            int i = Math.max(2, Math.min(9, columns)) - 2;
            return COLUMN_W[i];
        }
    }

    /** Corner radii in points; a capsule's radius is half its height. Measured from tvOS 26 screenshots. */
    static final class Radius {
        /** App icons and content cards: tvOS 26 rounds the 400x240 icon by about 12% of its height. */
        static final float CARD = 28;
        /** Glass panels such as the media player's info panel and Control Center. */
        static final float PANEL = 44;
        /** The width CARD was measured on: tvOS 26's 400 pt wide app icon. */
        static final float CARD_W = 400;

        /** A card's corner on a tile {@code widthPx} wide: CARD in proportion, so smaller tiles keep the shape. */
        static float card(float widthPx) {
            return widthPx * CARD / CARD_W;
        }

        /** The dock's corner, concentric with its cards (HIG concentricity): the card's radius plus the padding. */
        static float dock(float cardWidthPx, float padPx) {
            return card(cardWidthPx) + padPx;
        }
        /** HIG Buttons: a regular button is 44 pt tall, large 52 pt, extra large 64 pt; all are capsules. */
        static final float BUTTON_H = 64;
    }

    /** The focus effect: scale, lift, shadow and parallax. */
    static final class Focus {
        /**
         * A focused card grows by about an eighth, as an icon on the tvOS Home screen does: on a TV seen from the
         * sofa a tenth read as too little to follow (Control Center's tiles stay flat, TvFocus.Spec.flat).
         */
        static final float SCALE = 1.12f;
        /** Select presses it back towards its resting size before it springs up again. */
        static final float PRESS_SCALE = 1.02f;
        /** It rises off the screen while focused. */
        static final float LIFT = 12;
        /** Elevation for its shadow: soft, wide and dark under the card; stronger in light, where white sits on white. */
        static float SHADOW = 40;
        /** Unfocused Home cards sit at this brightness, so the focused one, at full, stands out (AppAdapter). */
        static final float REST_BRIGHTNESS = 0.86f;
        /** The faint light rim round a focused Home card: alpha (0-255) and width in points. */
        static final int RIM_ALPHA = 0x59;
        static final float RIM_W = 2f;
        /** Largest parallax tilt, in degrees, when the remote nudges a focused card. */
        static final float TILT_DEG = 6f;
    }

    /**
     * Liquid Glass, without a blur (the launcher draws no blur on a 1 GB TV, AGENTS.md): a white tint over what is
     * behind, a brighter rim that fades down the edge, and a top sheen. Alphas as 0-255. Not final: {@link #applyAppearance}
     * swaps them between the dark and the light look.
     */
    static final class Glass {
        static int TINT_TOP = 0x33, TINT_BOTTOM = 0x1A;
        static int RIM_TOP = 0x66;
        /** The rim's bottom colour: a faint white in dark, a faint grey in light, which reads as a soft shadow edge. */
        static int RIM_BOTTOM_COLOR = 0x1FFFFFFF;
        static int SHEEN = 0x40;
        /** Rim width in points. */
        static final float RIM = 1.5f;
        /** A focused control fills in as a near-opaque white platter with dark text (pure white in light). */
        static int FOCUSED_FILL = 0xF5FFFFFF;
        static final int FOCUSED_TEXT = 0xFF1C1C1E;
        /** Secondary text on a focused platter. */
        static final int FOCUSED_SECONDARY = 0x991C1C1E;
        /** The opaque base under a platter that floats over video. */
        static int PLATTER_BASE = 0xF0101014;
    }

    /**
     * Label and surface colours for the current appearance: Apple's label, secondaryLabel, tertiaryLabel and
     * quaternaryLabel on black (dark) or on white (light). Not final: {@link #applyAppearance} swaps the palette.
     */
    static final class Color {
        static int LABEL = 0xFFFFFFFF, SECONDARY = 0x99EBEBF5, TERTIARY = 0x4DEBEBF5, QUATERNARY = 0x2EEBEBF5;
        static int BACKGROUND = 0xFF000000, ELEVATED = 0xFF1C1C1E;
        /** The default background with no wallpaper: near-black graphite in dark, a soft light grey in light. */
        static int BACKDROP_TOP = 0xFF1C1F26, BACKDROP_BOTTOM = 0xFF08090B;
        /** A resting row's or tile's faint fill, and a well (toggle off, empty artwork). */
        static int FILL = 0x14FFFFFF, WELL = 0x2EFFFFFF;
        /** A toggle's "on" well on a focused white platter. */
        static final int FOCUSED_WELL = 0x1A000000;
        /** The RGB a scrim or dim is made of (black in dark, light grey in light); alpha is the caller's. */
        static int SCRIM_RGB = 0x000000;
        /** A full-screen dim under an alert, a sheet or a folder. */
        static int DIM = 0xCC000000;
        /** A shadow under text and logos over imagery. */
        static int SHADOW = 0x59000000;
        /** A placeholder card's gradient (no artwork yet, a folder's tile). */
        static int CARD_TOP = 0xFF3A3D44, CARD_BOTTOM = 0xFF202227;
    }

    static final String DARK = "dark", LIGHT = "light", AUTO = "auto";

    /** True while the light palette is in use; the fields above start dark. */
    static boolean light;

    /** A known appearance, or dark for anything else. */
    static String appearance(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return LIGHT.equals(v) || AUTO.equals(v) ? v : DARK;
    }

    /**
     * Whether {@code appearance} means light now. Automatic follows the system when the TV says light outright
     * (UiModeManager night mode "no"); TVs report dark by default without asking anyone, so otherwise it is light
     * between sunrise and sunset, as tvOS's Automatic follows the day ({@link #daylight}).
     */
    static boolean wantsLight(Context ctx, String appearance) {
        if (LIGHT.equals(appearance)) return true;
        if (!AUTO.equals(appearance)) return false;
        android.app.UiModeManager ui = (android.app.UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
        if (ui != null && ui.getNightMode() == android.app.UiModeManager.MODE_NIGHT_NO) return true;
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        return daylight(now.toLocalDate(), now.getHour() * 60 + now.getMinute(),
                ctx.getString(R.string.latitude), ctx.getString(R.string.longitude), now.getZone());
    }

    /**
     * Is the sun up at {@code minute} of {@code day}? At config.xml's latitude and longitude when both are set, else
     * at the place the time zone points to (ZonePlace); a zone with no place (UTC) falls back to 07:00 to 19:00.
     * Under midnight sun it is light all day, in polar night dark.
     */
    static boolean daylight(java.time.LocalDate day, int minute, String latitude, String longitude,
                            java.time.ZoneId zone) {
        double[] at = null;
        try {
            if (latitude != null && longitude != null && !latitude.trim().isEmpty() && !longitude.trim().isEmpty()) {
                at = new double[]{Double.parseDouble(latitude.trim()), Double.parseDouble(longitude.trim())};
            }
        } catch (NumberFormatException ignored) {
            // an unreadable config value: the time zone decides
        }
        if (at == null) at = ZonePlace.of(zone.getId());
        if (at == null) return minute >= 7 * 60 && minute < 19 * 60;
        int[] sun = Sun.riseAndSet(day, at[0], at[1], zone);
        if (sun == null) {
            boolean northSummer = day.getMonthValue() >= 4 && day.getMonthValue() <= 9;
            return at[0] >= 0 == northSummer;
        }
        return minute >= sun[0] && minute < sun[1];
    }

    /** Swaps every colour token to {@code appearance} (dark, light or auto); true if that is the light palette. */
    static boolean applyAppearance(Context ctx, String appearance) {
        boolean l = wantsLight(ctx, appearance(appearance));
        if (l != light) palette(l);
        return l;
    }

    static void palette(boolean l) {
        light = l;
        if (l) {
            Color.LABEL = 0xFF000000;
            Color.SECONDARY = 0x993C3C43;
            Color.TERTIARY = 0x4D3C3C43;
            Color.QUATERNARY = 0x2E3C3C43;
            Color.BACKGROUND = 0xFFF2F2F7;
            Color.ELEVATED = 0xFFFFFFFF;
            Color.BACKDROP_TOP = 0xFFF2F2F7;
            Color.BACKDROP_BOTTOM = 0xFFD9DAE0;
            Color.FILL = 0x0F000000;
            Color.WELL = 0x1F000000;
            Color.SCRIM_RGB = 0xE5E5EA;
            Color.DIM = 0xB3E5E5EA;
            Color.SHADOW = 0x33000000;
            Color.CARD_TOP = 0xFFFFFFFF;
            Color.CARD_BOTTOM = 0xFFE5E5EA;
            // Frosted white: much more white than the dark look's tint, so dark text reads on it.
            Glass.TINT_TOP = 0xB8;
            Glass.TINT_BOTTOM = 0x8C;
            Glass.RIM_TOP = 0xF0;
            Glass.RIM_BOTTOM_COLOR = 0x26000000;
            Glass.SHEEN = 0x59;
            Glass.FOCUSED_FILL = 0xFFFFFFFF;
            Glass.PLATTER_BASE = 0xF2E5E5EA;   // a shade under white, so a focused white row stands out
            Focus.SHADOW = 52;
        } else {
            Color.LABEL = 0xFFFFFFFF;
            Color.SECONDARY = 0x99EBEBF5;
            Color.TERTIARY = 0x4DEBEBF5;
            Color.QUATERNARY = 0x2EEBEBF5;
            Color.BACKGROUND = 0xFF000000;
            Color.ELEVATED = 0xFF1C1C1E;
            Color.BACKDROP_TOP = 0xFF1C1F26;
            Color.BACKDROP_BOTTOM = 0xFF08090B;
            Color.FILL = 0x14FFFFFF;
            Color.WELL = 0x2EFFFFFF;
            Color.SCRIM_RGB = 0x000000;
            Color.DIM = 0xCC000000;
            Color.SHADOW = 0x59000000;
            Color.CARD_TOP = 0xFF3A3D44;
            Color.CARD_BOTTOM = 0xFF202227;
            Glass.TINT_TOP = 0x33;
            Glass.TINT_BOTTOM = 0x1A;
            Glass.RIM_TOP = 0x66;
            Glass.RIM_BOTTOM_COLOR = 0x1FFFFFFF;
            Glass.SHEEN = 0x40;
            Glass.FOCUSED_FILL = 0xF5FFFFFF;
            Glass.PLATTER_BASE = 0xF0101014;
            Focus.SHADOW = 40;
        }
    }

    /** {@link Color#SCRIM_RGB} at {@code alpha} (0-255). */
    static int scrim(int alpha) {
        return alpha << 24 | Color.SCRIM_RGB;
    }

    /** Durations in ms and spring constants for the motion every tvOS 27 screen shares. */
    static final class Motion {
        /** Focus in and out: a soft glide rather than a snap, eased at both ends (see FOCUS_EASE). */
        static final int FOCUS_MS = 260, UNFOCUS_MS = 240;
        /** Select: down fast, back up on the spring. */
        static final int PRESS_MS = 90;
        /** The parallax nudge on a D-pad press, and the top shelf's cross-fade between apps. */
        static final int NUDGE_MS = 260, SHELF_FADE_MS = 700;
        /** A screen or panel coming in and going out. */
        static final int ENTER_MS = 320, EXIT_MS = 220;
        /** Ease-out used for everything that arrives: cubic-bezier(0.2, 0.8, 0.2, 1). */
        static final float[] EASE_OUT = {0.2f, 0.8f, 0.2f, 1f};
        /** Focus growing and settling: cubic-bezier(0.25, 0.1, 0.25, 1), smooth at both ends so it glides. */
        static final float[] FOCUS_EASE = {0.25f, 0.1f, 0.25f, 1f};
        /** Ease-in-out for things that move across the screen: cubic-bezier(0.4, 0, 0.2, 1). */
        static final float[] EASE_IN_OUT = {0.4f, 0f, 0.2f, 1f};
        /** The focus spring: a little give, no visible bounce. */
        static final float SPRING_STIFFNESS = 500f, SPRING_DAMPING = 0.82f;
    }
}
