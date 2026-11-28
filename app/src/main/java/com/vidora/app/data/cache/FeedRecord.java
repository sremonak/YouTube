package com.vidora.app.data.cache;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "feed_snapshots")
public class FeedRecord {
    @PrimaryKey @NonNull public String cacheKey = "";
    @NonNull public String payload = "";
    public long savedAt;
}
