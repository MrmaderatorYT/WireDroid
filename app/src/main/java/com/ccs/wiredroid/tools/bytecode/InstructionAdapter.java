package com.ccs.wiredroid.tools.bytecode;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppTheme;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Адаптер рядків інструкцій для байткод-в'ювера: колонки offset / label / opcode /
 * operand / comment, підсвічування під активну тему, клікабельні jump-targets
 * (tap по goto/if/switch → скрол до цільової мітки), фільтр-пошук.
 *
 * <p>Кольори мапляться з {@link AppTheme}:
 * <ul>
 *   <li>opcode → {@code editorKeyword}</li>
 *   <li>числовий операнд → {@code editorNumber}</li>
 *   <li>тип/рядок → {@code editorString}</li>
 *   <li>коментар → {@code editorComment}</li>
 *   <li>label → {@code accent}</li>
 *   <li>offset/meta → {@code textDim}</li>
 * </ul></p>
 */
public class InstructionAdapter extends RecyclerView.Adapter<InstructionAdapter.VH> {

    /** Стрибок до мітки з таким іменем (з prefix L). */
    public interface JumpListener {
        void jumpToLabel(String label);
    }

    private final List<BytecodeModel.Instruction> insns = new ArrayList<>();
    private AppTheme theme;
    private JumpListener jumpListener;
    private String query = "";
    private boolean showLineNumbers = true;
    private boolean showComments = true;
    private int selectedItemIndex = -1;

    public void setTheme(AppTheme theme) {
        this.theme = theme;
        notifyDataSetChanged();
    }

    public void setJumpListener(JumpListener l) {
        this.jumpListener = l;
    }

    public void setInstructions(List<BytecodeModel.Instruction> list) {
        insns.clear();
        if (list != null) insns.addAll(list);
        notifyDataSetChanged();
    }

    public void clear() {
        insns.clear();
        notifyDataSetChanged();
    }

    public void setShowLineNumbers(boolean v) {
        if (showLineNumbers != v) { showLineNumbers = v; notifyDataSetChanged(); }
    }

    public void setShowComments(boolean v) {
        if (showComments != v) { showComments = v; notifyDataSetChanged(); }
    }

    /** Пошуковий запит; підсвічує рядки, що містять його (case-insensitive). */
    public void setQuery(String q) {
        this.query = q == null ? "" : q.trim().toLowerCase(Locale.US);
        notifyDataSetChanged();
    }

    public boolean isShowLineNumbers() { return showLineNumbers; }
    public boolean isShowComments() { return showComments; }

    public int getSelectedItemIndex() { return selectedItemIndex; }

    public void setSelectedItemIndex(int index) {
        int old = selectedItemIndex;
        selectedItemIndex = index;
        if (old >= 0 && old < insns.size()) notifyItemChanged(old);
        if (index >= 0 && index < insns.size()) notifyItemChanged(index);
    }

    @Override
    public int getItemCount() {
        return insns.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_instruction, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        BytecodeModel.Instruction in = insns.get(position);

        // offset
        if (showLineNumbers) {
            h.offset.setVisibility(View.VISIBLE);
            h.offset.setText(String.format(Locale.US, "%5d", in.offset));
        } else {
            h.offset.setVisibility(View.GONE);
        }

        // label column
        String lbl = in.label;
        if (lbl != null && !lbl.isEmpty()) {
            h.label.setVisibility(View.VISIBLE);
            h.label.setText(lbl + ":");
        } else {
            h.label.setVisibility(View.GONE);
        }

        int clrOpcode  = theme != null ? theme.editorKeyword : 0xFFCC7832;
        int clrNumber  = theme != null ? theme.editorNumber  : 0xFF6897BB;
        int clrString  = theme != null ? theme.editorString  : 0xFF6A8759;
        int clrComment = theme != null ? theme.editorComment : 0xFF808080;
        int clrLabel   = theme != null ? theme.accent        : 0xFFFFC66D;
        int clrDim     = theme != null ? theme.textDim       : 0xFF808080;

        h.label.setTextColor(clrLabel);

        // opcode + спеціальна обробка мета-рядків (line / frame) та оголошень міток
        if (in.tokenType == BytecodeModel.Token.META) {
            // рядок "// line N" або "// frame type=..."
            h.opcode.setVisibility(View.GONE);
            h.operand.setVisibility(View.GONE);
            h.comment.setVisibility(showComments ? View.VISIBLE : View.GONE);
            h.comment.setText(in.comment);
            h.comment.setTextColor(clrDim);
        } else if (in.tokenType == BytecodeModel.Token.LABEL_DECL) {
            // "L3:" — показуємо в opcode колонці
            h.opcode.setVisibility(View.VISIBLE);
            h.opcode.setText(in.opcode);
            h.opcode.setTextColor(clrLabel);
            h.operand.setVisibility(View.GONE);
            h.comment.setVisibility(View.GONE);
        } else {
            h.opcode.setVisibility(View.VISIBLE);
            h.opcode.setText(in.opcode);
            h.opcode.setTextColor(clrOpcode);

            // operand з підсвічуванням за типом токена + клікабельність для jumps
            bindOperand(h, in, clrNumber, clrString);

            // comment
            if (showComments && in.comment != null && !in.comment.isEmpty()) {
                h.comment.setVisibility(View.VISIBLE);
                h.comment.setText(in.comment);
                h.comment.setTextColor(clrComment);
            } else {
                h.comment.setVisibility(View.GONE);
            }
        }

        // підсвітка пошукового запиту
        applySearchHighlight(h, in);

        // Виділення вибраного рядка
        boolean isSelected = (position == selectedItemIndex);
        if (isSelected) {
            int selBg = theme != null
                    ? (theme.accent & 0x00FFFFFF) | 0x55000000
                    : 0x559876AA;
            h.itemView.setBackgroundColor(selBg);
        } else if (query.isEmpty()) {
            h.itemView.setBackgroundColor(0);
        }

        h.itemView.setOnClickListener(v -> {
            setSelectedItemIndex(h.getAdapterPosition());
        });
    }

