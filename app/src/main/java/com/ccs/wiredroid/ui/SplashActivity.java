package com.ccs.wiredroid.ui;

import com.ccs.wiredroid.BuildConfig;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;
import com.ccs.wiredroid.util.FullScreenHelper;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Launcher screen. Holds for a fixed interval, then either shows the one-time
 * Java licence notice or hands over to {@link WelcomeActivity}.
 *
 * <p>The notice is a gate, not a banner: it cannot be dismissed by tapping
 * outside or by Back, because "accepted" is recorded permanently and a consent
 * the user never actually made should not be recorded. Declining closes the app
 * rather than continuing in a degraded mode.</p>
 */
public class SplashActivity extends AppCompatActivity {

    /**
     * How long the splash is held before moving on.
     *
     * <p>Long enough that the logo registers rather than flickering past, short
     * enough that it is not felt as waiting. This was three seconds, which is
     * three seconds of doing nothing on every single launch — by far the largest
     * single number in how long the app took to become usable. Raise it back if
     * the longer brand moment is worth it.</p>
     */
    private static final long SPLASH_MILLIS = 600L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable proceed = this::afterSplash;

    private AppPreferences prefs;
    private AppTheme theme;

    /** Set while the licence dialog is up, so a resume does not queue a second one. */
    private AlertDialog licenceDialog;
    /** Guards against the timer firing twice across a configuration change. */
    private boolean handedOver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        FullScreenHelper.enable(this);
        setContentView(buildLayout());

        ui.postDelayed(proceed, SPLASH_MILLIS);
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(theme.bg);
        int pad = dp(32);
        root.setPadding(pad, pad, pad, pad);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams logoLp =
                new LinearLayout.LayoutParams(dp(96), dp(96));
        logo.setLayoutParams(logoLp);
        root.addView(logo);

        TextView name = new TextView(this);
        name.setText(R.string.app_name);
        name.setTextColor(theme.text);
        name.setTextSize(28);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(20), 0, 0);
        root.addView(name);

        TextView version = new TextView(this);
        version.setText(getString(R.string.splash_version, BuildConfig.VERSION_NAME));
        version.setTextColor(theme.textDim);
        version.setTextSize(14);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, dp(6), 0, 0);
        root.addView(version);

        // Sits with the version rather than down in the footnote: the licence is
        // something a user should be told plainly, not something they find in
        // small print under a spinner.
        TextView openSource = new TextView(this);
        openSource.setText(R.string.splash_open_source);
        openSource.setTextColor(theme.textDim);
        openSource.setTextSize(12);
        openSource.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams osLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        osLp.topMargin = dp(10);
        openSource.setLayoutParams(osLp);
        root.addView(openSource);

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams spinnerLp =
                new LinearLayout.LayoutParams(dp(28), dp(28));
        spinnerLp.topMargin = dp(36);
        spinner.setLayoutParams(spinnerLp);
        root.addView(spinner);

        TextView footer = new TextView(this);
        footer.setText(R.string.splash_java_footnote);
        footer.setTextColor(Colors.blend(theme.bg, theme.textDim, 0.75f));
        footer.setTextSize(11);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin = dp(40);
        footer.setLayoutParams(footerLp);
        root.addView(footer);

        return root;
    }

    // ── Flow ─────────────────────────────────────────────────────────────────

    private void afterSplash() {
        if (!isFinishing() && !isDestroyed()) openWelcome();
    }

    private void showLicenceGate() {
        if (licenceDialog != null && licenceDialog.isShowing()) return;

        StringBuilder body = new StringBuilder();
        body.append(getString(R.string.licence_intro)).append("\n\n");
        for (int res : new int[]{
                R.string.credits_java_trademark,
                R.string.credits_java_no_jdk,
                R.string.credits_java_android_libs,
                R.string.credits_java_target_meaning,
                R.string.credits_java_build_files}) {
            body.append("•  ").append(getString(res)).append("\n\n");
        }
        body.append(getString(R.string.licence_confirm_question));

        licenceDialog = Dialogs.rounded(this)
                .setTitle(R.string.licence_title)
                .setMessage(body.toString().trim())
                .setCancelable(false)
                .setPositiveButton(R.string.licence_accept, (d, w) -> {
                    // commit(), not apply(): the transition happens immediately after.
                    prefs.setJavaLicenceAccepted(true);
                    openWelcome();
                })
                .setNegativeButton(R.string.licence_decline, (d, w) -> finishAffinity())
                .setNeutralButton(R.string.licence_view_credits, null)
                .show();

        // Wired after show() so that opening Credits does not dismiss the gate —
        // the default button behaviour would close the dialog and leave the user
        // on a splash screen with nothing to do.
        licenceDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                startActivity(new Intent(this, CreditsActivity.class)));
    }

    private void openWelcome() {
        if (handedOver) return;
        handedOver = true;
        startActivity(new Intent(this, WelcomeActivity.class));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        // Backing out of the splash must not skip the gate. Leave the app instead.
        finishAffinity();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(proceed);
        if (licenceDialog != null && licenceDialog.isShowing()) {
            licenceDialog.dismiss();
        }
        licenceDialog = null;
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
