package com.vidora.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vidora.app.BuildConfig
import com.vidora.app.Graph
import com.vidora.app.MainActivity
import com.vidora.app.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

internal object UpdateNotify {
    const val CHANNEL = "updates"
    private const val ID = 7301

    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(context: Context, extra: Intent? = null): PendingIntent = PendingIntent.getActivity(
        context, 11,
        extra ?: Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_ABOUT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun show(context: Context, title: String, text: String, tap: Intent? = null) {
        channel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, tap))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, n) }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)
}

/** Result of a PackageInstaller session started by [Updater.install]. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (foreground) runCatching { context.startActivity(confirm) }.onFailure {
                    UpdateNotify.show(context, "Vidora update ready", "Tap to install the new version", confirm)
                } else UpdateNotify.show(context, "Vidora update ready", "Tap to install the new version", confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateNotify.cancel(context)
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed ($status)"
                Updater.state.value = UpdateState.Failed(msg)
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    // e.g. an OEM installer that rejects sessions → classic installer screen
                    val apk = intent.getStringExtra("apk")?.let(::File)
                    if (apk != null && apk.exists()) runCatching { Updater.installWithIntent(context, apk) }
                    else Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        const val ACTION = "com.vidora.app.UPDATE_INSTALL_STATUS"
    }
}

/** After an update was installed: confirm it (silent updates are otherwise invisible). */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        UpdateNotify.show(context, "Vidora updated", "You're now on version ${BuildConfig.VERSION_NAME}. Tap to see what's new.")
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }
}

/** Twice a day: look for a new GitHub release; download + install it when auto-install is on. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = Graph.settings.flow.first()
        if (!s.autoUpdateCheck) return Result.success()
        val info = runCatching { Updater.check() }.getOrNull() ?: return Result.success()
        val prefs = applicationContext.getSharedPreferences("updater", Context.MODE_PRIVATE)
        if (!UpdatePromptPolicy.shouldPrompt(info.version, prefs.getString("later_version", null),
                prefs.getLong("later_until", 0), System.currentTimeMillis())) return Result.success()
        // Foreground discovery is shown by StartupDialogs. Don't race the floating card
        // with a silent install, even on older installs where auto-install was enabled.
        val foreground = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        if (foreground) return Result.success()
        val metered = runCatching {
            applicationContext.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered
        }.getOrDefault(true)
        if (s.autoInstallUpdates && Updater.canInstall(applicationContext) && !(metered && s.dataSaver)) {
            Updater.downloadAndInstall(applicationContext, info)
        } else if (prefs.getString("notified", null) != info.version) {
            prefs.edit().putString("notified", info.version).apply()
            UpdateNotify.show(applicationContext, "Vidora ${info.version} is available", "Tap to update — ${info.notes.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(120)}")
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "vidora-update-check"

        fun schedule(context: Context) {
            val app = context.applicationContext
            Graph.appScope.launch {
                Graph.settings.flow.map { it.autoUpdateCheck }.distinctUntilChanged().collect { on ->
                    val wm = WorkManager.getInstance(app)
                    if (!on) wm.cancelUniqueWork(WORK) else {
                        val req = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                            .setInitialDelay(30, TimeUnit.MINUTES)
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .build()
                        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
                    }
                }
            }
        }
    }
}
