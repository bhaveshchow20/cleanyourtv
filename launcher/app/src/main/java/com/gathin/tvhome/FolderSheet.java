package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.List;

/**
 * design_language tvos27: an open folder, as tvOS shows one. Home dims, and a Liquid Glass panel zooms up with the
 * folder's name on top and its apps in rows of four under it. Selecting an app opens it. Up to the name and Select
 * renames the folder with the on-screen keyboard. Holding Select (or Menu) on an app offers to move it back to the
 * Home Screen. Back closes the folder. No blur and no new views per frame: one panel, a few tiles.
 */
final class FolderSheet {
    interface Host {
        void onFolderRenamed(String id, String name);
        /** The app left the folder for Home; the folder's apps as they are now come back from the adapter. */
        void onFolderAppRemoved(String id, String appKey);
        void onFolderClosed(String id);
    }

    private static final int COLUMNS = 4;

    private final Activity activity;
    private final AppAdapter adapter;
    private final String id;
    private final TvFocus.Spec spec;
    private final boolean animations, reduced;
    private final Host host;
    final PopupWindow popup;
    private final LinearLayout panel, grid;
    private final EditText title;
    private final String startName;
    private PopupWindow menu;

    FolderSheet(Activity activity, AppAdapter adapter, String id, String name, TvFocus.Spec spec,
                boolean animations, boolean reduced, Host host) {
        this.activity = activity;
        this.adapter = adapter;
        this.id = id;
        this.spec = spec;
        this.animations = animations;
        this.reduced = reduced;
        this.host = host;
        this.startName = name;

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(TvDesign.Color.DIM);
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackground(LiquidGlass.panel(activity));
        int pad = px(56);
        panel.setPadding(pad, px(40), pad, pad);
        panel.setClipToPadding(false);
        panel.setClipChildren(false);
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int panelW = Math.round(screenW * 0.7f);
        root.addView(panel, new FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        title = new EditText(activity);
        title.setText(name);
        TvDesign.type(title, TvDesign.Type.TITLE3);
        title.setTypeface(TvDesign.font(activity, TvDesign.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        title.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        title.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{TvDesign.Glass.FOCUSED_TEXT, TvDesign.Color.LABEL}));
        title.setBackground(titleBackground());
        title.setPadding(px(32), px(10), px(32), px(10));
        title.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) saveName();
            return false;
        });
        title.setOnFocusChangeListener((v, focused) -> {
            if (!focused) saveName();
        });
        title.setOnKeyListener((v, code, event) -> {
            // Up from the name and sideways have nowhere to go: stay put rather than leave the panel.
            return event.getAction() == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT);
        });
        panel.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = px(40);
        panel.addView(grid, glp);
        fill(panelW - 2 * pad);

        popup = new PopupWindow(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // Back dismisses
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            if (menu != null) menu.dismiss();
            saveName();
            host.onFolderClosed(id);
        });
        NavSound.watch(root);
    }

    void show() {
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        View root = popup.getContentView();
        if (animations) {
            int ms = TvMotion.ms(TvDesign.Motion.ENTER_MS, reduced);
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(ms).setInterpolator(TvMotion.easeOut()).start();
            panel.setScaleX(0.9f);
            panel.setScaleY(0.9f);
            panel.animate().scaleX(1f).scaleY(1f).setDuration(ms).setInterpolator(TvMotion.easeOut()).start();
        }
        focusFirst(0);
    }

    /** Lays the folder's apps out again (after one moved out); closes the folder once none are left. */
    void refresh() {
        int width = grid.getWidth();
        if (adapter.folderApps(id).isEmpty()) {
            popup.dismiss();
            return;
        }
        fill(width);
        focusFirst(0);
    }

    /** Focus on the name, for a new folder or "Rename Folder": Select brings up the keyboard. */
    void focusTitle() {
        grid.post(title::requestFocus);
    }

    private void focusFirst(int index) {
        grid.post(() -> {
            View tile = grid.findViewWithTag(index);
            if (tile == null) tile = grid.findViewWithTag(0);
            if (tile != null) tile.requestFocus();
        });
    }

    private void fill(int width) {
        grid.removeAllViews();
        List<AppAdapter.Entry> apps = adapter.folderApps(id);
        int gap = px(40);
        int tileW = (width - (COLUMNS - 1) * gap) / COLUMNS, tileH = Math.round(tileW * 9f / 16f);
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = px(28);
                grid.addView(row, rlp);
            }
            LinearLayout cell = new LinearLayout(activity);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % COLUMNS > 0) clp.leftMargin = gap;
            row.addView(cell, clp);
            cell.addView(tile(apps.get(i), i, i == apps.size() - 1, i < COLUMNS), new LinearLayout.LayoutParams(tileW, tileH));
            TextView name = new TextView(activity);
            name.setText(apps.get(i).label);
            TvDesign.type(name, TvDesign.Type.CAPTION1);
            name.setTypeface(TvDesign.font(activity, TvDesign.MEDIUM));
            name.setTextColor(TvDesign.Color.SECONDARY);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = px(16);
            cell.addView(name, nlp);
        }
    }

    private View tile(AppAdapter.Entry e, int index, boolean last, boolean firstRow) {
        FrameLayout card = new FrameLayout(activity);
        card.setTag(index);
        card.setFocusable(true);
        card.setClickable(true);
        card.setLongClickable(true);
        card.setContentDescription(e.label);
        float radius = TvDesign.px(activity, TvDesign.Radius.CARD);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        ImageView art = new ImageView(activity);
        if (e.banner != null) {
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageDrawable(e.banner);
            card.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{TvDesign.Color.CARD_TOP, TvDesign.Color.CARD_BOTTOM}));
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setImageDrawable(e.icon);
            card.addView(art, new FrameLayout.LayoutParams(px(96), px(96), Gravity.CENTER));
        }
        card.setOnFocusChangeListener((v, f) -> TvFocus.focus(v, f, spec));
        card.setOnClickListener(v -> {
            popup.dismiss();
            adapter.open(e);
        });
        card.setOnLongClickListener(v -> {
            showMenu(v, e);
            return true;
        });
        card.setOnKeyListener((v, code, event) -> {
            TvFocus.key(v, event, spec);
            if (code == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP) showMenu(v, e);
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_UP && firstRow) {
                title.requestFocus();
                return true;
            }
            // The panel's edges: focus stays inside it.
            return code == KeyEvent.KEYCODE_DPAD_RIGHT && (last || index % COLUMNS == COLUMNS - 1)
                    || code == KeyEvent.KEYCODE_DPAD_LEFT && index % COLUMNS == 0
                    || code == KeyEvent.KEYCODE_DPAD_DOWN && index + COLUMNS >= adapter.folderApps(id).size()
                    && index / COLUMNS == (adapter.folderApps(id).size() - 1) / COLUMNS;
        });
        return card;
    }

    /** Hold Select on an app: a small glass menu by it, "Move to Home Screen". */
    private void showMenu(View anchor, AppAdapter.Entry e) {
        if (menu != null) menu.dismiss();
        View content = activity.getLayoutInflater().inflate(R.layout.popup_inputs, null, false);
        content.setBackground(LiquidGlass.platter(activity));
        int pad = px(14);
        content.setPadding(pad, pad, pad, pad);
        LinearLayout rows = content.findViewById(R.id.rows);
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(R.string.tvos_folder_move_out);
        rows.addView(row);
        PopupWindow m = new PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        m.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        m.setAnimationStyle(0);
        m.setOnDismissListener(() -> {
            if (menu == m) menu = null;
        });
        // The Select-up that opened the menu must not choose its row: only a press that starts in the menu counts.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            if (pressed[0]) {
                pressed[0] = false;
                m.dismiss();
                host.onFolderAppRemoved(id, e.component.flattenToShortString());
            }
            return true;
        });
        menu = m;
        m.showAsDropDown(anchor, 0, px(12));
        row.requestFocus();
    }

    private void saveName() {
        String name = title.getText().toString().trim();
        if (name.isEmpty() || name.equals(startName)) return;
        host.onFolderRenamed(id, name);
    }

    /** The name sits on nothing until focused; then it is a white capsule, as tvOS's text fields. */
    private android.graphics.drawable.Drawable titleBackground() {
        GradientDrawable on = new GradientDrawable();
        on.setColor(TvDesign.Glass.FOCUSED_FILL);
        on.setCornerRadius(TvDesign.px(activity, 999));
        android.graphics.drawable.StateListDrawable d = new android.graphics.drawable.StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, on);
        d.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return d;
    }

    private int px(float pt) {
        return Math.round(TvDesign.px(activity, pt));
    }
}
