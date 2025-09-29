package com.patriotlogger.logger.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.patriotlogger.logger.R;
// Updated import to TagStatus, as TagStatusState is an inner enum
import com.patriotlogger.logger.data.TagStatus;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class RunnerAdapter extends RecyclerView.Adapter<RunnerAdapter.VH> {

    private final AsyncListDiffer<TagStatus> differ = new AsyncListDiffer<>(this, DIFF_CALLBACK);
    private long gunTimeMs = 0L;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat splitMillisFormat = new SimpleDateFormat("mm:ss.SSS", Locale.getDefault());
    private final Context context; // For accessing resources like drawables

    public RunnerAdapter(Context context) {
        this.context = context;
        timeFormat.setTimeZone(TimeZone.getDefault()); 
        splitMillisFormat.setTimeZone(TimeZone.getTimeZone("UTC")); 
    }

    public void submitList(List<TagStatus> list) {
        differ.submitList(list);
    }

    public void setGunTime(long gunTimeMs) {
        boolean changed = this.gunTimeMs != gunTimeMs;
        this.gunTimeMs = gunTimeMs;
        if (changed) {
            notifyDataSetChanged(); 
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_runner, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        TagStatus s = differ.getCurrentList().get(pos);

        String namePart = (s.friendlyName != null && !s.friendlyName.isEmpty()) ? s.friendlyName : "Device";
        String displayName = String.format(Locale.getDefault(), "%s (#%d P:%d)", namePart, s.tagId, s.trackId);
        h.name.setText(displayName);

        h.status.setText(s.state.name()); // This should still work as s.state is now TagStatus.TagStatusState

        if (s.lastSeenMs > 0) {
            h.lastSeen.setText(String.format("Seen: %s", timeFormat.format(new Date(s.lastSeenMs))));
        } else {
            h.lastSeen.setText("Seen: N/A");
        }

        // Updated to use TagStatus.TagStatusState
        if (s.state == TagStatus.TagStatusState.LOGGED && s.peakTimeMs > 0 && gunTimeMs > 0) {
            long split = s.peakTimeMs - gunTimeMs;
            if (split >= 0) {
                h.split.setText(splitMillisFormat.format(new Date(split)));
            } else {
                h.split.setText("- " + splitMillisFormat.format(new Date(Math.abs(split)))); 
            }
        } else {
            h.split.setText("--:--.---");
        }

        // Updated to use TagStatus.TagStatusState
        switch (s.state) {
            case APPROACHING:
                h.icon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.img_approaching));
                break;
            case HERE:
                h.icon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.img_herenow));
                break;
            case LOGGED:
                h.icon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.img_logged));
                break;
            default:
                h.icon.setImageResource(android.R.drawable.ic_menu_help); 
                break;
        }
        h.icon.clearColorFilter(); 
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name;
        TextView status;
        TextView split;
        TextView lastSeen; 

        VH(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.imgStatus);
            name = v.findViewById(R.id.tvName);
            status = v.findViewById(R.id.tvStatus);
            split = v.findViewById(R.id.tvSplit);
            lastSeen = v.findViewById(R.id.tvLastSeen); 
        }
    }

    private static final DiffUtil.ItemCallback<TagStatus> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<TagStatus>() {
        @Override
        public boolean areItemsTheSame(@NonNull TagStatus oldItem, @NonNull TagStatus newItem) {
            return oldItem.trackId == newItem.trackId;
        }

        @SuppressLint("DiffUtilEquals")
        @Override
        public boolean areContentsTheSame(@NonNull TagStatus oldItem, @NonNull TagStatus newItem) {
            return oldItem.tagId == newItem.tagId &&
                   oldItem.state == newItem.state && // This comparison is fine for enums
                   oldItem.lastSeenMs == newItem.lastSeenMs &&
                   oldItem.peakTimeMs == newItem.peakTimeMs && 
                   (oldItem.friendlyName != null ? oldItem.friendlyName.equals(newItem.friendlyName) : newItem.friendlyName == null);
        }
    };
}