    private void bindOperand(VH h, BytecodeModel.Instruction in, int clrNumber, int clrString) {
        String op = in.operand;
        if (op == null || op.isEmpty()) {
            h.operand.setVisibility(View.GONE);
            return;
        }
        h.operand.setVisibility(View.VISIBLE);
        SpannableStringBuilder ssb = new SpannableStringBuilder(op);
        int color;
        switch (in.tokenType) {
            case BytecodeModel.Token.NUMBER: color = clrNumber; break;
            case BytecodeModel.Token.STRING: color = clrString; break;
            case BytecodeModel.Token.TYPE:   color = clrString; break;
            case BytecodeModel.Token.FIELD:
            case BytecodeModel.Token.METHOD: color = clrString; break;
            case BytecodeModel.Token.LABEL:  color = clrNumber; break;
            default: color = theme != null ? theme.text : 0xFFA9B7C6;
        }
        ssb.setSpan(new ForegroundColorSpan(color), 0, op.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // клікабельний jump-target (goto/if/switch → Lxx)
        if (in.tokenType == BytecodeModel.Token.LABEL && jumpListener != null) {
            final String target = op;
            ssb.setSpan(new ClickableSpan() {
                @Override public void onClick(@NonNull View widget) {
                    jumpListener.jumpToLabel(target);
                }
            }, 0, op.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            h.operand.setClickable(true);
        } else {
            h.operand.setClickable(false);
        }
        h.operand.setText(ssb);
    }

    private void applySearchHighlight(VH h, BytecodeModel.Instruction in) {
        if (query.isEmpty()) {
            h.itemView.setBackgroundColor(0);
            return;
        }
        boolean match = containsI(in.opcode) || containsI(in.operand)
                || containsI(in.comment) || (in.label != null && containsI(in.label));
        if (match) {
            int bg = theme != null
                    ? (theme.accent & 0x00FFFFFF) | 0x33000000
                    : 0x339876AA;
            h.itemView.setBackgroundColor(bg);
        } else {
            h.itemView.setBackgroundColor(0);
        }
    }

    private boolean containsI(String s) {
        return s != null && s.toLowerCase(Locale.US).contains(query);
    }

    /**
     * Повертає позицію першої інструкції з заданою міткою (для jump-to-label).
     */
    public int positionOfLabel(String label) {
        if (label == null) return -1;
        for (int i = 0; i < insns.size(); i++) {
            BytecodeModel.Instruction in = insns.get(i);
            // оголошення мітки: opcode = "L3:"
            if (in.tokenType == BytecodeModel.Token.LABEL_DECL
                    && (label + ":").equals(in.opcode)) {
                return i;
            }
        }
        return -1;
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView offset;
        final TextView label;
        final TextView opcode;
        final TextView operand;
        final TextView comment;

        VH(View v) {
            super(v);
            offset  = v.findViewById(R.id.insnOffset);
            label   = v.findViewById(R.id.insnLabel);
            opcode  = v.findViewById(R.id.insnOpcode);
            operand = v.findViewById(R.id.insnOperand);
            comment = v.findViewById(R.id.insnComment);
        }
    }
}
