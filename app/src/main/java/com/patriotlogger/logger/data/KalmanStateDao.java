package com.patriotlogger.logger.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

/**
 * Data Access Object for KalmanState entities.
 */
@Dao
public interface KalmanStateDao {

    /**
     * Inserts or updates a KalmanState.
     * If a KalmanState with the same tagId already exists, it will be replaced.
     * @param kalmanState The KalmanState object to upsert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(KalmanState kalmanState);

    /**
     * Retrieves a KalmanState by its tagId.
     * This method should be called from a background thread as it performs a synchronous query.
     * @param tagId The tagId to search for.
     * @return The KalmanState object if found, null otherwise.
     */
    @Query("SELECT * FROM kalman_states WHERE tagId = :tagId")
    KalmanState getByTagIdSync(int tagId);

    /**
     * Deletes a KalmanState by its tagId.
     * Useful if a tag is completely removed or reset.
     * @param tagId The tagId of the KalmanState to delete.
     */
    @Query("DELETE FROM kalman_states WHERE tagId = :tagId")
    void deleteByTagId(int tagId);

    /**
     * Clears all KalmanState entries from the table.
     */
    @Query("DELETE FROM kalman_states")
    void clear();
}
