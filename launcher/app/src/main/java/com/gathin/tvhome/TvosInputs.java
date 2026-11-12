package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvInputManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * design_language tvos27: the Inputs picker as a Control Center sub-panel, the way tvOS shows its audio output and
 * AirPlay pickers. A Liquid Glass platter in Control Center's place at the top right, over the same soft dim, with
 * "Inputs" on top and one row per input: a glyph in a round well, the device's name (or the port's), the port and
 * its state under it, and a checkmark on the input last switched to from here. The focused row fills white with
 * dark text and lifts slightly. MainActivity.showInputs() still decides which inputs are listed and what each opens;
 * this only draws them.
 */
final class TvosInputs {
    /** "Apple TV · HDMI 1": the name, then the port. */
    private static final String DOT = " \u00B7 ";
    private static final String PREFS = "tvos_inputs", CURRENT = "current";
    private static final float WIDTH = 620, PAD = 26, ROW_H = 92, ROW_RADIUS = 24, ROW_PAD = 20, WELL = 56,
            SLIDE = 64;

    private final Activity a;
    private final TvFocus.Spec spec;
    private final boolean fromControlCenter;
    private final Runnable backToControlCenter;
    private final Dialog dialog;
    private final View scrim;
    private final LinearLayout panel;
    final LinearLayout rows;
    private final String current;
    private boolean leaving;
    private View checkedRow;

    /**
     * {@code fromControlCenter}: opened from its Inputs tile, so the dim is already up and Back returns there
     * ({@code backToControlCenter}) instead of to Home.
     */
    TvosInputs(Activity activity, TvFocus.Spec spec, boolean fromControlCenter, Runnable backToControlCenter,
               Runnable dismissed) {
        a = activity;
        this.spec = spec;
        this.fromControlCenter = fromControlCenter;
        this.backToControlCenter = backToControlCenter;
        current = prefs(a).getString(CURRENT, null);
        boolean rtl = a.getResources().getBoolean(R.bool.rtl_menu_bar);

        FrameLayout root = new FrameLayout(a);
        root.setOnClickListener(v -> close());
        scrim = new View(a);
        scrim.setBackground(new GradientDrawable(rtl ? GradientDrawable.Orientation.RIGHT_LEFT
                : GradientDrawable.Orientation.LEFT_RIGHT, new int[]{TvDesign.scrim(0x33), TvDesign.scrim(0x66), TvDesign.scrim(0xB3)}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(LiquidGlass.platter(a));
        panel.setPadding(px(PAD), px(28), px(PAD), px(PAD - 8));
        panel.setClipChildren(false);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(px(WIDTH), -2,
                Gravity.TOP | (rtl ? Gravity.LEFT : Gravity.RIGHT));
        at.topMargin = px(TvDesign.Layout.SAFE_TOP);
        at.bottomMargin = px(TvDesign.Layout.SAFE_BOTTOM);
        at.leftMargin = at.rightMargin = px(TvDesign.Layout.SAFE_SIDE);
        root.addView(panel, at);

        TextView title = new TextView(a);
        title.setText(R.string.inputs);
        TvDesign.type(title, TvDesign.Type.HEADLINE);
        title.setTextColor(TvDesign.Color.LABEL);
        title.setIncludeFontPadding(false);
        title.setPaddingRelative(px(10), 0, 0, 0);
        panel.addView(title, new LinearLayout.LayoutParams(-2, -2));

        // The rows scroll inside the panel once they outgrow the screen; the padding leaves room for a focused row's
        // shadow.
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, px(10), 0, px(10));
        rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        scroll.addView(rows, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams list = new LinearLayout.LayoutParams(-1, -2);
        list.topMargin = px(10);
        panel.addView(scroll, list);

        dialog = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setWindowAnimations(0);
        }
        NavSound.watch(root);
        dialog.setOnKeyListener((d, code, e) -> {
            NavSound.key(e, dialog.getCurrentFocus());
            if (code != KeyEvent.KEYCODE_BACK) return false;
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) back();
            return true;
        });
        dialog.setOnDismissListener(d -> dismissed.run());
    }

