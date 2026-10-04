package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;

import java.util.ArrayList;
import java.util.List;

/**
 * The menu bar across the top of the editor: File, Edit, View, and the rest.
 *
 * <p>The app used to keep every action that had no toolbar button behind a
 * single ⋮, which is a fine place to search for something by name and a poor
 * place to discover what exists. A desktop IDE answers this with named menus,
 * and so does this: each title opens an {@link AnchoredMenu} beneath it.</p>
 *
 * <p>The bar scrolls sideways. Twelve menus do not fit across a phone, and the
 * alternatives — dropping menus, or shortening the names to two letters — both
 * cost more than a scroll does.</p>
 *
 * <p>It holds no logic of its own: every entry names an action that
 * {@link Callback#executeMenuAction} already knows, which is the same route the
 * command palette takes.</p>
 */
public final class EditorMenuBar {

    public interface Callback {
        AppTheme getTheme();
        void executeMenuAction(String action);
        /** Label for the build menu, which follows the project's build system. */
        String getMavenPanelLabel();
        /** True while a program is running, so Run reads as Stop. */
        boolean isRunning();
    }

    /** One entry in a menu: a title to show and an action name to run. */
    private static final class Item {
        final int titleRes;
        final String literal;
        final String action;
        final boolean separatorBefore;

        Item(int titleRes, String literal, String action, boolean separatorBefore) {
            this.titleRes = titleRes;
            this.literal = literal;
            this.action = action;
            this.separatorBefore = separatorBefore;
        }
    }

    private static final class Menu {
        final int titleRes;
        final List<Item> items = new ArrayList<>();

        Menu(int titleRes) {
            this.titleRes = titleRes;
        }

        Menu add(int titleRes, String action) {
            items.add(new Item(titleRes, null, action, false));
            return this;
        }

        Menu add(String literal, String action) {
            items.add(new Item(0, literal, action, false));
            return this;
        }

        /** Starts a group; the separator is drawn before this entry. */
        Menu group(int titleRes, String action) {
            items.add(new Item(titleRes, null, action, true));
            return this;
        }

        Menu group(String literal, String action) {
            items.add(new Item(0, literal, action, true));
            return this;
        }
    }

    private final Activity activity;
    private final Callback callback;
    private final List<Menu> menus = new ArrayList<>();
    private LinearLayout row;

    public EditorMenuBar(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        buildModel();
    }

