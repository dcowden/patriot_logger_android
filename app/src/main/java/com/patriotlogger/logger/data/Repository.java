package com.patriotlogger.logger.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.patriotlogger.logger.logic.RssiData;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Repository {
    private static final String TAG = "Repository";
    private static volatile Repository instance;
    private final AppDatabase db;
    private final ExecutorService databaseWriteExecutor;
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    private final Map<Integer, Object> tagLockMap = new ConcurrentHashMap<>();

    private final MutableLiveData<Boolean> areSettingsInitialized = new MutableLiveData<>(false);

    // ===== Step-2: buffered TagData + periodic flush =====
    private final Map<Integer, List<TagData>> inMemoryTagDataBufferByTrack = new ConcurrentHashMap<>();

    private volatile long tagDataFlushIntervalMs = 2000L;
    private volatile boolean savingEnabled = true;

    private final Runnable periodicFlushRunnable = new Runnable() {
        @Override public void run() {
            try { flushAllTagDataBuffersInternal(); }
            finally { mainThreadHandler.postDelayed(this, tagDataFlushIntervalMs); }
        }
    };

    // ===== Step-5: calibration stream (in-memory) =====
    private final Object calibrationLock = new Object();
    private final ArrayList<RssiData> calibrationBuffer = new ArrayList<>(512);
    private final MutableLiveData<List<RssiData>> calibrationLive = new MutableLiveData<>(new ArrayList<>());

    public boolean isSavingEnabled(){ return savingEnabled; }
    public void setSavingEnabled(boolean newValue){ this.savingEnabled = newValue; }

    public void setTagDataFlushIntervalMs(long intervalMs) {
        if (intervalMs < 250L) intervalMs = 250L;
        this.tagDataFlushIntervalMs = intervalMs;
    }

    private Repository(Context ctx) {
        databaseWriteExecutor = Executors.newSingleThreadExecutor();
        db = Room.databaseBuilder(ctx.getApplicationContext(), AppDatabase.class, "psl.db")
                .fallbackToDestructiveMigration()
                .addCallback(new RoomDatabase.Callback() {
                    @Override public void onCreate(@NonNull SupportSQLiteDatabase _db) {
                        databaseWriteExecutor.execute(Repository.this::initializeDefaultSettingsInDbInternal);
                    }
                    @Override public void onOpen(@NonNull SupportSQLiteDatabase _db) {
                        databaseWriteExecutor.execute(Repository.this::checkAndInitializeDefaultSettingsInternal);
                    }
                })
                .build();

        mainThreadHandler.postDelayed(periodicFlushRunnable, tagDataFlushIntervalMs);
    }

    public static Repository get(@NonNull Context context) {
        if (instance == null) {
            synchronized (Repository.class) {
                if (instance == null) instance = new Repository(context.getApplicationContext());
            }
        }
        return instance;
    }

    // ===== TagStatus helpers =====
    private TagStatus createNewTagStatus(int tagId){
        TagStatus newStatus = new TagStatus();
        newStatus.tagId = tagId;
        newStatus.entryTimeMs = System.currentTimeMillis();
        newStatus.lastSeenMs = System.currentTimeMillis();
        newStatus.state = TagStatus.TagStatusState.FIRST_SAMPLE;
        return newStatus;
    }

    // ------------------------------------------------------------
    // === Calibration in-memory API ===
    // ------------------------------------------------------------


    /** Reactive stream of calibration RSSI data (UI can observe this). */
    public LiveData<List<RssiData>> getLiveCalibrationRssi() {
        return calibrationLive; // <- unify on the later stream
    }

    /** Clear calibration samples (useful when switching stations). */
    public void clearCalibrationRssi() {
        clearCalibrationBuffer(); // reuse the later helper
    }

    public TagStatus getOrCreateActiveStatus(int tagId) {
        final Object tagLock = tagLockMap.computeIfAbsent(tagId, k -> new Object());
        synchronized (tagLock) {
            TagStatus ts = db.tagStatusDao().getLatestForTagIdSync(tagId);

            // Check if we need to start a new pass
            if (ts == null || ts.state == TagStatus.TagStatusState.LOGGED || ts.state == TagStatus.TagStatusState.TIMED_OUT) {
                // --- START OF FIX ---

                // 1. Create a completely new TagStatus object for the new pass.
                ts = createNewTagStatus(tagId);

                // 2. Resolve the friendly name for this NEW object.
                ts.friendlyName = resolveFriendlyName(tagId);

                // 3. Insert it into the database to get a new auto-generated trackId.
                long newId = db.tagStatusDao().insertSync(ts);
                ts.trackId = (int) newId;

                // --- END OF FIX ---
            } else {
                // This is a continuation of an existing, active pass.
                // Just update the last seen time.
                ts.lastSeenMs = System.currentTimeMillis();
                // No need to update the friendly name, it's already set.
            }

            // By this point, 'ts' is the correct object for the current pass,
            // whether it's brand new or a continuation of an active one.
            return ts;
        }
    }

    public TagStatus startNewPassForTag(int tagId) {
        final Object tagLock = tagLockMap.computeIfAbsent(tagId, k -> new Object());
        synchronized (tagLock) {
            TagStatus ts = createNewTagStatus(tagId);
            long newId = db.tagStatusDao().insertSync(ts);
            ts.trackId = (int) newId;
            return ts;
        }
    }

//    public TagStatus getLatestTagStatusForId(int tagId){
//        Object tagLock = tagLockMap.computeIfAbsent(tagId, k -> new Object());
//        TagStatus ts;
//        synchronized (tagLock){
//            boolean needsInsert = false;
//            ts = db.tagStatusDao().getTagStatusForTagId(tagId);
//            if ( ts == null ){
//                ts = createNewTagStatus(tagId);
//                needsInsert = true;
//            }
//            ts.lastSeenMs = System.currentTimeMillis();
//            ts.friendlyName = resolveFriendlyName(tagId);
//            if ( needsInsert){
//                long newId = db.tagStatusDao().insertSync(ts);
//                ts.trackId = (int)newId;
//            }
//            return ts;
//        }
//    }
    public List<TagStatus> getOpenPassesSync() {
        return db.tagStatusDao().getOpenPassesSync();
    }

    private String resolveFriendlyName(int tagId) {
        return String.format("PT-%d",tagId);
    }

    // ===== Step-2: buffered TagData API =====
    public void appendInMemoryTagData(TagData tagData) {
        if (tagData == null) return;
        final int trackId = tagData.trackId;
        final Object tagLock = tagLockMap.computeIfAbsent(trackId, k -> new Object());
        synchronized (tagLock) {
            inMemoryTagDataBufferByTrack
                    .computeIfAbsent(trackId, __ -> new ArrayList<>())
                    .add(tagData);
        }
    }

    public List<TagData> getHistoryForTrackIdSyncCombined(int trackId) {
        List<TagData> persisted = db.tagDataDao().getSamplesForTrackIdSync(trackId);
        if (persisted == null) persisted = Collections.emptyList();

        List<TagData> copyBuffer = Collections.emptyList();
        final Object tagLock = tagLockMap.computeIfAbsent(trackId, k -> new Object());
        synchronized (tagLock) {
            List<TagData> buf = inMemoryTagDataBufferByTrack.get(trackId);
            if (buf != null && !buf.isEmpty()) copyBuffer = new ArrayList<>(buf);
        }

        if (copyBuffer.isEmpty()) return new ArrayList<>(persisted);
        List<TagData> combined = new ArrayList<>(persisted.size() + copyBuffer.size());
        combined.addAll(persisted);
        combined.addAll(copyBuffer);
        return combined;
    }

    public void clearInMemorySamplesForTrackId(int trackId) {
        final Object tagLock = tagLockMap.computeIfAbsent(trackId, k -> new Object());
        synchronized (tagLock) {
            inMemoryTagDataBufferByTrack.remove(trackId);
        }
    }

    public void restoreOpenPassBuffers(int maxPerTrack) {
        if (maxPerTrack <= 0) return;
        databaseWriteExecutor.execute(() -> {
            try {
                List<TagStatus> open = db.tagStatusDao().getOpenPassesSync();
                if (open == null || open.isEmpty()) return;

                for (TagStatus ts : open) {
                    final int trackId = ts.trackId;
                    List<TagData> all = db.tagDataDao().getSamplesForTrackIdSync(trackId);
                    if (all == null || all.isEmpty()) continue;

                    // take the last N in ascending time order
                    int from = Math.max(0, all.size() - maxPerTrack);
                    List<TagData> tail = new ArrayList<>(all.subList(from, all.size()));
                    tail.sort(Comparator.comparingLong(td -> td.timestampMs));

                    final Object tagLock = tagLockMap.computeIfAbsent(trackId, k -> new Object());
                    synchronized (tagLock) {
                        inMemoryTagDataBufferByTrack.put(trackId, new ArrayList<>(tail));
                    }
                }
            } catch (Throwable t) {
                // best-effort; no-op on failure
                t.printStackTrace();
            }
        });
    }

    public List<TagData> getSamplesForTrackIdSync(int trackId) {
        return  db.tagDataDao().getSamplesForTrackIdSync(trackId);
    }

    public List<TagStatus> getAllActiveTagsSync(){
        return db.tagStatusDao().getAllActiveSync();
    }

    public void insertTagData(TagData tagData) {
        databaseWriteExecutor.execute(() -> db.tagDataDao().insert(tagData));
    }

    // === RESTORED ===
    public LiveData<List<DebugTagData>> getLiveAllDebugTagData() {
        return db.tagDataDao().liveGetAllDebugTagData();
    }

    public LiveData<DataCount> getTotalDataCount(){
        MediatorLiveData<DataCount> mediatorLiveData = new MediatorLiveData<>();
        LiveData<Integer> samplesCountSource = db.tagDataDao().getTotalSamplesCount();
        LiveData<Integer> statusesCountSource = db.tagStatusDao().getStatusSampleCount();

        mediatorLiveData.addSource(samplesCountSource, sampleCount -> {
            Integer currentStatusesCount = statusesCountSource.getValue();
            if (currentStatusesCount != null) {
                mediatorLiveData.setValue(new DataCount(sampleCount, currentStatusesCount));
            }
        });

        mediatorLiveData.addSource(statusesCountSource, statusCount -> {
            Integer currentSampleCount = samplesCountSource.getValue();
            if (currentSampleCount != null) {
                mediatorLiveData.setValue(new DataCount(currentSampleCount, statusCount));
            }
        });

        return mediatorLiveData;
    }

    public void deleteSamplesForTrackId(int trackId) {
        databaseWriteExecutor.execute(() -> db.tagDataDao().deleteSamplesForTrackIdSync(trackId));
    }

    public AppDatabase getDatabase() { return db; }

    private void initializeDefaultSettingsInDbInternal() {
        Setting currentDbSetting = db.settingDao().getConfigSync(Setting.SETTINGS_ID);
        if (currentDbSetting == null) {
            Setting defaultSetting = new Setting();
            db.settingDao().upsertConfig(defaultSetting);
            mainThreadHandler.post(() -> areSettingsInitialized.setValue(true));
        } else {
            mainThreadHandler.post(() -> areSettingsInitialized.setValue(true));
        }
    }

    private void checkAndInitializeDefaultSettingsInternal() {
        Setting currentDbSetting = db.settingDao().getConfigSync(Setting.SETTINGS_ID);
        if (currentDbSetting == null) {
            initializeDefaultSettingsInDbInternal();
        } else {
            mainThreadHandler.post(() -> areSettingsInitialized.setValue(true));
        }
    }

    public LiveData<List<TagStatus>> getAllTagStatuses() {
        return db.tagStatusDao().liveAll();
    }

    public void upsertTagStatus(TagStatus s, boolean deleteSamples, @Nullable RepositoryCallback<Long> callback) {
        databaseWriteExecutor.execute(() -> {
            try {
                long rowId = db.tagStatusDao().upsertSync(s);
                if ( s.state == TagStatus.TagStatusState.LOGGED){
                    if (deleteSamples) {
                        clearInMemorySamplesForTrackId(s.trackId);
                        deleteSamplesForTrackId(s.trackId);
                    }
                }
                if (callback != null) mainThreadHandler.post(() -> callback.onSuccess(rowId));
            } catch (Exception e) {
                if (callback != null) mainThreadHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void upsertRacers(List<Racer> rs) {
        databaseWriteExecutor.execute(() -> db.racerDao().upsertAll(rs));
    }

    public LiveData<Racer> getRacer(int id) { return db.racerDao().liveGetById(id); }
    public LiveData<List<Racer>> getRacersForSplitAssignment(int splitAssignmentId) {
        return db.racerDao().liveGetBySplit(splitAssignmentId);
    }

    public LiveData<RaceContext> getLiveRaceContext() { return db.raceContextDao().liveLatest(); }
    public void upsertRaceContext(RaceContext c) {
        databaseWriteExecutor.execute(() -> db.raceContextDao().upsert(c));
    }

    private RaceContext makeTemporaryNewRace(){
        RaceContext ctx = new RaceContext();
        ctx.raceName="TestRace";
        ctx.eventName="TestEvent";
        ctx.id=1;
        return ctx;
    }

    public void setGunTime(long gunTimeMs, @Nullable RepositoryVoidCallback callback) {
        databaseWriteExecutor.execute(() -> {
            try {
                RaceContext ctx = db.raceContextDao().latestSync();
                if (ctx == null) ctx = makeTemporaryNewRace();
                ctx.gunTimeMs = gunTimeMs;
                db.raceContextDao().upsert(ctx);
                if (callback != null) mainThreadHandler.post(callback::onSuccess);
            } catch (Exception e) {
                if (callback != null) mainThreadHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void setGunTime(long gunTimeMs) { setGunTime(gunTimeMs, null); }

    public LiveData<Setting> getLiveConfig() {
        MediatorLiveData<Setting> mediatedLiveData = new MediatorLiveData<>();
        mediatedLiveData.addSource(areSettingsInitialized, initialized -> {
            if (Boolean.TRUE.equals(initialized)) {
                LiveData<Setting> actualLiveConfig = db.settingDao().getLiveConfig(Setting.SETTINGS_ID);
                mediatedLiveData.addSource(actualLiveConfig, setting -> {
                    if (setting == null) {
                        databaseWriteExecutor.execute(this::checkAndInitializeDefaultSettingsInternal);
                    } else {
                        mediatedLiveData.setValue(setting);
                    }
                });
                mediatedLiveData.removeSource(areSettingsInitialized);
            }
        });
        if (areSettingsInitialized.getValue() == null || !areSettingsInitialized.getValue()) {
            databaseWriteExecutor.execute(this::checkAndInitializeDefaultSettingsInternal);
        }
        return mediatedLiveData;
    }

    public void upsertConfig(Setting setting, @Nullable RepositoryVoidCallback callback) {
        setting.id = Setting.SETTINGS_ID;
        databaseWriteExecutor.execute(() -> {
            try {
                db.settingDao().upsertConfig(setting);
                if (callback != null) mainThreadHandler.post(callback::onSuccess);
            } catch (Exception e) {
                if (callback != null) mainThreadHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void clearAllData(boolean clearSettings,@Nullable RepositoryVoidCallback callback) {
        databaseWriteExecutor.execute(() -> {
            try {
                inMemoryTagDataBufferByTrack.clear();
                clearCalibrationBuffer();
                if ( clearSettings){
                    db.clearAllTables();
                } else {
                    db.clearAllTablesExceptSettings();
                }
                checkAndInitializeDefaultSettingsInternal();
                if (callback != null) mainThreadHandler.post(callback::onSuccess);
            } catch (Exception e) {
                if (callback != null) mainThreadHandler.post(() -> callback.onError(e));
            }
        });
    }

    // === Manual flush trigger (used by BleScannerService) ===
    public void flushPendingSamplesNow() {
        try {
            flushAllTagDataBuffersInternal();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    // ===== Step-2 internals: periodic flush =====
    private void flushAllTagDataBuffersInternal() {
        if (!savingEnabled) return;
        Map<Integer, List<TagData>> snap = snapshotInMemoryBuffers();
        if (snap.isEmpty()) return;
        databaseWriteExecutor.execute(() -> {
            try {
                flushSnapshotSync(snap);
            } catch (Exception ex) {
                // On failure, re-queue back into per-track buffer
                for (Map.Entry<Integer, List<TagData>> e : snap.entrySet()) {
                    final int trackId = e.getKey();
                    final Object tagLock = tagLockMap.computeIfAbsent(trackId, k -> new Object());
                    synchronized (tagLock) {
                        inMemoryTagDataBufferByTrack
                                .computeIfAbsent(trackId, __ -> new ArrayList<>())
                                .addAll(e.getValue());
                    }
                }
            }
        });
    }

    // ===== Step-5: calibration stream (mirror kept for compatibility) =====
    public LiveData<List<RssiData>> getLiveCalibrationRssiMirror() { return calibrationLive; }

    public void appendCalibrationSample(@NonNull RssiData s) {
        synchronized (calibrationLock) {
            if (calibrationBuffer.size() >= 2000) {
                calibrationBuffer.subList(0, 500).clear();
            }
            calibrationBuffer.add(s);
            calibrationLive.postValue(new ArrayList<>(calibrationBuffer));
        }
    }

    public void clearCalibrationBuffer() {
        synchronized (calibrationLock) {
            calibrationBuffer.clear();
            calibrationLive.postValue(new ArrayList<>());
        }
    }

    // ===== NEW: helper to fetch the latest open pass (not LOGGED/TIMED_OUT) =====
    public int getLatestOpenTrackIdForTag(int tagId) {
        TagStatus ts = db.tagStatusDao().getTagStatusForTagId(tagId);
        if (ts == null) return -1;
        if (ts.state == TagStatus.TagStatusState.LOGGED || ts.state == TagStatus.TagStatusState.TIMED_OUT) return -1;
        return ts.trackId;
    }


    // === NEW: snapshot + synchronous flush helpers ===
    private Map<Integer, List<TagData>> snapshotInMemoryBuffers() {
        final Map<Integer, List<TagData>> snapshot = new ConcurrentHashMap<>();
        for (Map.Entry<Integer, List<TagData>> e : inMemoryTagDataBufferByTrack.entrySet()) {
            final int trackId = e.getKey();
            final Object tagLock = tagLockMap.computeIfAbsent(trackId, k -> new Object());
            List<TagData> toFlush;
            synchronized (tagLock) {
                List<TagData> buf = inMemoryTagDataBufferByTrack.get(trackId);
                if (buf == null || buf.isEmpty()) continue;
                toFlush = new ArrayList<>(buf);
                buf.clear();
            }
            snapshot.put(trackId, toFlush);
        }
        return snapshot;
    }

    private void flushSnapshotSync(Map<Integer, List<TagData>> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return;
        // Optional: run in a transaction for speed/atomicity
        db.runInTransaction(() -> {
            for (Map.Entry<Integer, List<TagData>> e : snapshot.entrySet()) {
                List<TagData> items = e.getValue();
                if (items == null || items.isEmpty()) continue;
                db.tagDataDao().insertAll(items);
            }
        });
    }

    /** Public: block this thread until all in-memory TagData are written to DB. */
    public void flushPendingSamplesBlocking() {
        if (!savingEnabled) return;
        Map<Integer, List<TagData>> snap = snapshotInMemoryBuffers();
        if (snap.isEmpty()) return;
        flushSnapshotSync(snap);
    }

    /** Public: wait until all previously queued DB tasks have run. */
    public void drainDb() {
        final CountDownLatch latch = new CountDownLatch(1);
        databaseWriteExecutor.execute(latch::countDown);
        try {
            // Don’t hang forever; 5s is plenty for our tiny queue.
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ===== NEW: CSV export for a given track =====
    public void exportTrackCsv(int trackId, @NonNull OutputStream output, @Nullable RepositoryVoidCallback callback) {
        databaseWriteExecutor.execute(() -> {
            Exception error = null;
            try {
                List<TagData> rows = db.tagDataDao().getSamplesForTrackIdSync(trackId);
                // write header
                output.write("timestampMs,trackId,tagId,rssi\n".getBytes(StandardCharsets.UTF_8));

                int tagId = 0;
                TagStatus status = db.tagStatusDao().getByTrackIdSync(trackId);
                if (status != null) tagId = status.tagId;

                if (rows != null) {
                    StringBuilder sb = new StringBuilder(64 * Math.max(1, rows.size()));
                    for (TagData td : rows) {
                        sb.append(td.timestampMs).append(',')
                                .append(trackId).append(',')
                                .append(tagId).append(',')
                                .append(td.rssi).append('\n');
                    }
                    output.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
                output.flush();
            } catch (Exception e) {
                error = e;
            } finally {
                try { output.close(); } catch (Exception ignore) {}
            }
            if (callback != null) {
                final Exception e2 = error;
                mainThreadHandler.post(() -> {
                    if (e2 == null) callback.onSuccess();
                    else callback.onError(e2);
                });
            }
        });
    }
}
