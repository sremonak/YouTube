package com.vidora.app.download

import java.util.Locale

enum class DownloadView(val label: String) { ALL("All statuses"), ACTIVE("Active"), SAVED("Completed"), FAILED("Failed"), CANCELLED("Cancelled") }
enum class DownloadOrder(val label: String) { NEWEST("Newest first"), OLDEST("Oldest first"), TITLE("Title A–Z"), LARGEST("Largest first") }
object DownloadDashboard {
    fun active(status: DownloadStatus) = status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.MUXING)
    fun retryable(status: DownloadStatus) = status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED
    fun filter(records: List<DownloadRecord>, query: String = "", kind: DownloadKind? = null,
               view: DownloadView = DownloadView.ALL, order: DownloadOrder = DownloadOrder.NEWEST): List<DownloadRecord> {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        val matching = records.filter { r ->
            (kind == null || r.kind == kind) && words.all { it in "${r.video.title} ${r.video.uploader.orEmpty()} ${r.fileName}".lowercase(Locale.ROOT) } && when (view) {
                DownloadView.ALL -> true
                DownloadView.ACTIVE -> active(r.status)
                DownloadView.SAVED -> r.status == DownloadStatus.COMPLETED
                DownloadView.FAILED -> r.status == DownloadStatus.FAILED
                DownloadView.CANCELLED -> r.status == DownloadStatus.CANCELLED
            }
        }
        return when (order) {
            DownloadOrder.NEWEST -> matching.sortedByDescending { it.createdAt }
            DownloadOrder.OLDEST -> matching.sortedBy { it.createdAt }
            DownloadOrder.TITLE -> matching.sortedBy { it.video.title.lowercase(Locale.ROOT) }
            DownloadOrder.LARGEST -> matching.sortedByDescending { it.totalBytes }
        }
    }
    fun completedBytes(records: List<DownloadRecord>) = records.filter { it.status == DownloadStatus.COMPLETED }.sumOf { it.totalBytes.coerceAtLeast(0) }
}
