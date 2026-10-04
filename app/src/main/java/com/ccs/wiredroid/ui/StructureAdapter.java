package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppTheme;

import java.util.ArrayList;
import java.util.List;

public class StructureAdapter extends RecyclerView.Adapter<StructureAdapter.ViewHolder> {

    public interface OnMemberClickListener {
        void onMemberClick(MemberOutline.Member member);
    }

    private List<MemberOutline.Member> members = new ArrayList<>();
    private final AppTheme theme;
    private final OnMemberClickListener listener;
    private final Context context;

    public StructureAdapter(Context context, AppTheme theme, OnMemberClickListener listener) {
        this.context = context;
        this.theme = theme;
        this.listener = listener;
    }

    public void setMembers(List<MemberOutline.Member> newMembers) {
        this.members = newMembers != null ? newMembers : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // We can reuse a simple row layout or create a new one, but let's just inflate a generic horizontal linear layout with an icon and text.
        // For simplicity, we can use item_result from search, but it has multiple lines.
        // Let's create it programmatically to avoid adding a new layout file.
        android.widget.LinearLayout root = new android.widget.LinearLayout(context);
        root.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        root.setPadding((int)(16 * context.getResources().getDisplayMetrics().density), 
                        (int)(12 * context.getResources().getDisplayMetrics().density), 
                        (int)(16 * context.getResources().getDisplayMetrics().density), 
                        (int)(12 * context.getResources().getDisplayMetrics().density));
        root.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // selectableItemBackground is a theme attribute, not a drawable: handing
        // its id straight to setBackgroundResource throws NotFoundException.
        // It has to be resolved against the theme first.
        android.util.TypedValue ripple = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, ripple, true)) {
            root.setBackgroundResource(ripple.resourceId);
        }
        
        ImageView icon = new ImageView(context);
        icon.setId(android.R.id.icon1);
        android.widget.LinearLayout.LayoutParams iconParams = new android.widget.LinearLayout.LayoutParams(
                (int)(18 * context.getResources().getDisplayMetrics().density), 
                (int)(18 * context.getResources().getDisplayMetrics().density));
        iconParams.setMarginEnd((int)(12 * context.getResources().getDisplayMetrics().density));
        root.addView(icon, iconParams);
        
        TextView text = new TextView(context);
        text.setId(android.R.id.text1);
        text.setTextColor(theme.text);
        text.setTextSize(14f);
        text.setSingleLine(true);
        root.addView(text, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        
        return new ViewHolder(root, icon, text);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MemberOutline.Member m = members.get(position);
        holder.text.setText(m.label);
        
        // Use standard drawable or colored circle
        if (m.kind == MemberOutline.Kind.CONSTRUCTOR) {
            holder.icon.setImageResource(android.R.drawable.ic_menu_manage);
            holder.icon.setColorFilter(Color.parseColor("#6A8759"), PorterDuff.Mode.SRC_IN);
        } else if (m.method) {
            holder.icon.setImageResource(android.R.drawable.ic_menu_manage); // simple gear/method icon
            holder.icon.setColorFilter(Color.parseColor("#FFC66D"), PorterDuff.Mode.SRC_IN);
        } else {
            holder.icon.setImageResource(android.R.drawable.presence_invisible); // generic field bullet
            holder.icon.setColorFilter(Color.parseColor("#9876AA"), PorterDuff.Mode.SRC_IN);
        }
        
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onMemberClick(m);
        });
    }

    @Override
    public int getItemCount() {
        return members.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView text;
        
        ViewHolder(View itemView, ImageView icon, TextView text) {
            super(itemView);
            this.icon = icon;
            this.text = text;
        }
    }
}
