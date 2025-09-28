package com.patriotlogger.logger.data;

import android.content.Context;

import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.room.Room;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Single entry point to Room. All writes go through a single IO executor.
 * Never call DAO methods from Activities/Fragments directly.
 */
public final class Repository {
    private static volatile Repository instance;
    private final AppDatabase db;
    private final Executor io = Executors.newSingleThreadExecutor();

    private Repository(Context ctx) {
        // Remember to increment DB version in AppDatabase if schema changes
        db = Room.databaseBuilder(ctx.getApplicationContext(), AppDatabase.class, "psl.db")
                .fallbackToDestructiveMigration() // Existing strategy, fine for dev
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

    /** For background use only (e.g., from services/workers). */
    @WorkerThread
    public TagStatus getTagStatusNow(int id) {
        return db.tagStatusDao().getSync(id);
    }

    public void upsertTagStatus(TagStatus s) {
        io.execute(() -> db.tagStatusDao().upsert(s));
    }

    // ---------- KalmanState ----------
    /** For background use only (e.g., from services/workers or new logic class). */
    @WorkerThread
    public KalmanState getKalmanStateByTagIdSync(int tagId) {
        return db.kalmanStateDao().getByTagIdSync(tagId);
    }

    public void upsertKalmanState(KalmanState kalmanState) {
        io.execute(() -> db.kalmanStateDao().upsert(kalmanState));
    }

    public void deleteKalmanStateByTagId(int tagId) {
        io.execute(() -> db.kalmanStateDao().deleteByTagId(tagId));
    }

    // ---------- Racer ----------
    public void upsertRacers(List<Racer> rs) {
        io.execute(() -> db.racerDao().upsertAll(rs));
    }

    /** For background use only. */
    @WorkerThread
    public Racer getRacerNow(int id) {
        return db.racerDao().getSync(id);
    }

    // ---------- RaceContext ----------
    public LiveData<RaceContext> liveContext() {
        return db.raceContextDao().liveLatest();
    }

    /** For background use only. */
    @WorkerThread
    public RaceContext latestContextNow() {
        return db.raceContextDao().latestSync();
    }

    /** Persist full context (background). */
    public void upsertContext(RaceContext c) {
        io.execute(() -> db.raceContextDao().upsert(c));
    }

    /**
     * Set/override gun time for the latest context.
     * Runs entirely off the UI thread to satisfy Room’s threading rules.
     */
    public void setGunTime(long gunTimeMs) {
        io.execute(() -> {
            RaceContext ctx = db.raceContextDao().latestSync(); // background thread
            if (ctx != null) {
                ctx.gunTimeMs = gunTimeMs;
                db.raceContextDao().upsert(ctx);
            }
        });
    }

    // ---------- Synchronous bulk queries (use with caution, background only) ----------
    /** For background use only. */
    @WorkerThread
    public List<TagStatus> allTagStatusesNow() {
        // This was previously returning liveAll().getValue() which can be problematic.
        // If a truly synchronous list is needed, a new DAO method for it would be better.
        // For now, assuming this is for specific background tasks and liveData.getValue() was intentional.
        return db.tagStatusDao().liveAll().getValue(); // Keep an eye on this if it causes issues.
    }

    /** For background use only. */
    @WorkerThread
    public List<Racer> racersForSplitNow(int splitAssignmentId) {
        return db.racerDao().getBySplitSync(splitAssignmentId);
    }

    // ---------- Maintenance ----------
    public void clearAll() {
        io.execute(() -> {
            db.tagStatusDao().clear();
            db.racerDao().clear();
            db.raceContextDao().clear();
            db.kalmanStateDao().clear(); // Added to clear Kalman states as well
        });
    }
}
