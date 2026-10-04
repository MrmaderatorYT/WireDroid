package com.ccs.wiredroid.ui;

import android.content.Context;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.appcompat.widget.PopupMenu;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Sorting and filtering for the Structure panel.
 *
 * <p>The panel used to show whatever {@link MemberOutline} handed back, in
 * source order, with no way to change it. This owns the little menu that says
 * otherwise, remembers the answer across launches, and reshapes the member list
 * on its way to {@link StructureAdapter}.</p>
 *
 * <p>Only the panel is affected. The breadcrumb dropdown keeps showing the raw
 * scan — a filter set for a side panel should not quietly remove entries from a
 * navigation control the user did not touch.</p>
 */
public final class StructureOptionsController {

    /** Raised after the user changes anything; the host should rebuild the list. */
    public interface Listener {
        void onStructureOptionsChanged();
    }

    private static final Comparator<MemberOutline.Member> BY_LABEL =
            (a, b) -> a.label.compareToIgnoreCase(b.label);

    private static final Comparator<MemberOutline.Member> BY_KIND = (a, b) -> {
        int d = a.kind.ordinal() - b.kind.ordinal();
        return d != 0 ? d : a.label.compareToIgnoreCase(b.label);
    };

    private static final Comparator<MemberOutline.Member> BY_VISIBILITY = (a, b) -> {
        int d = a.visibility.ordinal() - b.visibility.ordinal();
        return d != 0 ? d : a.label.compareToIgnoreCase(b.label);
    };

    private final Context context;
    private final AppPreferences prefs;
    private final TextView button;
    private final Listener listener;
    private AppTheme theme;

    public StructureOptionsController(Context context, AppPreferences prefs,
                                      TextView button, AppTheme theme, Listener listener) {
        this.context = context;
        this.prefs = prefs;
        this.button = button;
        this.theme = theme;
        this.listener = listener;
        if (button != null) {
            button.setOnClickListener(v -> showMenu());
            refreshTint();
        }
    }

    /** Reshapes a freshly scanned list. Never mutates the argument. */
    public List<MemberOutline.Member> apply(List<MemberOutline.Member> members) {
        List<MemberOutline.Member> out = new ArrayList<>();
        if (members == null || members.isEmpty()) return out;

        boolean showFields = prefs.isStructureShowFields();
        boolean showNonPublic = prefs.isStructureShowNonPublic();
        for (MemberOutline.Member m : members) {
            if (!showFields && m.kind == MemberOutline.Kind.FIELD) continue;
            if (!showNonPublic && m.visibility != MemberOutline.Visibility.PUBLIC) continue;
            out.add(m);
        }

        // The sorts are stable, so members that tie keep the order they were
        // declared in — which is what the eye expects inside a group.
        switch (prefs.getStructureSort()) {
            case AppPreferences.STRUCTURE_SORT_ALPHA:
                Collections.sort(out, BY_LABEL);
                break;
            case AppPreferences.STRUCTURE_SORT_KIND:
                Collections.sort(out, BY_KIND);
                break;
            case AppPreferences.STRUCTURE_SORT_VISIBILITY:
                Collections.sort(out, BY_VISIBILITY);
                break;
            default:
                break;
        }
        return out;
    }

    public void applyTheme(AppTheme theme) {
        this.theme = theme;
        refreshTint();
    }

    private void showMenu() {
        if (button == null) return;
        PopupMenu menu = new PopupMenu(context, button);
        menu.getMenuInflater().inflate(R.menu.menu_structure_options, menu.getMenu());

        int checkedId;
        switch (prefs.getStructureSort()) {
            case AppPreferences.STRUCTURE_SORT_ALPHA:
                checkedId = R.id.structure_sort_alpha;
                break;
            case AppPreferences.STRUCTURE_SORT_KIND:
                checkedId = R.id.structure_sort_kind;
                break;
            case AppPreferences.STRUCTURE_SORT_VISIBILITY:
                checkedId = R.id.structure_sort_visibility;
                break;
            default:
                checkedId = R.id.structure_sort_source;
                break;
        }
        MenuItem sorted = menu.getMenu().findItem(checkedId);
        if (sorted != null) sorted.setChecked(true);

        MenuItem fields = menu.getMenu().findItem(R.id.structure_show_fields);
        if (fields != null) fields.setChecked(prefs.isStructureShowFields());
        MenuItem nonPublic = menu.getMenu().findItem(R.id.structure_show_non_public);
        if (nonPublic != null) nonPublic.setChecked(prefs.isStructureShowNonPublic());

        menu.setOnMenuItemClickListener(this::onItemSelected);
        menu.show();
    }

    private boolean onItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.structure_sort_source) {
            prefs.setStructureSort(AppPreferences.STRUCTURE_SORT_SOURCE);
        } else if (id == R.id.structure_sort_alpha) {
            prefs.setStructureSort(AppPreferences.STRUCTURE_SORT_ALPHA);
        } else if (id == R.id.structure_sort_kind) {
            prefs.setStructureSort(AppPreferences.STRUCTURE_SORT_KIND);
        } else if (id == R.id.structure_sort_visibility) {
            prefs.setStructureSort(AppPreferences.STRUCTURE_SORT_VISIBILITY);
        } else if (id == R.id.structure_show_fields) {
            prefs.setStructureShowFields(!item.isChecked());
        } else if (id == R.id.structure_show_non_public) {
            prefs.setStructureShowNonPublic(!item.isChecked());
        } else {
            return false;
        }
        refreshTint();
        if (listener != null) listener.onStructureOptionsChanged();
        return true;
    }

    /**
     * A filtered panel can look like an empty one. Highlighting the button while
     * anything is hidden is the cheapest way to say "this is a filter, not a bug".
     */
    private void refreshTint() {
        if (button == null || theme == null) return;
        boolean filtering = !prefs.isStructureShowFields() || !prefs.isStructureShowNonPublic();
        button.setTextColor(filtering ? theme.accent : theme.textDim);
    }
}
