package com.patriotlogger.logger.data;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

@Entity(tableName = "racers")
public class Racer {

    @PrimaryKey
    public int id;

    @NonNull
    public String name;

    public int splitAssignmentId; // Ensure this field exists if you query by it

    // THIS IS ESSENTIAL FOR ROOM
    public Racer() {
    }

    // Your other constructor
    public Racer(int id, @NonNull String name, int splitAssignmentId) { // Added splitAssignmentId here too for completeness
        this.id = id;
        this.name = name;
        this.splitAssignmentId = splitAssignmentId;
    }

    // Simpler constructor if splitAssignmentId is set separately or nullable
    @Ignore
    public Racer(int id, @NonNull String name) {
        this.id = id;
        this.name = name;
        // this.splitAssignmentId can be set later or have a default
    }
}

