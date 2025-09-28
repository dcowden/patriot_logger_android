package com.patriotlogger.logger.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ForeignKey;
import androidx.room.Index;

/**
 * Represents the persisted state of a Kalman1D filter for a specific tag.
 */
@Entity(tableName = "kalman_states",
        foreignKeys = @ForeignKey(entity = TagStatus.class,
                                   parentColumns = "tagId",
                                   childColumns = "tagId",
                                   onDelete = ForeignKey.CASCADE),
        indices = {@Index(value = "tagId", unique = true)}) // Ensure only one Kalman state per tagId
public class KalmanState {

    @PrimaryKey
    public int tagId;       // Foreign key to TagStatus.tagId

    public float q;         // Process noise covariance from Kalman1D
    public float r;         // Measurement noise covariance from Kalman1D
    public float p;         // Estimation error covariance from Kalman1D
    public float x;         // Estimated value from Kalman1D
    public boolean initialized; // Initialization state from Kalman1D

    public KalmanState(int tagId, float q, float r, float p, float x, boolean initialized) {
        this.tagId = tagId;
        this.q = q;
        this.r = r;
        this.p = p;
        this.x = x;
        this.initialized = initialized;
    }

    // It's good practice for Room entities to have a no-arg constructor if possible,
    // but since all fields are fundamental to the state, we'll rely on the parameterized one.
    // If Room complains, a no-arg constructor might be needed with setters, or use @Ignore.
}
