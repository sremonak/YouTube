package com.vidora.app.data.cache;

import androidx.room.*;

@Database(entities = {FeedRecord.class}, version = 1, exportSchema = false)
public abstract class FeedDatabase extends RoomDatabase {
    public abstract FeedDao feeds();
}
