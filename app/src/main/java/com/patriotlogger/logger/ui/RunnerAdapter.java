package com.patriotlogger.logger.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.TagStatus;

import java.util.ArrayList;
import java.util.List;

public class RunnerAdapter extends RecyclerView.Adapter<RunnerAdapter.VH> {

    private final List<TagStatus> items = new ArrayList<>();

    public void submit(List<TagStatus> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_runner, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        TagStatus s = items.get(pos);
        String label = (s.friendlyName != null && !s.friendlyName.isEmpty()) ? s.friendlyName + " (PT-"+s.tagId+")" : ("PT-"+s.tagId);
        h.name.setText(label);
        h.status.setText(s.state);
        h.split.setText(formatMmSs(s.peakTimeMs, s));
        if ("logged".equals(s.state)) {
            h.icon.setImageResource(R.drawable.img_logged);
            h.icon.setColorFilter(h.itemView.getResources().getColor(R.color.green));
        } else if ("here".equals(s.state)) {
            h.icon.setImageResource(R.drawable.img_herenow);
            h.icon.setColorFilter(h.itemView.getResources().getColor(R.color.blue));
        } else {
            h.icon.setImageResource(R.drawable.img_approaching);
            h.icon.setColorFilter(h.itemView.getResources().getColor(R.color.accent));
        }
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView icon; TextView name; TextView status; TextView split;
        VH(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.imgStatus);
            name = v.findViewById(R.id.tvName);
            status = v.findViewById(R.id.tvStatus);
            split = v.findViewById(R.id.tvSplit);
        }
    }

    private String formatMmSs(long peakTimeMs, TagStatus s) {
        if (peakTimeMs <= 0) return "--:--";
        // We don't have gun time here; UI shows --:-- until uploaded or have context.
        long ms = 0;
        ms = 0;
        return "--:--";
    }
}
