package com.patriotlogger.logger.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TagDataDao {

    @Query("SELECT td.dataId, td.trackId, ts.tagId, td.timestampMs, td.rssi " +
           "FROM tag_data AS td " +
           "INNER JOIN tag_status AS ts ON td.trackId = ts.trackId " +
           "ORDER BY td.timestampMs DESC")
    LiveData<List<DebugTagData>> liveGetAllDebugTagData();


    @Insert
    void insert(TagData tagData);

    @Query("DELETE FROM tag_data WHERE trackId = :trackId")
    void deleteSamplesForTrackIdSync(int trackId);

    @Query("DELETE FROM tag_data")
    void clear();

    @Query("SELECT * FROM tag_data WHERE trackId = :trackId ORDER BY timestampMs ASC")
    LiveData<List<TagData>> liveGetSamplesForTrackId(int trackId);

    @Query("SELECT * FROM tag_data ORDER BY timestampMs ASC")
    LiveData<List<TagData>> liveGetAllTagData();

    @Query("SELECT * FROM tag_data ORDER BY timestampMs DESC") // New query for calibration
    LiveData<List<TagData>> liveGetAllTagDataDesc();

    @Query("SELECT COUNT(*) FROM tag_data")
    LiveData<Integer> getTotalSamplesCount();

    @Query("SELECT * FROM tag_data WHERE trackId = :trackId ORDER BY timestampMs ASC")
    List<TagData> getSamplesForTrackIdSync(int trackId);

    @Query("SELECT * FROM tag_data ORDER BY timestampMs ASC")
    List<TagData> getAllTagDataSync();

    @Query("SELECT COUNT(*) FROM tag_data")
    int getTotalSamplesCountSync();
}
