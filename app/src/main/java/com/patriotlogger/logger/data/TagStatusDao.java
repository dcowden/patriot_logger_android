package com.patriotlogger.logger.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
// import androidx.room.TypeConverters; // Removed as StateConverter.class was undefined/not needed

import java.util.List;

@Dao
// @TypeConverters(StateConverter.class) // Removed: StateConverter.class was likely undefined or unused.
                                         // TagStatusStateConverter is defined at AppDatabase level for TagStatus.TagStatusState.
public interface TagStatusDao {

    @Insert
    long insertSync(TagStatus tagStatus); 

    @Update
    void updateSync(TagStatus tagStatus);

    default void upsert(TagStatus tagStatus) {
        if (tagStatus.trackId == 0) { 
            tagStatus.trackId = (int) insertSync(tagStatus); 
        } else {
            updateSync(tagStatus);
        }
    }
    
    default long upsertSync(TagStatus tagStatus) {
        if (tagStatus.trackId == 0) {
            return insertSync(tagStatus);
        } else {
            updateSync(tagStatus);
            return tagStatus.trackId;
        }
    }

    @Query("SELECT * FROM tag_status ORDER BY trackId DESC")
    LiveData<List<TagStatus>> liveAll();

    @Query("SELECT * FROM tag_status ORDER BY trackId DESC")
    List<TagStatus> getAllSync();

    @Query("SELECT * FROM tag_status WHERE trackId = :trackId LIMIT 1")
    TagStatus getByTrackIdSync(int trackId);

    @Query("SELECT * FROM tag_status WHERE tagId = :tagId AND (state = 'APPROACHING' OR state = 'HERE') ORDER BY trackId DESC LIMIT 1")
    TagStatus getActiveStatusForTagIdSync(int tagId);
    
    @Query("SELECT * FROM tag_status WHERE tagId = :tagId AND state IN (:states) ORDER BY trackId DESC")
    List<TagStatus> getByTagIdAndStatesSync(int tagId, List<String> states); // Note: getByTagIdAndStatesSync not getByTagIdAndStateSync

    @Query("DELETE FROM tag_status")
    void clear();
}
