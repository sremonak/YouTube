package com.vidora.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vidora.app.Graph
import com.vidora.app.MainActivity
import com.vidora.app.R
import com.vidora.app.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import java.util.concurrent.TimeUnit

/** Periodically checks subscribed channels' RSS feeds and notifies about new uploads. */
class NewVideosWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Local subscriptions plus, when signed in, the YouTube account's subscriptions (bell parity).
        val accountSubs = if (Graph.account.isSignedIn) {
            runCatching { Graph.accountApi.subscriptions().map { it.url } }.getOrDefault(emptyList())
        } else emptyList()
        val subs = (Graph.library.data.value.subscriptions.map { it.url } + accountSubs).distinct().take(200)
        if (subs.isEmpty()) return@withContext Result.success()
        val prefs = applicationContext.getSharedPreferences("new_videos", Context.MODE_PRIVATE)
        val gate = Semaphore(6)
        val fresh = subs.map { ch ->
            async {
                gate.withPermit {
                    runCatching {
                        val id = resolveChannelId(ch, prefs) ?: return@runCatching emptyList<VideoItem>()
                        val videos = RssFeed.fetch(id)
                        val newest = videos.maxOfOrNull { it.uploadedEpoch } ?: return@runCatching emptyList<VideoItem>()
                        val lastSeen = prefs.getLong("seen_$id", 0L)
                        prefs.edit().putLong("seen_$id", maxOf(newest, lastSeen)).apply()
                        if (lastSeen == 0L) emptyList() // first run: just remember, don't spam
                        else videos.filter { it.uploadedEpoch > lastSeen }
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten().sortedByDescending { it.uploadedEpoch }

        if (fresh.isNotEmpty()) notify(fresh)
        Result.success()
    }

    private fun resolveChannelId(url: String, prefs: android.content.SharedPreferences): String? {
        RssFeed.channelIdFromUrl(url)?.let { return it }
        prefs.getString("id_$url", null)?.let { return it }
        val id = runCatching { ChannelInfo.getInfo(ServiceList.YouTube, url).id }.getOrNull()
            ?.takeIf { it.startsWith("UC") } ?: return null
        prefs.edit().putString("id_$url", id).apply()
        return id
    }

    private fun notify(videos: List<VideoItem>) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        videos.take(6).forEach { v ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(v.url), ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(ctx, v.url.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val thumb = v.thumbnail?.let { url ->
                runCatching {
                    Graph.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        r.body?.byteStream()?.let(BitmapFactory::decodeStream)
                    }
                }.getOrNull()
            }
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(v.uploader ?: "New video")
                .setContentText(v.title)
                .setLargeIcon(thumb)
                .setStyle(if (thumb != null) NotificationCompat.BigPictureStyle().bigPicture(thumb).setSummaryText(v.title) else NotificationCompat.BigTextStyle().bigText(v.title))
                .setGroup(GROUP)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            runCatching { nm.notify(v.url.hashCode(), n) }
        }
        if (videos.size > 1) {
            val summary = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${videos.size} new videos")
                .setContentText(videos.mapNotNull { it.uploader }.distinct().take(4).joinToString())
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(SUMMARY_ID, summary) }
        }
    }

    companion object {
        const val CHANNEL = "new_videos"
        private const val GROUP = "vidora_new_videos"
        private const val SUMMARY_ID = 4242
        private const val WORK = "new_videos_check"

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "New videos from subscriptions", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        /** Keeps the periodic job in sync with the "notify every N hours" setting. */
        fun schedule(context: Context) {
            val app = context.applicationContext
            Graph.appScope.launch {
                Graph.settings.flow.map { it.notifyHours }.distinctUntilChanged().collect { hours ->
                    val wm = WorkManager.getInstance(app)
                    if (hours <= 0) {
                        wm.cancelUniqueWork(WORK)
                    } else {
                        ensureChannel(app)
                        val req = PeriodicWorkRequestBuilder<NewVideosWorker>(hours.toLong(), TimeUnit.HOURS)
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .build()
                        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
                    }
                }
            }
        }
    }
}
