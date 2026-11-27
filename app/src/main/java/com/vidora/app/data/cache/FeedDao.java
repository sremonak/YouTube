package com.vidora.app.data.cache;

import androidx.room.*;

@Dao
public interface FeedDao {
    @Query("SELECT * FROM feed_snapshots WHERE cacheKey = :key LIMIT 1") FeedRecord find(String key);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void put(FeedRecord record);
    @Query("DELETE FROM feed_snapshots WHERE savedAt < :cutoff OR cacheKey NOT IN (SELECT cacheKey FROM feed_snapshots ORDER BY savedAt DESC LIMIT 32)") void prune(long cutoff);
    @Query("DELETE FROM feed_snapshots") void clear();
}
