package com.patriotlogger.logger.data;

import androidx.room.TypeConverter;

// Renamed from StateConverter
public class TagStatusStateConverter {
    @TypeConverter
    public static TagStatus.TagStatusState fromString(String value) {
        return value == null ? null : TagStatus.TagStatusState.valueOf(value);
    }

    @TypeConverter
    public static String toString(TagStatus.TagStatusState state) {
        return state == null ? null : state.name();
    }
}