    /**
     * The menus, in the order a desktop IDE puts them.
     *
     * <p>Grouped by what the user is doing rather than by which class implements
     * it: everything that produces or moves a file is under File, everything
     * that changes the text in place is under Code, and so on.</p>
     */
    private void buildModel() {
        menus.add(new Menu(R.string.menubar_file)
                .add(R.string.menu_new_file, "new_file")


                .add(R.string.menubar_new_project, "new_project")
                .group(R.string.menu_open_file, "open_file")
                .add(R.string.menu_import_files, "import_files")
                .group(R.string.menu_save, "save")
                .add(R.string.menubar_save_as, "save_as")
                .group("Налаштування схеми", "project_structure")
                .add(R.string.local_history_title, "local_history")
                .group(R.string.menu_share_file, "share_file")
                .add(R.string.menu_pastebin, "pastebin")
                .add(R.string.menu_bt_share, "bt_share")
                .add(R.string.menu_export_project, "export_project"));

        menus.add(new Menu(R.string.menubar_edit)
                .add(R.string.menu_undo, "undo")
                .add(R.string.menu_redo, "redo")
                .group(R.string.menu_find_replace, "find")
                .add(R.string.menu_search_everywhere, "search_everywhere")
                .add(R.string.semantic_search_title, "semantic_search")
                .group(R.string.a11y_action_voice, "voice_input")
                .group(R.string.dialog_line_separator, "line_separator")
                .add(R.string.dialog_select_encoding, "encoding"));

        menus.add(new Menu(R.string.menubar_view)
                .add(R.string.menu_split_screen, "split_screen")
                .add(R.string.zen_mode_menu, "zen_mode")
                .group(R.string.menubar_panel_run, "run_panel")
                .add(R.string.menubar_panel_problems, "problems")
                .add(R.string.menubar_panel_todo, "todo_panel")



                .group(R.string.menu_view_formatted, "view_formatted")
                .add(R.string.menubar_webview_preview, "webview_preview")
                .add(R.string.menu_play_media, "play_media"));

        menus.add(new Menu(R.string.menubar_navigate)
                .add(R.string.menu_search_everywhere, "search_everywhere")

                .add(R.string.menu_project_map, "project_map")
                .group(R.string.menubar_toggle_bookmark, "toggle_bookmark")
                .add(R.string.menubar_show_bookmarks, "show_bookmarks"));

        menus.add(new Menu(R.string.menubar_code)
                .add(R.string.menu_format_code, "format")

                .group(R.string.action_duplicate_line, "duplicate_line")
                .add(R.string.action_move_line_up, "move_line_up")
                .add(R.string.action_move_line_down, "move_line_down")
                .add(R.string.action_toggle_comment, "toggle_comment")
                .group(R.string.action_fold_toggle, "fold_toggle")
                .add(R.string.action_fold_all, "fold_all")
                .add(R.string.action_unfold_all, "unfold_all")
                );

        menus.add(new Menu(R.string.menubar_build)
                .add("Схема й контакти", "wire_workspace")
                .add("Компілювати й запустити C/C++", "run"));
        menus.add(new Menu(R.string.menubar_run)
                .add("Запустити схему", "run")
                .add("Відкрити схему", "wire_workspace"));

        menus.add(new Menu(R.string.menubar_tools)
                .add(R.string.regex_tester_title, "regex_tester")
                .add(R.string.base64_title, "base64_encoder")
                .add(R.string.menu_hash_calc, "hash_calc")
                .group(R.string.menu_http_client, "http_client")
                .add(R.string.menu_db_client, "db_client")
                .add(R.string.menu_ssl_certs, "ssl_certs")
                .add(R.string.menu_split_terminal, "split_terminal"));

        menus.add(new Menu(R.string.menubar_git)
                .add(R.string.menu_git, "git"));

        menus.add(new Menu(R.string.menubar_help)
                .add(R.string.menubar_command_palette, "command_palette")
                .add(R.string.menu_settings, "settings"));
    }

    /** Builds the bar; call once, then add the returned view to the layout. */
    public View createView() {
        AppTheme theme = callback.getTheme();
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        for (Menu menu : menus) {
            row.addView(titleView(menu, theme));
        }

        HorizontalScrollView scroller = new HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(row);
        scroller.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        applyTheme(theme, scroller);
        return scroller;
    }

    private TextView titleView(Menu menu, AppTheme theme) {
        TextView view = new TextView(activity);
        view.setText(menu.titleRes);
        view.setTextSize(13);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view.setTag(menu);
        view.setOnClickListener(v -> open(menu, v));
        return view;
    }

    private void open(Menu menu, View anchor) {
        AnchoredMenu popup = AnchoredMenu.with(activity, callback.getTheme()).minWidth(220);
        for (Item item : menu.items) {
            if (item.separatorBefore) popup.separator();
            String label = item.literal != null ? item.literal : activity.getString(item.titleRes);
            final String action = item.action;
            popup.item(label, () -> callback.executeMenuAction(action));
        }
        popup.showBelow(anchor);
    }

    /** Repaints on a theme change; safe before {@link #createView}. */
    public void applyTheme(AppTheme theme) {
        if (row == null) return;
        applyTheme(theme, (View) row.getParent());
    }

    private void applyTheme(AppTheme theme, View container) {
        if (theme == null || row == null) return;
        container.setBackgroundColor(theme.toolbar);
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            ((TextView) child).setTextColor(theme.text);
            child.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(Colors.blend(theme.toolbar, theme.accent, 0.35f)),
                    null, null));
        }
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
