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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Repository {
    private static final String TAG = "Repository"; // Added for logging from settings part
    private static volatile Repository instance;
    private final AppDatabase db;
    private final ExecutorService databaseWriteExecutor;
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper()); // For callbacks

    // To manage initialization of default settings
    private final MutableLiveData<Boolean> areSettingsInitialized = new MutableLiveData<>(false);


    private Repository(Context ctx) {
        db = Room.databaseBuilder(ctx.getApplicationContext(), AppDatabase.class, "psl.db")
                .fallbackToDestructiveMigration()
                .addCallback(new RoomDatabase.Callback() { // Callback to initialize settings
                    @Override
                    public void onCreate(@NonNull SupportSQLiteDatabase _db) {
                        super.onCreate(_db);
                        Log.i(TAG, "Database created. Initializing default settings.");
                        // This runs on a Room background thread
                        initializeDefaultSettingsInDbInternal();
                    }

                    @Override
                    public void onOpen(@NonNull SupportSQLiteDatabase _db) {
                        super.onOpen(_db);
                        // Also check on open, in case DB existed but settings were cleared or app was updated
                        // Run this on our executor to avoid blocking Room's open thread for too long
                        databaseWriteExecutor.execute(Repository.this::checkAndInitializeDefaultSettingsInternal);
                    }
                })
                .build();
        databaseWriteExecutor = Executors.newSingleThreadExecutor();
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

    public AppDatabase getDatabase() {
        return db;
    }

    public void shutdownExecutor() {
        if (databaseWriteExecutor != null && !databaseWriteExecutor.isShutdown()) {
            databaseWriteExecutor.shutdown();
        }
    }

    // --- Settings Initialization Logic (Internal) ---
    private void initializeDefaultSettingsInDbInternal() {
        // This is called from Room's onCreate or onOpen(via executor)
        // No need for another executor.execute() here
        Setting currentDbSetting = db.settingDao().getConfigSync(Setting.SETTINGS_ID);
        if (currentDbSetting == null) {
            Setting defaultSetting = new Setting(); // Instantiates with defaults from Setting class
            db.settingDao().upsertConfig(defaultSetting);
            Log.i(TAG, "Initialized default settings in DB.");
            mainThreadHandler.post(() -> areSettingsInitialized.setValue(true));
        } else {
            Log.i(TAG, "Settings already exist in DB.");
            mainThreadHandler.post(() -> areSettingsInitialized.setValue(true));
        }
    }

    private void checkAndInitializeDefaultSettingsInternal() {
        // Called from onOpen
        Setting currentDbSetting = db.settingDao().getConfigSync(Setting.SETTINGS_ID);
        if (currentDbSetting == null) {
            Log.i(TAG, "No settings found on DB open, ensuring defaults exist.");
            initializeDefaultSettingsInDbInternal();
        } else {
            mainThreadHandler.post(() -> areSettingsInitialized.setValue(true));
        }
    }


    // ---------- TagStatus ----------
    public LiveData<List<TagStatus>> getAllTagStatuses() {
        return db.tagStatusDao().liveAll();
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

    // ---------- TagData ----------
    public void insertTagData(TagData tagData) {
        databaseWriteExecutor.execute(() -> db.tagDataDao().insert(tagData));
    }
    public LiveData<List<TagData>> getSamplesForTrackId(int trackId) {
        return db.tagDataDao().liveGetSamplesForTrackId(trackId);
    }
    public LiveData<List<TagData>> getAllTagData() {
        return db.tagDataDao().liveGetAllTagData();
    }
    public LiveData<Integer> getTotalSamplesCount() {
        return db.tagDataDao().getTotalSamplesCount();
    }
    public void deleteSamplesForTrackId(int trackId) {
        databaseWriteExecutor.execute(() -> db.tagDataDao().deleteSamplesForTrackIdSync(trackId));
    }

    // ---------- Racer ----------
    public void upsertRacers(List<Racer> rs) {
        databaseWriteExecutor.execute(() -> db.racerDao().upsertAll(rs));
    }
    public LiveData<Racer> getRacer(int id) {
        return db.racerDao().liveGetById(id);
    }
    public LiveData<List<Racer>> getRacersForSplitAssignment(int splitAssignmentId) {
        return db.racerDao().liveGetBySplit(splitAssignmentId);
    }

    // ---------- RaceContext ----------
    public LiveData<RaceContext> getLiveRaceContext() {
        return db.raceContextDao().liveLatest();
    }
    public void upsertRaceContext(RaceContext c) {
        databaseWriteExecutor.execute(() -> db.raceContextDao().upsert(c));
    }
    public void setGunTime(long gunTimeMs, @Nullable RepositoryVoidCallback callback) {
        databaseWriteExecutor.execute(() -> {
            try {
                RaceContext ctx = db.raceContextDao().latestSync();
                if (ctx != null) {
                    ctx.gunTimeMs = gunTimeMs;
                    db.raceContextDao().upsert(ctx);
                    if (callback != null) mainThreadHandler.post(callback::onSuccess);
                } else {
                    if (callback != null) mainThreadHandler.post(() -> callback.onError(new IllegalStateException("No RaceContext found.")));
                }
            } catch (Exception e) {
                if (callback != null) mainThreadHandler.post(() -> callback.onError(e));
            }
        });
    }
    public void setGunTime(long gunTimeMs) {
        setGunTime(gunTimeMs, null);
    }


    // ---------- Settings (Config) - Merged from SettingsManager ----------

    /**
     * Observes the application settings/config.
     * It ensures default settings are initialized if they don't exist.
     */
    public LiveData<Setting> getLiveConfig() {
        // MediatorLiveData to ensure settings are initialized before emitting
        MediatorLiveData<Setting> mediatedLiveData = new MediatorLiveData<>();
        mediatedLiveData.addSource(areSettingsInitialized, initialized -> {
            if (Boolean.TRUE.equals(initialized)) {
                // Now safe to get the actual live config
                LiveData<Setting> actualLiveConfig = db.settingDao().getLiveConfig(Setting.SETTINGS_ID);
                mediatedLiveData.addSource(actualLiveConfig, setting -> {
                    if (setting == null) {
                        // This case should be rare if initialization logic works on onCreate/onOpen
                        Log.w(TAG, "LiveConfig emitted null after initialization! Re-checking defaults.");
                        databaseWriteExecutor.execute(this::checkAndInitializeDefaultSettingsInternal);
                        // Optionally, could emit a default new Setting() here temporarily,
                        // but better to let the DB trigger a proper update.
                        // mediatedLiveData.setValue(new Setting()); // Temporary default
                    } else {
                        mediatedLiveData.setValue(setting);
                    }
                });
                // Remove the initialization source once done
                mediatedLiveData.removeSource(areSettingsInitialized);
            }
        });
        // Trigger initial check if not already done by Room callbacks
        if (areSettingsInitialized.getValue() == null || !areSettingsInitialized.getValue()) {
            databaseWriteExecutor.execute(this::checkAndInitializeDefaultSettingsInternal);
        }
        return mediatedLiveData;
    }


    /**
     * For one-shot read of config. Ensures defaults are checked before attempting read.
     * Returns LiveData which will emit the setting once fetched (or null if not found after check).
     */
    public LiveData<Setting> getConfigOnce() {
        MutableLiveData<Setting> settingLiveData = new MutableLiveData<>();
        // Ensure settings are initialized before fetching
        areSettingsInitialized.observeForever(new androidx.lifecycle.Observer<Boolean>() {
            @Override
            public void onChanged(Boolean initialized) {
                if (Boolean.TRUE.equals(initialized)) {
                    databaseWriteExecutor.execute(() -> {
                        Setting setting = db.settingDao().getConfigSync(Setting.SETTINGS_ID);
                        if (setting == null) { // Should be rare after initialization
                            Log.w(TAG, "getConfigOnce: Setting still null after init, providing default instance.");
                            settingLiveData.postValue(new Setting()); // Post a default instance
                        } else {
                            settingLiveData.postValue(setting);
                        }
                    });
                    areSettingsInitialized.removeObserver(this); // Clean up observer
                }
            }
        });
        // Trigger initial check if not already done
        if (areSettingsInitialized.getValue() == null || !areSettingsInitialized.getValue()) {
            databaseWriteExecutor.execute(this::checkAndInitializeDefaultSettingsInternal);
        }
        return settingLiveData;
    }

    /**
     * Internal synchronous fetch, primarily for initialization checks.
     * Avoid calling this directly from UI thread.
     */
    private Setting getConfigSyncInternal() {
        return db.settingDao().getConfigSync(Setting.SETTINGS_ID);
    }


    /**
     * Inserts or updates settings asynchronously.
     * Ensures the setting ID is correct.
     */
    public void upsertConfig(Setting setting, @Nullable RepositoryVoidCallback callback) {
        setting.id = Setting.SETTINGS_ID; // Ensure correct ID
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


    // --- Specific Setting Accessors ---
    // These now need to fetch the setting object first, then access its fields.
    // They are less efficient if calling multiple getters.
    // It's often better for the ViewModel to observe getLiveConfig() and manage the Setting object.

    /**
     * Asynchronously sets the 'retain_samples' setting.
     */
    public void setRetainSamples(boolean value, @Nullable RepositoryVoidCallback callback) {
        databaseWriteExecutor.execute(() -> {
            try {
                Setting current = db.settingDao().getConfigSync(Setting.SETTINGS_ID);
                if (current == null) { // Should be initialized by now
                    Log.w(TAG, "setRetainSamples: Current settings null, creating new with default.");
                    current = new Setting(); // Should use defaults
                }
                current.retain_samples = value;
                current.id = Setting.SETTINGS_ID; // Ensure ID
                db.settingDao().upsertConfig(current);
                if (callback != null) mainThreadHandler.post(callback::onSuccess);
            } catch (Exception e) {
                if (callback != null) mainThreadHandler.post(() -> callback.onError(e));
            }
        });
    }
    public void setRetainSamples(boolean value) {
        setRetainSamples(value, null);
    }


    /**
     * Asynchronously sets the 'arrived_threshold' setting.
     */
    public void setArrivedThreshold(int value, @Nullable RepositoryVoidCallback callback) {
        databaseWriteExecutor.execute(() -> {
            try {
                Setting current = db.settingDao().getConfigSync(Setting.SETTINGS_ID);
                if (current == null) {
                    Log.w(TAG, "setArrivedThreshold: Current settings null, creating new with default.");
                    current = new Setting(); // Should use defaults
                }
                current.arrived_threshold = value;
                current.id = Setting.SETTINGS_ID; // Ensure ID
                db.settingDao().upsertConfig(current);
                if (callback != null) mainThreadHandler.post(callback::onSuccess);
            } catch (Exception e) {
                if (callback != null) mainThreadHandler.post(() -> callback.onError(e));
            }
        });
    }
    public void setArrivedThreshold(int value) {
        setArrivedThreshold(value, null);
    }


    // ---------- Maintenance ----------
    public void clearAllData(@Nullable RepositoryVoidCallback callback) {
        databaseWriteExecutor.execute(() -> {
            try {
                db.tagStatusDao().clear();
                db.tagDataDao().clear();
                db.racerDao().clear();
                db.raceContextDao().clear();
                db.settingDao().clear(); // This will clear settings
                Log.i(TAG, "All data cleared. Default settings will be re-initialized on next access/app start.");
                // Trigger re-initialization check for settings
                checkAndInitializeDefaultSettingsInternal();
                if (callback != null) mainThreadHandler.post(callback::onSuccess);
            } catch (Exception e) {
                if (callback != null) mainThreadHandler.post(() -> callback.onError(e));
            }
        });
    }
    public void clearAllData() {
        clearAllData(null);
    }
}
