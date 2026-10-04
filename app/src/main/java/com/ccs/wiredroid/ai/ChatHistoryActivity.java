package com.ccs.wiredroid.ai;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;

import java.util.List;

/** The list of saved assistant conversations. Returns the one that was picked. */
public class ChatHistoryActivity extends AppCompatActivity {

    public static final String EXTRA_CONVERSATION_ID = "conversation_id";

    private AppTheme theme;
    private ChatHistoryStore store;
    private LinearLayout list;

    public static void pick(Activity from, int requestCode) {
        from.startActivityForResult(new Intent(from, ChatHistoryActivity.class), requestCode);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppPreferences prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);
        super.onCreate(savedInstanceState);
        FullScreenHelper.enable(this);

        store = new ChatHistoryStore(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(theme.toolbar);
        bar.setPadding(dp(8), dp(8), dp(12), dp(8));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextColor(theme.text);
        back.setTextSize(18);
        back.setPadding(dp(8), dp(4), dp(8), dp(4));
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText(getString(R.string.ai_history_title));
        title.setTextColor(theme.text);
        title.setTextSize(16);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(title);
        root.addView(bar);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(24));

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.addView(list);
        root.addView(scroll);

        setContentView(root);
        rebuild();
    }

    private void rebuild() {
        list.removeAllViews();
        List<ChatHistoryStore.Conversation> saved = store.listConversations(200);
        if (saved.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.ai_history_empty));
            empty.setTextColor(theme.textDim);
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(48), dp(24), dp(48));
            list.addView(empty);
            return;
        }
        for (ChatHistoryStore.Conversation c : saved) {
            list.addView(rowFor(c));
        }
    }

    private View rowFor(ChatHistoryStore.Conversation c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setClickable(true);
        row.setFocusable(true);

        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        if (tv.resourceId != 0) row.setBackgroundResource(tv.resourceId);

        TextView title = new TextView(this);
        title.setText(c.title == null || c.title.isEmpty()
                ? getString(R.string.ai_history_untitled) : c.title);
        title.setTextColor(theme.text);
        title.setTextSize(14);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(title);

        TextView meta = new TextView(this);
        meta.setText(getString(R.string.ai_history_meta, c.messageCount,
                DateUtils.getRelativeTimeSpanString(c.updatedAt, System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS)));
        meta.setTextColor(theme.textDim);
        meta.setTextSize(11);
        meta.setPadding(0, dp(2), 0, 0);
        row.addView(meta);

        row.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_CONVERSATION_ID, c.id);
            setResult(RESULT_OK, result);
            finish();
        });
        // Deleting is destructive and the rows are close together, so it is behind
        // a long press with a confirmation rather than a button you can brush.
        row.setOnLongClickListener(v -> {
            com.ccs.wiredroid.ui.Dialogs.rounded(this)
                    .setTitle(R.string.ai_history_delete_title)
                    .setMessage(getString(R.string.ai_history_delete_message,
                            c.title == null || c.title.isEmpty()
                                    ? getString(R.string.ai_history_untitled) : c.title))
                    .setPositiveButton(R.string.ai_history_delete_confirm, (d, w) -> {
                        store.deleteConversation(c.id);
                        rebuild();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        return row;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
