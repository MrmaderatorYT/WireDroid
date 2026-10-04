package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Controller for the quick action menu / searchable command palette (Ctrl+Shift+A).
 */
public final class SearchableMenuController {

    public interface Callback {
        AppTheme getTheme();
        String getMavenPanelLabel();
        void executeMenuAction(String action);
    }

    private final Activity activity;
    private final Callback callback;

    public SearchableMenuController(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public void show() {
        showSearchableMenu();
    }

    public void showSearchableMenu() {
        AppTheme theme = callback.getTheme();
        final String[][] menuItems = {
            {resStr("wire_workspace", "WireDroid circuit workspace"), "wire_workspace"},







            {resStr("menu_share_file", "Share File"), "share_file"},
            {resStr("menu_pastebin", "Pastebin"), "pastebin"},
            {resStr("menu_open_file", "Open File"), "open_file"},
            {resStr("menu_import_files", "Import Files"), "import_files"},
            {resStr("menu_format_code", "Format Code"), "format"},

            {resStr("menu_view_formatted", "View Formatted"), "view_formatted"},
            {resStr("menu_export_project", "Export Project"), "export_project"},
            {resStr("menu_split_screen", "Split Screen"), "split_screen"},
            {resStr("menu_play_media", "Play Media"), "play_media"},
            {"🎤 " + resStr("a11y_action_voice", "Voice Input"), "voice_input"},


            {resStr("regex_tester_title", "Regex Tester"), "regex_tester"},
            {resStr("base64_title", "Base64 Encoder"), "base64_encoder"},
            {resStr("menu_hash_calc", "Hash Calculator"), "hash_calc"},
            {resStr("menu_ssl_certs", "SSL / TLS Certificates"), "ssl_certs"},
            {resStr("menu_split_terminal", "Split Terminal"), "split_terminal"},
            {resStr("menu_db_client", "Database Client"), "db_client"},
            {resStr("menu_http_client", "HTTP Client"), "http_client"},
            {resStr("menu_bt_share", "Bluetooth Share"), "bt_share"},
            {resStr("menu_search_everywhere", "Search Everywhere"), "search_everywhere"},
            {resStr("menu_project_map", "Project Map"), "project_map"},

            {resStr("local_history_title", "Local History"), "local_history"},
            {resStr("zen_mode_menu", "Zen Mode (Fullscreen)"), "zen_mode"},


            {resStr("semantic_search_title", "Semantic Search (AI)"), "semantic_search"},
            {"Налаштування схеми", "project_structure"}
        };

        final List<String> filteredTitles = new ArrayList<>();
        final List<String> filteredActions = new ArrayList<>();
        for (String[] item : menuItems) {
            filteredTitles.add(item[0]);
            filteredActions.add(item[1]);
        }

        LinearLayout dialogRoot = new LinearLayout(activity);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        if (theme != null) {
            dialogRoot.setBackgroundColor(theme.bg);
        }
        int pad = dp(16);
        dialogRoot.setPadding(pad, pad, pad, 0);

        EditText searchField = new EditText(activity);
        searchField.setHint(R.string.actions_search_hint);
        if (theme != null) {
            searchField.setHintTextColor(theme.textDim);
            searchField.setTextColor(theme.text);
            GradientDrawable sBg = new GradientDrawable();
            sBg.setColor(Colors.blend(theme.consoleBg, theme.bg, 0.4f));
            sBg.setCornerRadius(dp(8));
            sBg.setStroke(dp(1), theme.separator);
            searchField.setBackground(sBg);
        }
        searchField.setPadding(dp(12), dp(10), dp(12), dp(10));
        searchField.setTextSize(14);
        searchField.setContentDescription(activity.getString(R.string.actions_search_hint));
        dialogRoot.addView(searchField);

        LinearLayout listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, dp(8), 0, dp(8));

        ScrollView sv = new ScrollView(activity);
        sv.addView(listContainer);
        dialogRoot.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));

        AlertDialog dialog = com.ccs.wiredroid.ui.Dialogs.rounded(activity)
                .setTitle(R.string.actions_title)
                .setView(dialogRoot)
                .setNegativeButton(R.string.dialog_cancel, null)
                .create();

        final Runnable[] rebuildList = {null};
        rebuildList[0] = () -> {
            listContainer.removeAllViews();
            String query = searchField.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < filteredTitles.size(); i++) {
                String title = filteredTitles.get(i);
                if (!query.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(query)) continue;

                TextView item = new TextView(activity);
                item.setText(title);
                if (theme != null) {
                    item.setTextColor(theme.text);
                    int rippleColor = (theme.accent & 0x00FFFFFF) | 0x22000000;
                    GradientDrawable normal = new GradientDrawable();
                    normal.setColor(Color.TRANSPARENT);
                    normal.setCornerRadius(dp(6));

                    GradientDrawable mask = new GradientDrawable();
                    mask.setColor(Color.WHITE);
                    mask.setCornerRadius(dp(6));

                    RippleDrawable rd = new RippleDrawable(
                            ColorStateList.valueOf(rippleColor),
                            normal,
                            mask);
                    item.setBackground(rd);
                } else {
                    item.setBackgroundResource(android.R.drawable.list_selector_background);
                }
                item.setTextSize(13.5f);
                item.setPadding(dp(12), dp(10), dp(12), dp(10));

                String action = filteredActions.get(i);
                item.setOnClickListener(v -> {
                    dialog.dismiss();
                    callback.executeMenuAction(action);
                });
                listContainer.addView(item);
            }
            if (listContainer.getChildCount() == 0) {
                TextView empty = new TextView(activity);
                empty.setText(R.string.actions_no_matching);
                if (theme != null) empty.setTextColor(theme.textDim);
                empty.setPadding(dp(12), dp(16), dp(12), 0);
                empty.setGravity(Gravity.CENTER);
                listContainer.addView(empty);
            }
        };

        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                rebuildList[0].run();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        rebuildList[0].run();
        dialog.show();
        Dialogs.style(dialog, theme);

        searchField.requestFocus();
        searchField.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    private String resStr(String name, String fallback) {
        int id = activity.getResources().getIdentifier(name, "string", activity.getPackageName());
        if (id != 0) {
            try {
                return activity.getString(id);
            } catch (Exception ignored) {}
        }
        return fallback;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
