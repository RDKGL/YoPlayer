package com.yoopen.player.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yoopen.player.data.soulseek.SoulseekConnectionState
import com.yoopen.player.data.soulseek.SoulseekDownloadItem
import com.yoopen.player.data.soulseek.SoulseekFolderGroup
import com.yoopen.player.data.soulseek.SoulseekFormatFilter
import com.yoopen.player.data.soulseek.SoulseekManager
import com.yoopen.player.data.soulseek.SoulseekSearchResult
import com.yoopen.player.data.soulseek.SoulseekSortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SoulseekViewModel @Inject constructor(
    private val soulseekManager: SoulseekManager
) : ViewModel() {

    val connectionState: StateFlow<SoulseekConnectionState> = soulseekManager.connectionState
    val isSearching: StateFlow<Boolean> = soulseekManager.isSearching
    val downloads: StateFlow<List<SoulseekDownloadItem>> = soulseekManager.downloads

    private val _formatFilter = MutableStateFlow(SoulseekFormatFilter.ALL)
    val formatFilter: StateFlow<SoulseekFormatFilter> = _formatFilter.asStateFlow()

    private val _freeSlotsOnly = MutableStateFlow(false)
    val freeSlotsOnly: StateFlow<Boolean> = _freeSlotsOnly.asStateFlow()

    private val _sortOrder = MutableStateFlow(SoulseekSortOrder.AVAILABILITY)
    val sortOrder: StateFlow<SoulseekSortOrder> = _sortOrder.asStateFlow()

    /**
     * Seeker-style album/folder grouping: groups matching files by (username, fullFolderPath),
     * sorted according to the user's chosen [sortOrder].
     */
    val folderGroups: StateFlow<List<SoulseekFolderGroup>> = combine(
        soulseekManager.searchResults,
        _formatFilter,
        _freeSlotsOnly,
        _sortOrder
    ) { results, filter, freeOnly, sort ->
        val filtered = results.filter { item ->
            val matchesFormat = when (filter) {
                SoulseekFormatFilter.ALL -> true
                SoulseekFormatFilter.LOSSLESS -> item.isLossless
                SoulseekFormatFilter.MP3 -> item.extension == "mp3"
            }
            val matchesSlot = !freeOnly || item.hasFreeSlot
            matchesFormat && matchesSlot
        }

        val groups = filtered
            .groupBy { "${it.username}|${it.fullFolderPath}" }
            .map { (groupKey, files) ->
                val sortedFiles = files.sortedBy { it.displayName.lowercase() }
                val sample = sortedFiles.first()
                val parentPath = sample.fullFolderPath
                    .substringBeforeLast('\\', "")
                    .substringAfterLast('\\', "")
                val totalSize = sortedFiles.sumOf { it.sizeBytes }
                val bestFile = sortedFiles.maxWithOrNull(
                    compareBy<SoulseekSearchResult> { it.isLossless }
                        .thenBy { it.bitDepth }
                        .thenBy { it.sampleRateHz }
                        .thenBy { it.bitrateKbps }
                ) ?: sample

                SoulseekFolderGroup(
                    id = groupKey,
                    username = sample.username,
                    folderName = sample.folderName,
                    parentFolderName = parentPath,
                    fullFolderPath = sample.fullFolderPath,
                    files = sortedFiles,
                    totalSizeBytes = totalSize,
                    hasFreeSlot = sample.hasFreeSlot,
                    uploadSpeedBytesPerSec = sample.uploadSpeedBytesPerSec,
                    queueLength = sample.queueLength,
                    isLossless = bestFile.isLossless,
                    primaryQualityBadge = bestFile.qualityBadge,
                    maxBitDepth = bestFile.bitDepth,
                    maxSampleRateHz = bestFile.sampleRateHz,
                    maxBitrateKbps = bestFile.bitrateKbps
                )
            }

        when (sort) {
            SoulseekSortOrder.AVAILABILITY -> groups.sortedWith(
                compareByDescending<SoulseekFolderGroup> { it.hasFreeSlot }
                    .thenBy { it.queueLength }
                    .thenByDescending { it.uploadSpeedBytesPerSec }
                    .thenByDescending { it.isLossless }
                    .thenByDescending { it.files.size }
            )
            SoulseekSortOrder.SPEED -> groups.sortedWith(
                compareByDescending<SoulseekFolderGroup> { it.uploadSpeedBytesPerSec }
                    .thenByDescending { it.hasFreeSlot }
                    .thenByDescending { it.files.size }
            )
            SoulseekSortOrder.QUALITY -> groups.sortedWith(
                compareByDescending<SoulseekFolderGroup> { it.isLossless }
                    .thenByDescending { it.maxBitDepth }
                    .thenByDescending { it.maxSampleRateHz }
                    .thenByDescending { it.maxBitrateKbps }
                    .thenByDescending { it.hasFreeSlot }
            )
            SoulseekSortOrder.TRACK_COUNT -> groups.sortedWith(
                compareByDescending<SoulseekFolderGroup> { it.files.size }
                    .thenByDescending { it.hasFreeSlot }
                    .thenByDescending { it.uploadSpeedBytesPerSec }
            )
            SoulseekSortOrder.SIZE -> groups.sortedWith(
                compareByDescending<SoulseekFolderGroup> { it.totalSizeBytes }
                    .thenByDescending { it.hasFreeSlot }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun getUsername(): String = soulseekManager.getUsername()

    fun ensureConnected() = soulseekManager.ensureConnected()

    fun reconnect() {
        soulseekManager.disconnect()
        soulseekManager.ensureConnected()
    }

    fun updateCredentials(username: String, password: String) {
        soulseekManager.updateCredentials(username, password)
    }

    fun setFormatFilter(filter: SoulseekFormatFilter) {
        _formatFilter.value = filter
    }

    fun toggleFreeSlotsOnly() {
        _freeSlotsOnly.value = !_freeSlotsOnly.value
    }

    fun setSortOrder(order: SoulseekSortOrder) {
        _sortOrder.value = order
    }

    fun search(query: String) {
        soulseekManager.search(query)
    }

    fun download(result: SoulseekSearchResult) {
        soulseekManager.download(result)
    }

    fun downloadFolder(group: SoulseekFolderGroup) {
        soulseekManager.downloadFolder(group.files)
    }

    fun retryDownload(downloadId: String) {
        soulseekManager.retryDownload(downloadId)
    }

    fun cancelDownload(downloadId: String) {
        soulseekManager.cancelDownload(downloadId)
    }

    fun cancelAllDownloads() {
        soulseekManager.cancelAllDownloads()
    }

    fun clearCompletedDownloads() {
        soulseekManager.clearCompletedDownloads()
    }
}
