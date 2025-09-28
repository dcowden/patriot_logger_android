package com.patriotlogger.logger.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface RacerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<Racer> racers);

    @Query("SELECT * FROM racers WHERE id=:id LIMIT 1")
    Racer getSync(int id); // call only from background threads

    // Optional: handy for UI binding/debug lists if you ever need it.
    @Query("SELECT * FROM racers ORDER BY name ASC")
    LiveData<List<Racer>> liveAll();

    @Query("SELECT * FROM racers WHERE splitAssignmentId = :splitAssignmentId")
    List<Racer> getBySplitSync(int splitAssignmentId);

    @Query("DELETE FROM racers")
    void clear();
}
