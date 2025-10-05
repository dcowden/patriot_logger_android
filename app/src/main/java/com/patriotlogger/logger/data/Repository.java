package com.patriotlogger.logger.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.patriotlogger.logger.util.ThrottledLiveData;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Repository {
    private static final String TAG = "Repository";
    private static volatile Repository instance;
    private final AppDatabase db;
    private final ExecutorService databaseWriteExecutor;
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    private final MutableLiveData<Boolean> areSettingsInitialized = new MutableLiveData<>(false);

    private Repository(Context ctx) {
        databaseWriteExecutor = Executors.newSingleThreadExecutor();
        db = Room.databaseBuilder(ctx.getApplicationContext(), AppDatabase.class, "psl.db")
                .fallbackToDestructiveMigration()
                .addCallback(new RoomDatabase.Callback() {
                    @Override
                    public void onCreate(@NonNull SupportSQLiteDatabase _db) {
                        super.onCreate(_db);
                        databaseWriteExecutor.execute(Repository.this::initializeDefaultSettingsInDbInternal);
                    }

                    @Override
                    public void onOpen(@NonNull SupportSQLiteDatabase _db) {
                        super.onOpen(_db);
                        databaseWriteExecutor.execute(Repository.this::checkAndInitializeDefaultSettingsInternal);
                    }
                })
                .build();
    }

    public static Repository get(@NonNull Context context) {
        if (instance == null) {
            synchronized (Repository.class) {
                if (instance == null) {
                    instance = new Repository(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    // --- TagData ---
    public LiveData<List<DebugTagData>> getLiveAllDebugTagData() {
        return db.tagDataDao().liveGetAllDebugTagData();
    }

    public LiveData<List<TagData>> getLiveAllTagDataDesc() {
        return db.tagDataDao().liveGetAllTagDataDesc();
    }

    public LiveData<List<TagData>> getThrottledLiveAllTagDataDesc(long throttleMs) {
        return new ThrottledLiveData<>(db.tagDataDao().liveGetAllTagDataDesc(), throttleMs);
    }

    public void insertTagData(TagData tagData) {
        databaseWriteExecutor.execute(() -> db.tagDataDao().insert(tagData));
    }

    public LiveData<List<TagData>> getSamplesForTrackId(int trackId) {
        return db.tagDataDao().liveGetSamplesForTrackId(trackId);
    }

    public LiveData<List<TagData>> getAllTagData() {
        return db.tagDataDao().liveGetAllTagData();
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
    public LiveData<Integer> getTotalSamplesCount() {
        return db.tagDataDao().getTotalSamplesCount();
    }

    public void deleteSamplesForTrackId(int trackId) {
        databaseWriteExecutor.execute(() -> db.tagDataDao().deleteSamplesForTrackIdSync(trackId));
    }

    // --- The rest of your Repository code... ---

    public AppDatabase getDatabase() {
        return db;
    }

    public void shutdownExecutor() {
        if (databaseWriteExecutor != null && !databaseWriteExecutor.isShutdown()) {
            databaseWriteExecutor.shutdown();
        }
    }

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

    public LiveData<List<TagStatus>> getThrottledAllTagStatuses(long throttleMs) {
        return new ThrottledLiveData<>(db.tagStatusDao().liveAll(), throttleMs);
    }

    public LiveData<TagStatus> getTagStatusByTrackId(int trackId) {
        return db.tagStatusDao().liveGetByTrackId(trackId);
    }

    public LiveData<TagStatus> getActiveTagStatusForTagId(int tagId) {
        return db.tagStatusDao().liveGetActiveStatusForTagId(tagId);
    }

    public void upsertTagStatus(TagStatus s) {
        databaseWriteExecutor.execute(() -> db.tagStatusDao().upsert(s));
    }

    public void upsertTagStatus(TagStatus s, @Nullable RepositoryCallback<Long> callback) {
        databaseWriteExecutor.execute(() -> {
            try {
                long rowId = db.tagStatusDao().upsertSync(s);
                if (callback != null) mainThreadHandler.post(() -> callback.onSuccess(rowId));
            } catch (Exception e) {
                if (callback != null) mainThreadHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void upsertRacers(List<Racer> rs) {
        databaseWriteExecutor.execute(() -> db.racerDao().upsertAll(rs));
    }

    public LiveData<Racer> getRacer(int id) {
        return db.racerDao().liveGetById(id);
    }

    public LiveData<List<Racer>> getRacersForSplitAssignment(int splitAssignmentId) {
        return db.racerDao().liveGetBySplit(splitAssignmentId);
    }

    public LiveData<RaceContext> getLiveRaceContext() {
        return db.raceContextDao().liveLatest();
    }

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
                if (ctx == null) {
                    ctx = makeTemporaryNewRace();
                }
                ctx.gunTimeMs = gunTimeMs;
                db.raceContextDao().upsert(ctx);
                if (callback != null) mainThreadHandler.post(callback::onSuccess);
            } catch (Exception e) {
                if (callback != null) mainThreadHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void setGunTime(long gunTimeMs) {
        setGunTime(gunTimeMs, null);
    }

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

    public void upsertConfig(Setting setting) {
        upsertConfig(setting, null);
    }

    public void clearAllData(boolean clearSettings,@Nullable RepositoryVoidCallback callback) {
        databaseWriteExecutor.execute(() -> {
            try {
                if ( clearSettings){
                    db.clearAllTables();
                }
                else{
                    db.clearAllTablesExceptSettings();
                }

                checkAndInitializeDefaultSettingsInternal();
                if (callback != null) mainThreadHandler.post(callback::onSuccess);
            } catch (Exception e) {
                if (callback != null) mainThreadHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void clearAllData(boolean clearSettings) {
        clearAllData(clearSettings, null);
    }
}