    /**
     * One input. A "Name · Port" label is split into the name and, under it, the port; the state (a
     * TvInputManager INPUT_STATE_, or -1 for none) follows the port.
     */
    View row(int icon, String label, int state, Runnable action) {
        String name = label, detail = null;
        int dot = label.lastIndexOf(DOT);
        if (dot > 0) {
            name = label.substring(0, dot);
            detail = label.substring(dot + DOT.length());
        }
        String status = state == TvInputManager.INPUT_STATE_CONNECTED ? a.getString(R.string.connected)
                : state == TvInputManager.INPUT_STATE_DISCONNECTED ? a.getString(R.string.not_connected) : null;
        if (status != null) detail = detail == null ? status : detail + DOT + status;
        View row = build(icon, name, detail, label.equals(current));
        row.setOnClickListener(v -> {
            prefs(a).edit().putString(CURRENT, label).apply();
            dialog.dismiss();
            action.run();
        });
        rows.addView(row);
        return row;
    }

    /** "More inputs": shows the folded rows in place and moves focus to the first of them. */
    void more(List<View> folded) {
        View more = build(R.drawable.ic_input, a.getString(R.string.more_inputs), null, false);
        more.setOnClickListener(v -> {
            for (View f : folded) f.setVisibility(View.VISIBLE);
            rows.removeView(more);
            for (View f : folded) {
                if (f.isFocusable()) { f.requestFocus(); break; }
            }
        });
        rows.addView(more);
    }

