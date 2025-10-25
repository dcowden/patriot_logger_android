package com.patriotlogger.logger.logic;

import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.Setting;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.data.TagStatus.TagStatusState;
import com.patriotlogger.logger.logic.filters.MinMaxRssiFilter;
import com.patriotlogger.logger.logic.filters.RssiFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Single source of truth for "process one RSSI sample" + periodic sweeps.
 * Headless (no Android types), thread-safe enough for single worker usage.
 */
public final class SampleProcessor {

    private final Repository repository;

    private final RssiSmoother rssiSmoother = new RssiSmoother();
    private final Map<Integer, SuperSimpleHandler> handlerByTrack = new ConcurrentHashMap<>();
    private final List<RssiFilter> filters = new ArrayList<>();

    private final SuperSimpleHandler handler;
    private final Setting s;

//    public SampleProcessor(@NonNull Repository repository,
//                           @NonNull Setting s) {
//        this.repository = repository;
//        this.s = s;
//        synchronized (filters) {
//            filters.clear();
//            int min = (s.filter_min_rssi != null) ? s.filter_min_rssi : Setting.DEFAULT_FILTER_MIN_RSSI;
//            int max = (s.filter_max_rssi != null) ? s.filter_max_rssi : Setting.DEFAULT_FILTER_MAX_RSSI;
//            filters.add(new MinMaxRssiFilter(min, max));
//        }
//        if (s.tagdata_flush_ms != null) repository.setTagDataFlushIntervalMs(s.tagdata_flush_ms);
//        this.handler = newTcaHandler(s);
//        handlerByTrack.clear(); // ensure new handlers reflect new settingsseed defaults
//    }

    public SampleProcessor(Repository repository, SuperSimpleHandler h, Setting s ){
        this.s = s;
        int min = (s.filter_min_rssi != null) ? s.filter_min_rssi : Setting.DEFAULT_FILTER_MIN_RSSI;
        int max = (s.filter_max_rssi != null) ? s.filter_max_rssi : Setting.DEFAULT_FILTER_MAX_RSSI;
        filters.add(new MinMaxRssiFilter(min, max));
        this.repository = repository;
        this.handler = h;
    }

    /** Best-effort handler for a given track (useful for tests to introspect). */
    @Nullable
    public SuperSimpleHandler getHandlerForTrack(int trackId) {
        return handlerByTrack.get(trackId);
    }

    /** Seed handlers for any open passes (used by the service warm start). */
    public void seedFromOpenPasses() {
        handlerByTrack.clear();
        List<TagStatus> open = repository.getOpenPassesSync();
        for (TagStatus ts : open){
            handlerByTrack.put(ts.trackId, newTcaHandler(s));
        }
        repository.restoreOpenPassBuffers(120);
    }

    /** Process a single RSSI sample path (shared by service & tests). Returns the post-state. */
    @NonNull
    public TagStatus processSample(int tagId, int rssi, long nowMs) {
        MedianRssiSmoother medianSmoother = new MedianRssiSmoother(3);
        // (filters)
        List<RssiFilter> fs;
        synchronized (filters) { fs = new ArrayList<>(filters); }
        for (RssiFilter f : fs) {
            if (!f.shouldAccept(nowMs, rssi)) {
                // calibration stream still wants samples
                if (!repository.isSavingEnabled()) {
                    repository.appendCalibrationSample(new RssiData(tagId, nowMs, rssi, rssi));
                }
                return repository.getOrCreateActiveStatus(tagId); // unchanged
            }
        }

        // build RssiData (UI smoothing; handlers do their own)
        Setting st = s;
        //float smoothed = rssiSmoother.getSmoothedRssi(rssi, st);
        float smoothed = medianSmoother.updateAndGet(rssi);
        RssiData rssiData = new RssiData(tagId, nowMs, rssi, (int) smoothed);

        if (!repository.isSavingEnabled()) {
            repository.appendCalibrationSample(rssiData);
            return repository.getOrCreateActiveStatus(tagId);
        }

        // Persisting path
        TagStatus latestStatus = repository.getOrCreateActiveStatus(tagId);
        int trackId = latestStatus.trackId;

        List<TagData> history = repository.getHistoryForTrackIdSyncCombined(trackId);
        repository.appendInMemoryTagData(new TagData(trackId, nowMs, rssi));

        //RssiHandler handler = handlerByTrack.computeIfAbsent(trackId, k -> newTcaHandler(st));

        TagStatus processed = handler.acceptSample(latestStatus, history, rssiData);

        repository.upsertTagStatus(processed, st.retain_samples == null ? false : !st.retain_samples, null);

        if (processed.state == TagStatusState.LOGGED || processed.state == TagStatusState.TIMED_OUT) {
            handlerByTrack.remove(processed.trackId);
        }
        return processed;
    }

    /** Periodic timeout sweep; returns statuses that were transitioned to TIMED_OUT. */
    @NonNull
    public List<TagStatus> sweepForTimeouts(long abandonedTimeoutMs, long nowMs) {
        List<TagStatus> changed = new ArrayList<>();
        List<TagStatus> active = repository.getAllActiveTagsSync();
        for (TagStatus s : active) {
            long msSince = nowMs - s.lastSeenMs;
            if (s.state == TagStatusState.HERE && msSince > abandonedTimeoutMs) {
                TagStatus timed = s;
                timed.state = TagStatusState.TIMED_OUT;
                timed.exitTimeMs = nowMs;
                repository.upsertTagStatus(timed, this.s.retain_samples == null ? false : !(this.s.retain_samples), null);
                handlerByTrack.remove(timed.trackId);
                changed.add(timed);
            }
        }
        return changed;
    }

    // ---- handler factory pulled from service ----
    public static SuperSimpleHandler newTcaHandler(@NonNull Setting s) {

        //return new PeakWindowHandler(new PeakWindowHandler.Params());
        return new SuperSimpleHandler(-90, 2, 10000, 30000);

//        float  alpha        = s.tca_alpha != null ? s.tca_alpha : Setting.DEFAULT_TCA_ALPHA;
//        double txAt1mDbm    = s.tx_power_at_1m_dbm != null ? s.tx_power_at_1m_dbm : Setting.DEFAULT_TX_POWER_AT_1M_DBM;
//        double pathExp      = s.path_loss_n != null ? s.path_loss_n : Setting.DEFAULT_PATH_LOSS_N;
//        double hereMeters   = s.tca_here_meters != null ? s.tca_here_meters : Setting.DEFAULT_TCA_HERE_METERS;
//        double thresholdSec = s.tca_threshold_sec != null ? s.tca_threshold_sec : Setting.DEFAULT_TCA_THRESHOLD_SEC;
//        int    windowSize   = s.tca_window_size != null ? s.tca_window_size : Setting.DEFAULT_TCA_WINDOW_SIZE;
//        int    minPoints    = s.tca_min_points != null ? s.tca_min_points : Setting.DEFAULT_TCA_MIN_POINTS;
//        double approachM    = s.tca_approach_meters != null ? s.tca_approach_meters : Setting.DEFAULT_TCA_APPROACH_METERS;
//        return new com.patriotlogger.logger.logic.TcaWithFallbackHandler(
//                alpha, txAt1mDbm, pathExp, hereMeters, thresholdSec, windowSize, minPoints, approachM
//        );
    }
}
