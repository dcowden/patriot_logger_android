package com.patriotlogger.logger.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface TagStatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(TagStatus s);

    @Update
    void update(TagStatus s);

    @Query("SELECT * FROM TagStatus ORDER BY peakTimeMs DESC")
    LiveData<List<TagStatus>> liveAll();

    @Query("SELECT * FROM TagStatus WHERE tagId=:tagId LIMIT 1")
    TagStatus getSync(int tagId);

    @Query("DELETE FROM TagStatus")
    void clear();
}