    /** A hairline between the HDMI ports and the rest, and before Live TV, inset to the names. */
    View separator() {
        View line = new View(a);
        line.setBackgroundColor(TvDesign.Color.QUATERNARY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, px(1.5f)));
        lp.setMargins(px(ROW_PAD + WELL + 18), px(10), px(ROW_PAD), px(10));
        rows.addView(line, lp);
        return line;
    }

    private View build(int icon, String name, String detail, boolean checked) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(px(ROW_PAD), 0, px(ROW_PAD + 4), 0);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(background(px(ROW_RADIUS), TvDesign.Color.FILL, TvDesign.Glass.FOCUSED_FILL));
        row.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        row.setContentDescription(detail == null ? name : name + ", " + detail);
        ColorStateList ink = states(TvDesign.Glass.FOCUSED_TEXT, TvDesign.Color.LABEL);

        ImageView glyph = new ImageView(a);
        glyph.setImageResource(icon);
        glyph.setImageTintList(ink);
        glyph.setDuplicateParentStateEnabled(true);
        int gp = px(14);
        glyph.setPadding(gp, gp, gp, gp);
        GradientDrawable on = new GradientDrawable(), off = new GradientDrawable();
        on.setShape(GradientDrawable.OVAL);
        on.setColor(0x1A000000);
        off.setShape(GradientDrawable.OVAL);
        off.setColor(TvDesign.Color.WELL);
        StateListDrawable well = new StateListDrawable();
        well.addState(new int[]{android.R.attr.state_focused}, on);
        well.addState(new int[]{}, off);
        glyph.setBackground(well);
        row.addView(glyph, new LinearLayout.LayoutParams(px(WELL), px(WELL)));

        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setDuplicateParentStateEnabled(true);
        words.addView(text(name, TvDesign.Type.BODY, ink), new LinearLayout.LayoutParams(-2, -2));
        if (detail != null) {
            TextView sub = text(detail, TvDesign.Type.CAPTION2, states(TvDesign.Glass.FOCUSED_SECONDARY, TvDesign.Color.SECONDARY));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.topMargin = px(4);
            words.addView(sub, sp);
        }
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
        wp.setMarginStart(px(18));
        row.addView(words, wp);

        ImageView check = new ImageView(a);
        check.setImageResource(R.drawable.ic_check);
        check.setImageTintList(ink);
        check.setDuplicateParentStateEnabled(true);
        check.setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(px(34), px(34));
        cp.setMarginStart(px(14));
        row.addView(check, cp);
        if (checked) checkedRow = row;
        row.setSelected(checked);

        row.setOnFocusChangeListener((v, f) -> lift(v, f ? px(18) : 0f, false));
        row.setOnKeyListener((v, code, e) -> {
            boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (select && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) lift(v, px(6), true);
            else if (select && e.getAction() == KeyEvent.ACTION_UP) lift(v, v.hasFocus() ? px(18) : 0f, true);
            // The list is one column: sideways goes nowhere.
            return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(ROW_H));
        lp.topMargin = rows.getChildCount() == 0 ? 0 : px(8);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView text(String s, TvDesign.Type style, ColorStateList color) {
        TextView t = new TextView(a);
        t.setText(s);
        TvDesign.type(t, style);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setDuplicateParentStateEnabled(true);
        return t;
    }

    /**
     * Raises a row onto a shadow {@code z} deep. Rows never scale: the focused row keeps the same size, corners and
     * insets as the rest, and only its fill and shadow change. Select sinks it onto a smaller shadow.
     */
    private void lift(View v, float z, boolean quick) {
        v.animate().cancel();
        if (!spec.animations) {
            v.setTranslationZ(z);
            return;
        }
        int ms = quick ? TvDesign.Motion.PRESS_MS : z > 0 ? TvDesign.Motion.FOCUS_MS : TvDesign.Motion.UNFOCUS_MS;
        v.animate().translationZ(z).setDuration(TvMotion.ms(ms, spec.reduced)).setInterpolator(TvMotion.easeOut()).start();
    }

    /** Shows the panel with the checked row (or else the first) focused, sliding in from the edge. */
    void show() {
        dialog.show();
        if (checkedRow == null || checkedRow.getVisibility() != View.VISIBLE || !checkedRow.requestFocus()) {
            for (int i = 0; i < rows.getChildCount(); i++) {
                View c = rows.getChildAt(i);
                if (c.getVisibility() == View.VISIBLE && c.isFocusable() && c.requestFocus()) break;
            }
        }
        if (!spec.animations) return;
        int ms = TvMotion.ms(TvDesign.Motion.ENTER_MS, spec.reduced);
        boolean rtl = panel.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        // From Control Center the dim is already there and the panel takes its place, so it only settles in.
        panel.setTranslationX(spec.reduced ? 0f : px(fromControlCenter ? SLIDE / 3 : SLIDE) * (rtl ? -1 : 1));
        panel.setAlpha(0f);
        panel.animate().translationX(0f).alpha(1f).setDuration(ms).setInterpolator(TvMotion.easeOut()).start();
        if (!fromControlCenter) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(ms).setInterpolator(TvMotion.easeOut()).start();
        }
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    /** Back: to Control Center when it came from there, else slide out to Home. */
    private void back() {
        if (fromControlCenter && backToControlCenter != null) {
            leaving = true;
            backToControlCenter.run();
            return;
        }
        close();
    }

    /** Slides the panel out to the edge, then goes. */
    void close() {
        if (leaving || !dialog.isShowing()) return;
        leaving = true;
        if (!spec.animations) {
            dialog.dismiss();
            return;
        }
        int ms = TvMotion.ms(TvDesign.Motion.EXIT_MS, spec.reduced);
        boolean rtl = panel.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        scrim.animate().alpha(0f).setDuration(ms).setInterpolator(TvMotion.easeInOut()).start();
        panel.animate().translationX(spec.reduced ? 0f : px(SLIDE) * (rtl ? -1 : 1)).alpha(0f).setDuration(ms)
                .setInterpolator(TvMotion.easeInOut()).withEndAction(this::dismiss).start();
    }

    /** Gone at once: Home paused, or Control Center about to take its place. */
    void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    /** The label of the input last switched to from this panel, for Control Center's Inputs tile. */
    static String current(Context ctx) {
        return prefs(ctx).getString(CURRENT, null);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static ColorStateList states(int focused, int rest) {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{focused, rest});
    }

    private static StateListDrawable background(float r, int rest, int focused) {
        GradientDrawable on = new GradientDrawable(), off = new GradientDrawable();
        on.setColor(focused);
        on.setCornerRadius(r);
        off.setColor(rest);
        off.setCornerRadius(r);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        return s;
    }

    private int px(float pt) {
        return Math.round(TvDesign.px(a, pt));
    }
}
