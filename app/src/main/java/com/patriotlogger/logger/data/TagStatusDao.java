package com.patriotlogger.logger.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import android.util.Log;
import java.util.List;

@Dao
public interface TagStatusDao {

    // --- Write Operations (Synchronous - to be called from Repository's background executor) ---
    @Insert
    long insertSync(TagStatus tagStatus);

    @Update
    void updateSync(TagStatus tagStatus);

    // default upsert (kept)
    default void upsert(TagStatus tagStatus) {
        if (tagStatus.trackId == 0) {
            long newTrackId = insertSync(tagStatus);
            if (tagStatus.trackId == 0) {
                tagStatus.trackId = (int) newTrackId;
            }
        } else {
            updateSync(tagStatus);
        }
    }
    // TagStatusDao.java
    @Query("SELECT * FROM tag_status WHERE tagId = :tagId ORDER BY lastSeenMs DESC LIMIT 1")
    TagStatus getLatestForTagIdSync(int tagId);

    // default upsertSync (kept)
    default long upsertSync(TagStatus tagStatus) {
        if (tagStatus.trackId == 0) {
            long s = System.currentTimeMillis();
            Log.d("TagStatusDao", "Insert existing with trackId: " + tagStatus.trackId + ", lastSeenMs=" + tagStatus.lastSeenMs+ " dt=" +  (System.currentTimeMillis() - s));
            long r = insertSync(tagStatus);
            Log.d("TagStatusDao", "Done");
            return r;
        } else {
            long s = System.currentTimeMillis();
            updateSync(tagStatus);
            Log.d("TagStatusDao", "Updating existing TagStatus with trackId: " + tagStatus.trackId + ", lastSeenMs=" + tagStatus.lastSeenMs + " dt=" +  (System.currentTimeMillis() - s));
            long r = tagStatus.trackId;
            Log.d("TagStatusDao", "Done");
            return r;
        }
    }

    @Query("DELETE FROM tag_status")
    void clear();

    // --- Read Operations ---
    @Query("SELECT COUNT(*) from tag_status;")
    LiveData<Integer> getStatusSampleCount();

    @Query("SELECT * FROM tag_status ORDER BY trackId DESC")
    LiveData<List<TagStatus>> liveAll();

    @Query("SELECT * FROM tag_status WHERE trackId = :trackId LIMIT 1")
    LiveData<TagStatus> liveGetByTrackId(int trackId);

    @Query("SELECT * FROM tag_status WHERE tagId = :tagId AND (state = 'APPROACHING' OR state = 'HERE') ORDER BY trackId DESC LIMIT 1")
    LiveData<TagStatus> liveGetActiveStatusForTagId(int tagId);

    @Query("SELECT * FROM tag_status WHERE tagId = :tagId AND state IN (:states) ORDER BY trackId DESC")
    LiveData<List<TagStatus>> liveGetByTagIdAndStates(int tagId, List<String> states);

    @Query("SELECT * FROM tag_status ORDER BY trackId DESC")
    List<TagStatus> getAllSync();

    @Query("SELECT * FROM tag_status WHERE state IN ('APPROACHING','HERE' ) ORDER BY trackId DESC")
    List<TagStatus> getAllActiveSync();

    @Query("SELECT * FROM tag_status WHERE trackId = :trackId LIMIT 1")
    TagStatus getByTrackIdSync(int trackId);

    @Query("SELECT * FROM tag_status WHERE tagId = :tagId AND state IN ('FIRST_SAMPLE','TOO_FAR','APPROACHING','HERE') ORDER BY trackId DESC LIMIT 1")
    TagStatus getTagStatusForTagId(int tagId);

    @Query("SELECT * FROM tag_status WHERE tagId = :tagId AND state IN (:states) ORDER BY trackId DESC")
    List<TagStatus> getByTagIdAndStatesSync(int tagId, List<String> states);

    // === NEW: open passes for warm-start (not LOGGED or TIMED_OUT) ===
    @Query("SELECT * FROM tag_status WHERE state IN ('FIRST_SAMPLE','TOO_FAR','APPROACHING','HERE') ORDER BY trackId ASC")
    List<TagStatus> getOpenPassesSync();
}
