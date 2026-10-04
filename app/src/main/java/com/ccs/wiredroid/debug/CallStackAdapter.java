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
 * Адаптер Call Stack для дебагера: кожен фрейм як рядок з маркером активного
 * фрейму (►) + клас.метод:рядок. Тап → callback (стрибок до файлу/рядка).
 */
public class CallStackAdapter extends RecyclerView.Adapter<CallStackAdapter.VH> {

    public interface Listener {
        void onFrameClicked(StackTraceElement frame);
    }

    private final List<StackTraceElement> frames = new ArrayList<>();
    private AppTheme theme;
    private Listener listener;

    public void setTheme(AppTheme theme) {
        this.theme = theme;
        notifyDataSetChanged();
    }

    public void setListener(Listener l) { this.listener = l; }

    public void setFrames(StackTraceElement[] stack) {
        frames.clear();
        if (stack != null) {
            for (StackTraceElement f : stack) frames.add(f);
        }
        notifyDataSetChanged();
    }

    public void clear() {
        frames.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() { return frames.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(com.ccs.wiredroid.R.layout.item_stack_frame, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        StackTraceElement f = frames.get(position);
        h.marker.setText(position == 0 ? "►" : " ");
        h.number.setText("#" + position);
        // коротке ім'я класу
        String cls = f.getClassName();
        int dot = cls.lastIndexOf('.');
        if (dot >= 0) cls = cls.substring(dot + 1);
        h.text.setText(cls + "." + f.getMethodName() + ":" + f.getLineNumber());

        int accent = theme != null ? theme.accent : 0xFFFFC66D;
        int dim = theme != null ? theme.textDim : 0xFF808080;
        int text = theme != null ? theme.text : 0xFFA9B7C6;
        h.marker.setTextColor(accent);
        h.number.setTextColor(dim);
        h.text.setTextColor(position == 0 ? accent : text);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onFrameClicked(f);
        });
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView marker;
        final TextView number;
        final TextView text;
        VH(View v) {
            super(v);
            marker = v.findViewById(com.ccs.wiredroid.R.id.frameMarker);
            number = v.findViewById(com.ccs.wiredroid.R.id.frameNumber);
            text   = v.findViewById(com.ccs.wiredroid.R.id.frameText);
        }
    }
}
