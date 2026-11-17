package com.yoopen.player.data.soulseek

import androidx.compose.runtime.Immutable

sealed interface SoulseekConnectionState {
    data object Disconnected : SoulseekConnectionState
    data object Connecting : SoulseekConnectionState
    data class Connected(val username: String) : SoulseekConnectionState
    data class Error(val message: String) : SoulseekConnectionState
}

enum class SoulseekFormatFilter(val label: String) {
    ALL("Все"),
    LOSSLESS("FLAC / Lossless"),
    MP3("MP3")
}

enum class SoulseekSortOrder(val label: String) {
    AVAILABILITY("Сначала доступные"),
    SPEED("По скорости"),
    QUALITY("По качеству"),
    TRACK_COUNT("По кол-ву треков"),
    SIZE("По размеру")
}

@Immutable
data class SoulseekSearchResult(
    val id: String,
    val username: String,
    val filename: String,
    val displayName: String,
    val folderName: String,
    val fullFolderPath: String,
    val extension: String,
    val sizeBytes: Long,
    val bitrateKbps: Int = 0,
    val durationSec: Int = 0,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val hasFreeSlot: Boolean = false,
    val uploadSpeedBytesPerSec: Long = 0L,
    val queueLength: Int = 0
) {
    val isLossless: Boolean
        get() = extension in LOSSLESS_EXTENSIONS

    val qualityBadge: String
        get() = buildString {
            append(extension.uppercase().ifBlank { "AUDIO" })
            if (bitDepth > 0 && sampleRateHz > 0) {
                val khz = if (sampleRateHz % 1000 == 0) "${sampleRateHz / 1000}" else String.format("%.1f", sampleRateHz / 1000.0)
                append(" • ${bitDepth}bit/${khz}kHz")
            } else if (bitrateKbps > 0) {
                append(" • ${bitrateKbps} kbps")
            }
        }

    val formattedSize: String
        get() = formatBytes(sizeBytes)

    companion object {
        val LOSSLESS_EXTENSIONS = setOf("flac", "wav", "alac", "aiff", "aif", "wv", "ape", "dsf", "dff")
        val AUDIO_EXTENSIONS = LOSSLESS_EXTENSIONS + setOf("mp3", "m4a", "aac", "ogg", "opus", "wma")

        fun formatBytes(bytes: Long): String {
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) String.format("%.1f MB", mb)
            else String.format("%.0f KB", (bytes / 1024.0).coerceAtLeast(1.0))
        }

        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0L) return ""
            val mbps = bytesPerSec / (1024.0 * 1024.0)
            return if (mbps >= 1.0) String.format("%.1f MB/s", mbps)
            else String.format("%.0f KB/s", (bytesPerSec / 1024.0).coerceAtLeast(1.0))
        }
    }
}

@Immutable
data class SoulseekFolderGroup(
    val id: String,
    val username: String,
    val folderName: String,
    val parentFolderName: String,
    val fullFolderPath: String,
    val files: List<SoulseekSearchResult>,
    val totalSizeBytes: Long,
    val hasFreeSlot: Boolean,
    val uploadSpeedBytesPerSec: Long,
    val queueLength: Int,
    val isLossless: Boolean,
    val primaryQualityBadge: String,
    val maxBitDepth: Int,
    val maxSampleRateHz: Int,
    val maxBitrateKbps: Int
) {
    val formattedTotalSize: String
        get() = SoulseekSearchResult.formatBytes(totalSizeBytes)

    val formattedSpeed: String
        get() = SoulseekSearchResult.formatSpeed(uploadSpeedBytesPerSec)
}

enum class SoulseekDownloadStatus {
    QUEUED,
    CONNECTING,
    DOWNLOADING,
    COMPLETED,
    FAILED
}

@Immutable
data class SoulseekDownloadItem(
    val id: String,
    val username: String,
    val filename: String,
    val displayName: String,
    val folderName: String,
    val sizeBytes: Long,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val status: SoulseekDownloadStatus = SoulseekDownloadStatus.QUEUED,
    val queuePlace: Int = 0,
    val errorMessage: String? = null
) {
    val progress: Float
        get() = if (sizeBytes > 0L) (downloadedBytes.toFloat() / sizeBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val formattedProgressBytes: String
        get() = "${SoulseekSearchResult.formatBytes(downloadedBytes)} / ${SoulseekSearchResult.formatBytes(sizeBytes)}"

    val formattedSpeed: String
        get() = SoulseekSearchResult.formatSpeed(speedBytesPerSec)
}
