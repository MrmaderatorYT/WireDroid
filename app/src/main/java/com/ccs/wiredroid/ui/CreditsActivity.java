package com.ccs.wiredroid.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Credits;
import com.ccs.wiredroid.util.FullScreenHelper;

/**
 * Acknowledgements: the libraries WireDroid ships, under which licence, and a
 * short section on what "Java" does and does not mean inside this app.
 *
 * <p>Built programmatically like the settings screen, so it picks up the
 * current theme without a second set of light/dark layouts.</p>
 */
public class CreditsActivity extends AppCompatActivity {

    private AppTheme theme;
    private LinearLayout content;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppPreferences prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        setContentView(buildRoot());
        FullScreenHelper.enable(this);
        setTitle(R.string.credits_title);
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        root.addView(buildToolbar());

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(scroll);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(32));
        scroll.addView(content);

        content.addView(buildHeader());
        // The library list is by far the longest part of the page and none of it
        // sits above the fold, so it is appended once the first frame is out.
        content.post(this::appendLibrarySections);

        return root;
    }

    private void appendLibrarySections() {
        if (isFinishing() || isDestroyed()) return;

        content.addView(sectionTitle(getString(R.string.credits_section_libraries)));
        content.addView(paragraph(getString(R.string.credits_libraries_intro)));
        for (Credits.Group group : Credits.groups()) {
            content.addView(groupTitle(getString(group.titleRes)));
            for (Credits.Entry entry : group.entries) {
                content.addView(buildEntry(entry));
            }
        }

        content.addView(sectionTitle("Fritzing · component artwork"));
        content.addView(paragraph("Fritzing contributors · Creative Commons Attribution-ShareAlike 3.0 Unported. Component graphics and descriptions: github.com/fritzing/fritzing-parts. Artwork is sanitized for the local WireDroid workspace. The supplied licence is included in wiredroid/FRITZING-LICENSE.txt."));

        content.addView(sectionTitle(getString(R.string.credits_section_thanks)));
        content.addView(paragraph(getString(R.string.credits_thanks_body)));
    }

    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(theme.toolbar);
        bar.setPadding(dp(8), dp(10), dp(16), dp(10));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextColor(theme.text);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        back.setPadding(dp(8), dp(2), dp(14), dp(2));
        back.setContentDescription(getString(R.string.a11y_back));
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText(R.string.credits_title);
        title.setTextColor(theme.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title);

        return bar;
    }

    /** App name and version, so a bug report can quote the exact build. */
    private View buildHeader() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(4));

        TextView name = new TextView(this);
        name.setText(R.string.app_name);
        name.setTextColor(theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(name);

        TextView version = new TextView(this);
        version.setText(versionLine());
        version.setTextColor(theme.textDim);
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(version);

        return box;
    }

    private String versionLine() {
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return getString(R.string.credits_version, info.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private View buildEntry(Credits.Entry entry) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));

        TextView heading = new TextView(this);
        heading.setText(entry.version == null || entry.version.isEmpty() || "—".equals(entry.version)
                ? entry.name
                : getString(R.string.credits_name_version, entry.name, entry.version));
        heading.setTextColor(theme.text);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(heading);

        TextView license = new TextView(this);
        license.setText(getString(R.string.credits_license, entry.license));
        license.setTextColor(theme.accent);
        license.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(license);

        TextView purpose = new TextView(this);
        purpose.setText(entry.purposeRes);
        purpose.setTextColor(theme.textDim);
        purpose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(purpose);

        if (entry.url != null) {
            TextView link = new TextView(this);
            link.setText(entry.url);
            link.setTextColor(theme.accent);
            link.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            link.setPadding(0, dp(2), 0, 0);
            link.setOnClickListener(v -> openUrl(entry.url));
            box.addView(link);
        }

        return box;
    }

    /**
     * Hands the URL to the system browser. Nothing is opened in-app: these are
     * third-party sites, and a WebView here would only blur whose page it is.
     */
    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.credits_no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    // ── small view builders ──────────────────────────────────────────────────

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(theme.accent);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setAllCaps(true);
        view.setLetterSpacing(0.08f);
        view.setPadding(0, dp(22), 0, dp(6));
        return view;
    }

    private TextView groupTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(theme.text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(14), 0, dp(2));
        return view;
    }

    private TextView paragraph(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(theme.textDim);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
