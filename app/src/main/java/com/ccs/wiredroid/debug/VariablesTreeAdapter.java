package com.ccs.wiredroid.debug;
import com.ccs.wiredroid.R;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.wiredroid.util.AppTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Деревовидний адаптер Variables (як IntelliJ): локальні змінні розгортаються
 * у свої поля по тапу. Кожен вузол = {@link DebugVariable} + глибина.
 *
 * <p>Розкриття lazy: дочірні поля обчислюються через {@code var.getFields()}
 * лише при першому тапі на ▸.</p>
 */
public class VariablesTreeAdapter extends RecyclerView.Adapter<VariablesTreeAdapter.VH> {

    /** Вузол дерева. */
    private static final class Node {
        final DebugVariable var;
        final int depth;
        boolean expanded;
        List<Node> children; // null = ще не розкривали
        Node(DebugVariable var, int depth) { this.var = var; this.depth = depth; }

        boolean hasChildren() {
            if (children != null) return !children.isEmpty();
            // лінива оцінка: об'єкт/масив може мати поля
            Object v = var.getValue();
            if (v == null) return false;
            if (v instanceof Number || v instanceof Boolean || v instanceof CharSequence
                    || v instanceof Character) return false;
            return true;
        }
    }

    private final List<Node> roots = new ArrayList<>();
    /** Плоский видимий список (згорнуто/розгорнуто). */
    private final List<Node> visible = new ArrayList<>();
    private AppTheme theme;

    public void setTheme(AppTheme theme) {
        this.theme = theme;
        notifyDataSetChanged();
    }

    /** Встановлює список локальних змінних (верхній рівень). */
    public void setVariables(List<DebugVariable> vars) {
        roots.clear();
        if (vars != null) {
            for (DebugVariable v : vars) roots.add(new Node(v, 0));
        }
        rebuildVisible();
        notifyDataSetChanged();
    }

    public void clear() {
        roots.clear();
        rebuildVisible();
        notifyDataSetChanged();
    }

    private void rebuildVisible() {
        visible.clear();
        for (Node n : roots) addIfVisible(n);
    }

    private void addIfVisible(Node n) {
        visible.add(n);
        if (n.expanded) {
            ensureChildren(n);
            for (Node c : n.children) addIfVisible(c);
        }
    }

    private void ensureChildren(Node n) {
        if (n.children != null) return;
        n.children = new ArrayList<>();
        List<DebugVariable> fields = n.var.getFields();
        if (fields != null) {
            for (DebugVariable f : fields) {
                n.children.add(new Node(f, n.depth + 1));
            }
        }
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(com.ccs.wiredroid.R.layout.item_variable, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Node n = visible.get(position);
        // відступ за глибиною
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < n.depth; i++) indent.append("    ");
        h.indent.setText(indent.toString());

        // стрілка розкриття
        if (n.hasChildren()) {
            h.expand.setVisibility(View.VISIBLE);
            h.expand.setText(n.expanded ? "▾" : "▸");
            h.itemView.setOnClickListener(v -> toggle(n));
        } else {
            h.expand.setVisibility(View.INVISIBLE);
            h.itemView.setOnClickListener(null);
            h.itemView.setClickable(false);
        }

        // іконка типу
        h.icon.setText(iconFor(n.var));

        // ім'я + тип
        h.name.setText(n.var.getName());
        String type = n.var.getType();
        h.colon.setText(" : " + type);

        // значення
        String val = n.var.getDisplayValue();
        if (val != null && val.length() > 80) val = val.substring(0, 80) + "…";
        h.value.setText(val);

        applyTheme(n, h);
    }

    private String iconFor(DebugVariable v) {
        String t = v.getType() == null ? "" : v.getType().toLowerCase();
        Object val = v.getValue();
        if (val == null) return "∅";
        if (val.getClass().isArray()) return "🅐";
        if (val instanceof String || val instanceof CharSequence) return "🅢";
        if (val instanceof Boolean) return "🅑";
        if (val instanceof Number) return "🅝";
        if (val instanceof Character) return "🅒";
        return "🅞";
    }

    private void applyTheme(Node n, VH h) {
        int nameClr = theme != null ? theme.text : 0xFFA9B7C6;
        int dimClr  = theme != null ? theme.textDim : 0xFF808080;
        int valBase  = theme != null ? theme.editorString : 0xFF6A8759;
        int numClr  = theme != null ? theme.editorNumber : 0xFF6897BB;
        int strClr  = theme != null ? theme.editorString : 0xFF6A8759;
        int accent  = theme != null ? theme.accent : 0xFFCC7832;

        h.indent.setTextColor(dimClr);
        h.expand.setTextColor(n.expanded ? accent : nameClr);
        h.name.setTextColor(nameClr);
        h.colon.setTextColor(dimClr);
        // колір значення за типом
        Object val = n.var.getValue();
        if (val instanceof Number) h.value.setTextColor(numClr);
        else if (val instanceof String || val instanceof CharSequence) h.value.setTextColor(strClr);
        else h.value.setTextColor(valBase);
    }

    private void toggle(Node n) {
        n.expanded = !n.expanded;
        rebuildVisible();
        notifyDataSetChanged();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView indent;
        final TextView expand;
        final TextView icon;
        final TextView name;
        final TextView colon;
        final TextView value;

        VH(View v) {
            super(v);
            indent = v.findViewById(com.ccs.wiredroid.R.id.varIndent);
            expand = v.findViewById(com.ccs.wiredroid.R.id.varExpand);
            icon   = v.findViewById(com.ccs.wiredroid.R.id.varIcon);
            name   = v.findViewById(com.ccs.wiredroid.R.id.varName);
            colon  = v.findViewById(com.ccs.wiredroid.R.id.varColon);
            value  = v.findViewById(com.ccs.wiredroid.R.id.varValue);
        }
    }
}
