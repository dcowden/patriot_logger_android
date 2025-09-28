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
// Import TagStatus which now contains TagStatusState
import com.patriotlogger.logger.data.TagStatus;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

public class DebugInfoAdapter extends ListAdapter<TagStatus, DebugInfoAdapter.ViewHolder> {

    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public DebugInfoAdapter() {
        super(new DiffUtil.ItemCallback<TagStatus>() {
            @Override
            public boolean areItemsTheSame(@NonNull TagStatus oldItem, @NonNull TagStatus newItem) {
                // trackId is the unique key for a pass
                return oldItem.trackId == newItem.trackId;
            }

            @Override
            public boolean areContentsTheSame(@NonNull TagStatus oldItem, @NonNull TagStatus newItem) {
                return oldItem.trackId == newItem.trackId && // Should be same if items are same
                       oldItem.tagId == newItem.tagId &&
                       oldItem.state.equals( newItem.state)  && // Enum comparison is fine
                       (Objects.equals(oldItem.friendlyName, newItem.friendlyName)) &&
                       oldItem.entryTimeMs == newItem.entryTimeMs &&
                       oldItem.peakTimeMs == newItem.peakTimeMs &&
                       oldItem.exitTimeMs == newItem.exitTimeMs &&
                       oldItem.lastSeenMs == newItem.lastSeenMs &&
                       oldItem.lowestRssi == newItem.lowestRssi;
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
            sb.append(String.format(Locale.US, "TrackID: %d, TagID: %d, Name: %s\n",
                    status.trackId, status.tagId, status.friendlyName != null ? status.friendlyName : "N/A"));
            sb.append(String.format(Locale.US, "  State: %s, LowestRSSI: %.1f\n",
                    status.state.name(), status.lowestRssi)); // Use TagStatus.TagStatusState
            sb.append(String.format(Locale.US, "  Entry: %s\n  Peak:  %s\n  Exit:  %s\n  Last:  %s",
                    formatTime(status.entryTimeMs),
                    formatTime(status.peakTimeMs),
                    formatTime(status.exitTimeMs),
                    formatTime(status.lastSeenMs)));
            tvInfo.setText(sb.toString());
        }

        private String formatTime(long timeMs) {
            if (timeMs == 0L) return "--:--:--.---";
            return sdf.format(new Date(timeMs));
        }
    }
}
