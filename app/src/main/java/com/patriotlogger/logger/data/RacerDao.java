package com.patriotlogger.logger.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface RacerDao {

    // --- Write Operations (Synchronous) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<Racer> racers);

    @Query("DELETE FROM racers")
    void clear();


    // --- Read Operations (Returning LiveData) ---

    @Query("SELECT * FROM racers WHERE id = :id LIMIT 1") // Corrected table name from original error
    LiveData<Racer> liveGetById(int id); // NEW: LiveData version

    @Query("SELECT * FROM racers ORDER BY name ASC")
    LiveData<List<Racer>> liveAll(); // You already had this - good!

    @Query("SELECT * FROM racers WHERE splitAssignmentId = :splitAssignmentId")
    LiveData<List<Racer>> liveGetBySplit(int splitAssignmentId); // NEW: LiveData version


    // --- Synchronous Read Operations (Keep if needed by Repository for internal background logic) ---

    @Query("SELECT * FROM racers WHERE id = :id LIMIT 1")
    Racer getSync(int id);

    @Query("SELECT * FROM racers WHERE splitAssignmentId = :splitAssignmentId")
    List<Racer> getBySplitSync(int splitAssignmentId);
}