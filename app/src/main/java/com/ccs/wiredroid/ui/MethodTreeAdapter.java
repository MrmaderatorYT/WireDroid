package com.ccs.wiredroid.ui;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.tools.bytecode.BytecodeModel;
import com.ccs.wiredroid.tools.bytecode.BytecodeFormatter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Адаптер дерева класу для байткод-в'ювера: секції Fields та Methods,
 * кожен елемент — поле або метод з іконкою access-flag.
 *
 * <p>Дзеркалить структуру {@link ProblemsAdapter} (setTheme / setListener / setItems).</p>
 */
public class MethodTreeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** Слухач кліку: повертає індекс методу (не поля) у списку методів моделі. */
    public interface Listener {
        void onMethodClicked(int methodIndex);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_MEMBER = 1;

    private static final class Header {
        final String text;
        Header(String text) { this.text = text; }
    }

    private final List<Object> items = new ArrayList<>();
    private AppTheme theme;
    private Listener listener;
    private int selectedMethodIndex = -1;

    public void setTheme(AppTheme theme) {
        this.theme = theme;
        notifyDataSetChanged();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Перебудовує список. fields/methods — з {@link BytecodeModel}.
     */
    public void setModel(List<BytecodeModel.FieldInfo> fields,
                         List<BytecodeModel.MethodInfo> methods) {
        items.clear();
        if (fields != null && !fields.isEmpty()) {
            items.add(new Header("FIELDS (" + fields.size() + ")"));
            for (BytecodeModel.FieldInfo f : fields) items.add(f);
        }
        if (methods != null && !methods.isEmpty()) {
            items.add(new Header("METHODS (" + methods.size() + ")"));
            for (BytecodeModel.MethodInfo m : methods) items.add(m);
        }
        notifyDataSetChanged();
    }

    public void clear() {
        items.clear();
        selectedMethodIndex = -1;
        notifyDataSetChanged();
    }

    /** Позначає активний метод (підсвічує фон). */
    public void setSelectedMethod(int methodIndex) {
        this.selectedMethodIndex = methodIndex;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof Header ? TYPE_HEADER : TYPE_MEMBER;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            View v = inf.inflate(R.layout.item_method_header, parent, false);
            return new HeaderVH(v);
        }
        View v = inf.inflate(R.layout.item_method, parent, false);
        return new MemberVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
        Object o = items.get(position);
        if (h instanceof HeaderVH) {
            ((HeaderVH) h).bind(((Header) o).text, theme);
        } else if (o instanceof BytecodeModel.MethodInfo) {
            ((MemberVH) h).bindMethod((BytecodeModel.MethodInfo) o, theme,
                    indexOfMethodItem(position) == selectedMethodIndex);
            h.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    int pos = h.getAdapterPosition();
                    int idx = indexOfMethodItem(pos);
                    if (idx >= 0) listener.onMethodClicked(idx);
                }
            });
        } else if (o instanceof BytecodeModel.FieldInfo) {
            ((MemberVH) h).bindField((BytecodeModel.FieldInfo) o, theme);
            h.itemView.setOnClickListener(null);
            h.itemView.setClickable(false);
        }
    }

    /** Конвертує позицію у списку items у індекс методу серед методів моделі. */
    private int indexOfMethodItem(int position) {
        if (position < 0 || position >= items.size()) return -1;
        int idx = -1;
        for (int i = 0; i <= position; i++) {
            if (items.get(i) instanceof BytecodeModel.MethodInfo) idx++;
        }
        return idx;
    }

    // ── ViewHolders ──────────────────────────────────────────────────────────

    static final class HeaderVH extends RecyclerView.ViewHolder {
        final TextView text;
        HeaderVH(View v) {
            super(v);
            text = (TextView) v;
        }
        void bind(String s, AppTheme theme) {
            text.setText(s);
            text.setTextColor(theme != null ? theme.accent : 0xFF9876AA);
            text.setBackgroundColor(theme != null
                    ? (theme.toolbar & 0x00FFFFFF) | 0x22000000 : 0x22404040);
        }
    }

    static final class MemberVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView type;

        MemberVH(View v) {
            super(v);
            icon = v.findViewById(R.id.methodIcon);
            name = v.findViewById(R.id.methodName);
            type = v.findViewById(R.id.methodType);
        }

        void bindMethod(BytecodeModel.MethodInfo m, AppTheme theme, boolean selected) {
            icon.setImageResource(BytecodeFormatter.methodIconRes(m.access));
            // ім'я методу + скорочена сигнатура
            name.setText(m.name + m.shortText.substring(m.name.length()));
            // тип: access + повернення
            StringBuilder t = new StringBuilder();
            if (!m.accessText.isEmpty()) t.append(m.accessText).append(' ');
            String sig = m.signatureText;
            int colon = sig.lastIndexOf(") : ");
            if (colon >= 0) t.append(sig.substring(colon + 4));
            else t.append("void");
            type.setText(t);
            applyColors(theme, selected);
        }

        void bindField(BytecodeModel.FieldInfo f, AppTheme theme) {
            icon.setImageResource(BytecodeFormatter.fieldIconRes(f.access));
            name.setText(f.name);
            StringBuilder t = new StringBuilder();
            if (!f.accessText.isEmpty()) t.append(f.accessText).append(' ');
            t.append(f.typeText);
            type.setText(t);
            applyColors(theme, false);
        }

        private void applyColors(AppTheme theme, boolean selected) {
            int nameClr = theme != null ? theme.text : 0xFFA9B7C6;
            int typeClr = theme != null ? theme.textDim : 0xFF808080;
            name.setTextColor(nameClr);
            type.setTextColor(typeClr);
            if (selected) {
                int bg = theme != null
                        ? (theme.accent & 0x00FFFFFF) | 0x33000000
                        : 0x339876AA;
                itemView.setBackgroundColor(bg);
            } else {
                itemView.setBackgroundColor(0);
            }
        }
    }
}
