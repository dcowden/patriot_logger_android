package com.patriotlogger.logger.data;

import android.content.Context;

import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.room.Room;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class Repository {
    private static volatile Repository instance;
    private final AppDatabase db;
    private final Executor io = Executors.newSingleThreadExecutor();

    private Repository(Context ctx) {
        db = Room.databaseBuilder(ctx.getApplicationContext(), AppDatabase.class, "psl.db")
                .fallbackToDestructiveMigration()
                .build();
    }

    public static Repository get(Context ctx) {
        if (instance == null) {
            synchronized (Repository.class) {
                if (instance == null) instance = new Repository(ctx);
            }
        }
        return instance;
    }

    // ---------- TagStatus ----------
    public LiveData<List<TagStatus>> liveTagStatuses() {
        return db.tagStatusDao().liveAll();
    }

    @WorkerThread
    public TagStatus getTagStatusByTrackIdNow(int trackId) {
        return db.tagStatusDao().getByTrackIdSync(trackId);
    }
    
    @WorkerThread
    public TagStatus getActiveTagStatusForTagIdNow(int tagId) {
        return db.tagStatusDao().getActiveStatusForTagIdSync(tagId);
    }
    
    @WorkerThread
    public TagStatus getTagStatusByTagIdAndStatesNow(int tagId, List<TagStatus.TagStatusState> states) {
        List<String> stateStrings = new ArrayList<>();
        for (TagStatus.TagStatusState state : states) {
            stateStrings.add(state.name());
        }
        List<TagStatus> resultList = db.tagStatusDao().getByTagIdAndStatesSync(tagId, stateStrings);
        if (resultList != null && !resultList.isEmpty()) {
            return resultList.get(0); // Return the first element if the list is not empty
        }
        return null; // Return null if no matching status is found or list is empty
    }

    public void upsertTagStatus(TagStatus s) {
        io.execute(() -> db.tagStatusDao().upsert(s));
    }
    
    @WorkerThread
    public int upsertTagStatusSync(TagStatus s) {
        return (int) db.tagStatusDao().upsertSync(s); 
    }
    
    @WorkerThread
    public List<TagStatus> getAllTagStatusesSync() {
        return db.tagStatusDao().getAllSync();
    }

    // ---------- TagData ----------
    public void insertTagData(TagData tagData) {
        io.execute(() -> db.tagDataDao().insert(tagData));
    }

    @WorkerThread
    public List<TagData> getSamplesForTrackIdSync(int trackId) {
        return db.tagDataDao().getSamplesForTrackIdSync(trackId);
    }
    
    @WorkerThread
    public List<TagData> getAllTagDataSync() {
        return db.tagDataDao().getAllTagDataSync();
    }

    public LiveData<Integer> getTotalSamplesCount() {
        return db.tagDataDao().getTotalSamplesCount();
    }
    
    @WorkerThread
    public int getTotalSamplesCountSync() {
        return db.tagDataDao().getTotalSamplesCountSync();
    }

    public void deleteSamplesForTrackId(int trackId) {
        io.execute(() -> db.tagDataDao().deleteSamplesForTrackIdSync(trackId));
    }

    // ---------- Racer ----------
    public void upsertRacers(List<Racer> rs) {
        io.execute(() -> db.racerDao().upsertAll(rs));
    }

    @WorkerThread
    public Racer getRacerNow(int id) {
        return db.racerDao().getSync(id);
    }

    @WorkerThread
    public List<Racer> getRacersForSplitAssignmentSync(int splitAssignmentId) {
        return db.racerDao().getBySplitSync(splitAssignmentId); // Corrected method name
    }

    // ---------- RaceContext ----------
    public LiveData<RaceContext> liveContext() {
        return db.raceContextDao().liveLatest();
    }

    @WorkerThread
    public RaceContext latestContextNow() {
        return db.raceContextDao().latestSync();
    }

    public void upsertContext(RaceContext c) {
        io.execute(() -> db.raceContextDao().upsert(c));
    }

    public void setGunTime(long gunTimeMs) {
        io.execute(() -> {
            RaceContext ctx = db.raceContextDao().latestSync(); 
            if (ctx != null) {
                ctx.gunTimeMs = gunTimeMs;
                db.raceContextDao().upsert(ctx);
            }
        });
    }

    // ---------- Settings (Config) ----------
    @WorkerThread
    public Setting getConfigSync() {
        return db.settingDao().getConfigSync(Setting.SETTINGS_ID);
    }

    public LiveData<Setting> getLiveConfig() {
        return db.settingDao().getLiveConfig(Setting.SETTINGS_ID);
    }

    public void upsertConfig(Setting setting) {
        // Ensure the setting being upserted always uses the correct ID
        setting.id = Setting.SETTINGS_ID;
        io.execute(() -> db.settingDao().upsertConfig(setting));
    }
    

    // ---------- Maintenance ----------
    public void clearAll() {
        io.execute(() -> {
            db.tagStatusDao().clear();
            db.tagDataDao().clear();
            db.racerDao().clear();
            db.raceContextDao().clear();
            db.settingDao().clear(); 
        });
    }
}
