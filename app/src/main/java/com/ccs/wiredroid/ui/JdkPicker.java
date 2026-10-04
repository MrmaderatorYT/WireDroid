package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.project.ProjectJdk;
import com.ccs.wiredroid.tools.compilers.JavaVersions;
import com.ccs.wiredroid.util.AppTheme;

import java.io.File;
import java.util.List;

/**
 * Release chooser for a project's target JDK.
 *
 * <p>Built from {@link JavaVersions#all()} with the same labels the global
 * setting uses, so the creation dialog and Project Structure offer one list
 * rather than two that can disagree. Levels the bundled toolchain cannot emit
 * stay in the list — a project may legitimately declare one — and say what they
 * actually compile at, so the choice never lies.</p>
 */
public final class JdkPicker {

    private final Context context;
    private final String[] codes;
    private final Spinner spinner;
    private final LinearLayout view;

    public JdkPicker(Context context, AppTheme theme, String selectedCode) {
        this.context = context;

        List<JavaVersions.Release> releases = JavaVersions.all();
        codes = new String[releases.size()];
        String[] labels = new String[releases.size()];
        for (int i = 0; i < releases.size(); i++) {
            JavaVersions.Release release = releases.get(i);
            codes[i] = release.code;
            JavaVersions.Release actual = JavaVersions.byCode(JavaVersions.effective(release.code));
            labels[i] = release.isCompilable()
                    ? release.label
                    : context.getString(R.string.settings_java_target_maps_to, release.label,
                            actual == null ? JavaVersions.effective(release.code) : actual.label);
        }

        spinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context,
                android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView item = (TextView) super.getView(position, convertView, parent);
                item.setTextColor(theme.text);
                return item;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setContentDescription(context.getString(R.string.a11y_settings_java_target));
        setSelectedCode(selectedCode);

        TextView label = new TextView(context);
        label.setText(R.string.jdk_picker_label);
        label.setTextColor(theme.textDim);
        label.setTextSize(12);

        TextView hint = new TextView(context);
        hint.setText(context.getString(R.string.jdk_picker_hint,
                JavaVersions.MIN_COMPILABLE, JavaVersions.MAX_COMPILABLE));
        hint.setTextColor(theme.textDim);
        hint.setTextSize(11);

        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        view.setLayoutParams(lp);
        view.addView(label);
        view.addView(spinner);
        view.addView(hint);
    }

    /** Label, spinner and hint as one block, ready to drop into a dialog or panel. */
    public View getView() {
        return view;
    }

    /** The selected release in the spelling {@code JavaVersions} stores. */
    public String selectedCode() {
        int position = spinner.getSelectedItemPosition();
        if (position < 0 || position >= codes.length) return JavaVersions.MIN_COMPILABLE;
        return codes[position];
    }

    public void setSelectedCode(String code) {
        spinner.setSelection(indexOf(JavaVersions.normalize(code)));
    }

    /**
     * Asks for a release and applies it to {@code projectRoot}. This is the
     * entry point Project Structure uses; nothing else should write the level.
     *
     * @param onApplied run after a successful change, or {@code null}
     */
    public static void showDialog(Activity activity, AppTheme theme, File projectRoot,
                                  Runnable onApplied) {
        if (activity == null || projectRoot == null) return;

        JdkPicker picker = new JdkPicker(activity, theme, ProjectJdk.resolve(activity, projectRoot));
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.addView(picker.getView());

        Dialogs.rounded(activity)
                .setTitle(R.string.jdk_dialog_title)
                .setView(box)
                .setPositiveButton(R.string.dialog_apply, (dialog, which) -> {
                    String code = picker.selectedCode();
                    boolean written = ProjectJdk.set(activity, projectRoot, code);
                    JavaVersions.Release release = JavaVersions.byCode(code);
                    String name = release == null ? code : release.label;
                    Toast.makeText(activity,
                            activity.getString(written
                                    ? R.string.jdk_applied
                                    : R.string.jdk_applied_no_build_file, name),
                            Toast.LENGTH_SHORT).show();
                    if (onApplied != null) onApplied.run();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private int indexOf(String code) {
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(code)) return i;
        }
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(JavaVersions.MIN_COMPILABLE)) return i;
        }
        return 0;
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
