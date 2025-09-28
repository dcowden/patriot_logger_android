package com.patriotlogger.logger.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.TagStatus;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DebugInfoAdapter extends ListAdapter<TagStatus, DebugInfoAdapter.ViewHolder> {

    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public DebugInfoAdapter() {
        super(new DiffUtil.ItemCallback<TagStatus>() {
            @Override
            public boolean areItemsTheSame(@NonNull TagStatus oldItem, @NonNull TagStatus newItem) {
                return oldItem.tagId == newItem.tagId;
            }

            @Override
            public boolean areContentsTheSame(@NonNull TagStatus oldItem, @NonNull TagStatus newItem) {
                // For simplicity, we can assume if items are the same, contents are too,
                // or implement a more thorough check if TagStatus fields frequently change in place.
                return oldItem.tagId == newItem.tagId &&
                       oldItem.state.equals(newItem.state) &&
                       oldItem.sampleCount == newItem.sampleCount &&
                       oldItem.peakTimeMs == newItem.peakTimeMs;
            }
        });
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_debug_info, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TagStatus item = getItem(position);
        if (item != null) {
            holder.bind(item);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvInfo;

        ViewHolder(View view) {
            super(view);
            tvInfo = view.findViewById(R.id.tvDebugItemInfo);
        }

        void bind(TagStatus status) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "TagID: %d, Name: %s, State: %s\n",
                    status.tagId, status.friendlyName, status.state));
            sb.append(String.format(Locale.US, "  Entry: %s, Peak: %s, Exit: %s\n",
                    formatTime(status.entryTimeMs), formatTime(status.peakTimeMs), formatTime(status.exitTimeMs)));
            sb.append(String.format(Locale.US, "  RSSI (Est/Peak/Low): %.1f / %.1f / %.1f, Count: %d",
                    status.estimatedRssi, status.peakRssi, status.lowestRssi, status.sampleCount));
            tvInfo.setText(sb.toString());
        }

        private String formatTime(long timeMs) {
            if (timeMs == 0L) return "--:--:--.--";
            return sdf.format(new Date(timeMs));
        }
    }
}
