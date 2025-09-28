package com.patriotlogger.logger.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {TagStatus.class, Racer.class, RaceContext.class, KalmanState.class}, version = 2) // Added KalmanState.class, incremented version
public abstract class AppDatabase extends RoomDatabase {
    public abstract TagStatusDao tagStatusDao();
    public abstract RacerDao racerDao();
    public abstract RaceContextDao raceContextDao();
    public abstract KalmanStateDao kalmanStateDao(); // Added KalmanStateDao
}
