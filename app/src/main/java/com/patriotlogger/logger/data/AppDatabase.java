package com.patriotlogger.logger.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

// Schema changed, incremented version to 9
@Database(entities = {TagStatus.class, Racer.class, RaceContext.class, Setting.class, TagData.class}, version = 9)
@TypeConverters({TagStatusStateConverter.class})
public abstract class AppDatabase extends RoomDatabase {
    public abstract TagStatusDao tagStatusDao();
    public abstract RacerDao racerDao();
    public abstract RaceContextDao raceContextDao();
    public abstract SettingDao settingDao();
    public abstract TagDataDao tagDataDao();

    public void clearAllTablesExceptSettings() {
        // Run the clear operations in a single transaction
        runInTransaction(() -> {
            tagStatusDao().clear();
            racerDao().clear();
            raceContextDao().clear();
            tagDataDao().clear();
            // Note: We DO NOT call settingDao().clear()
        });
    }
}
