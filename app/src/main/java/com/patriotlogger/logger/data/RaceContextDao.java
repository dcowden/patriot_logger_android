package com.patriotlogger.logger.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface RaceContextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(RaceContext ctx);

    @Query("SELECT * FROM RaceContext ORDER BY createdAtMs DESC LIMIT 1")
    LiveData<RaceContext> liveLatest();

    @Query("SELECT * FROM RaceContext ORDER BY createdAtMs DESC LIMIT 1")
    RaceContext latestSync();

    @Query("DELETE FROM RaceContext")
    void clear();
}
