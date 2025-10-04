package com.patriotlogger.logger.ui;

import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.DebugTagData;

import java.util.Locale;

public class DebugInfoAdapter extends ListAdapter<DebugTagData, DebugInfoAdapter.ViewHolder> {

    public DebugInfoAdapter() {
        super(new DiffUtil.ItemCallback<DebugTagData>() {
            @Override
            public boolean areItemsTheSame(@NonNull DebugTagData oldItem, @NonNull DebugTagData newItem) {
                // Use the unique dataId for the most efficient comparison
                return oldItem.dataId == newItem.dataId;
            }

            @Override
            public boolean areContentsTheSame(@NonNull DebugTagData oldItem, @NonNull DebugTagData newItem) {
                // If items are the same, check if contents have changed.
                return oldItem.trackId == newItem.trackId &&
                       oldItem.tagId == newItem.tagId &&
                       oldItem.timestampMs == newItem.timestampMs &&
                       oldItem.rssi == newItem.rssi;
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
        DebugTagData item = getItem(position);
        if (item != null) {
            holder.bind(item);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvInfo;

        ViewHolder(View view) {
            super(view);
            tvInfo = view.findViewById(R.id.tvDebugItemInfo);
            // Set a smaller font size for compactness
            tvInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        }

        void bind(DebugTagData item) {
            // Chop off the first 7 digits of the timestamp for a more compact display
            String timestampStr = String.valueOf(item.timestampMs);
            String compactTimestamp = timestampStr.length() > 7 ? timestampStr.substring(7) : timestampStr;

            // Format the string to display the 5 required columns, with dataId first.
            String displayText = String.format(Locale.US,
                    "id:%d trk:%d dev:%s ts:%s rssi:%d",
                    item.dataId,
                    item.trackId,
                    item.getDeviceName(),
                    compactTimestamp,
                    item.rssi);
            tvInfo.setText(displayText);
        }
    }
}
