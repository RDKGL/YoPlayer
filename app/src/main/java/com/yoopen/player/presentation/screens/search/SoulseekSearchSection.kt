package com.yoopen.player.presentation.screens.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yoopen.player.data.soulseek.SoulseekConnectionState
import com.yoopen.player.data.soulseek.SoulseekDownloadItem
import com.yoopen.player.data.soulseek.SoulseekDownloadStatus
import com.yoopen.player.data.soulseek.SoulseekFolderGroup
import com.yoopen.player.data.soulseek.SoulseekFormatFilter
import com.yoopen.player.data.soulseek.SoulseekSearchResult
import com.yoopen.player.data.soulseek.SoulseekSortOrder
import com.yoopen.player.presentation.viewmodel.SoulseekViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoulseekSearchSection(
    searchQuery: String,
    soulseekViewModel: SoulseekViewModel,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    val connectionState by soulseekViewModel.connectionState.collectAsStateWithLifecycle()
    val isSearching by soulseekViewModel.isSearching.collectAsStateWithLifecycle()
    val folderGroups by soulseekViewModel.folderGroups.collectAsStateWithLifecycle()
    val downloads by soulseekViewModel.downloads.collectAsStateWithLifecycle()
    val formatFilter by soulseekViewModel.formatFilter.collectAsStateWithLifecycle()
    val freeSlotsOnly by soulseekViewModel.freeSlotsOnly.collectAsStateWithLifecycle()
    val sortOrder by soulseekViewModel.sortOrder.collectAsStateWithLifecycle()

    var showAccountDialog by remember { mutableStateOf(false) }
    var showDownloadsSheet by remember { mutableStateOf(false) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        soulseekViewModel.ensureConnected()
    }

    val downloadsByKey = remember(downloads) {
        downloads.associateBy { "${it.username}|${it.filename}" }
    }
    val activeDownloadsCount = remember(downloads) {
        downloads.count {
            it.status == SoulseekDownloadStatus.DOWNLOADING ||
                it.status == SoulseekDownloadStatus.CONNECTING ||
                it.status == SoulseekDownloadStatus.QUEUED
        }
    }
    val selectedFolder = remember(selectedFolderId, folderGroups) {
        selectedFolderId?.let { id -> folderGroups.firstOrNull { it.id == id } }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Connection status + Downloads manager button + Account button
        SoulseekStatusBar(
            connectionState = connectionState,
            totalDownloadsCount = downloads.size,
            activeDownloadsCount = activeDownloadsCount,
            onReconnect = { soulseekViewModel.reconnect() },
            onDownloadsClick = { showDownloadsSheet = true },
            onAccountClick = { showAccountDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Filter & Sort chips bar (like Seeker)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Sort chip with dropdown
            item {
                Box {
                    FilterChip(
                        selected = true,
                        onClick = { showSortMenu = true },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Sort,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text(sortOrder.label) },
                        shape = RoundedCornerShape(14.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    )
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        SoulseekSortOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = order.label,
                                        fontWeight = if (order == sortOrder) FontWeight.Bold else FontWeight.Normal,
                                        color = if (order == sortOrder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    soulseekViewModel.setSortOrder(order)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }

            items(SoulseekFormatFilter.entries) { filter ->
                FilterChip(
                    selected = formatFilter == filter,
                    onClick = { soulseekViewModel.setFormatFilter(filter) },
                    label = { Text(filter.label) },
                    shape = RoundedCornerShape(14.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }

            item {
                FilterChip(
                    selected = freeSlotsOnly,
                    onClick = { soulseekViewModel.toggleFreeSlotsOnly() },
                    label = { Text("Свободные слоты") },
                    shape = RoundedCornerShape(14.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                )
            }
        }

        // Compact clickable active download banner -> opens full Downloads sheet
        AnimatedVisibility(visible = activeDownloadsCount > 0) {
            val activeItem = downloads.firstOrNull { it.status == SoulseekDownloadStatus.DOWNLOADING }
                ?: downloads.firstOrNull {
                    it.status == SoulseekDownloadStatus.CONNECTING || it.status == SoulseekDownloadStatus.QUEUED
                }
            if (activeItem != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { showDownloadsSheet = true }
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Downloading,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = activeItem.displayName,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Text(
                                text = if (activeDownloadsCount > 1) {
                                    "Ещё ${activeDownloadsCount - 1} • Открыть"
                                } else {
                                    "Загрузки"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(5.dp))
                        if (activeItem.status == SoulseekDownloadStatus.DOWNLOADING) {
                            LinearProgressIndicator(
                                progress = { activeItem.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                }
            }
        }

        // Searching progress bar
        if (isSearching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .height(3.dp)
                    .clip(CircleShape)
            )
        } else {
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Album / Folder grouped results list (Seeker style!)
        if (folderGroups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = when {
                            isSearching -> "Ищем альбомы и треки в Soulseek..."
                            searchQuery.isBlank() -> "Поиск музыки в Soulseek"
                            else -> "Ничего не найдено"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            isSearching -> "Результаты группируются по альбомам и папкам"
                            searchQuery.isBlank() -> "Введите исполнителя, альбом или трек и нажмите «Поиск». Нажмите на альбом, чтобы выбрать отдельные треки, или скачайте всю папку целиком."
                            else -> "Попробуйте упростить запрос или изменить фильтр"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = folderGroups,
                    key = { it.id }
                ) { group ->
                    SoulseekAlbumFolderCard(
                        group = group,
                        downloadsByKey = downloadsByKey,
                        onOpenFolder = { selectedFolderId = group.id },
                        onDownloadAll = { soulseekViewModel.downloadFolder(group) }
                    )
                }
            }
        }
    }

    // Album / Folder Track Picker BottomSheet (tap any folder -> download whole album OR pick individual tracks)
    if (selectedFolder != null) {
        SoulseekFolderDetailSheet(
            group = selectedFolder,
            downloadsByKey = downloadsByKey,
            onDismiss = { selectedFolderId = null },
            onDownloadAll = { soulseekViewModel.downloadFolder(selectedFolder) },
            onDownloadTrack = { track -> soulseekViewModel.download(track) },
            onCancelDownload = { id -> soulseekViewModel.cancelDownload(id) }
        )
    }

    // Full Downloads Manager BottomSheet
    if (showDownloadsSheet) {
        SoulseekDownloadsSheet(
            downloads = downloads,
            onDismiss = { showDownloadsSheet = false },
            onRetry = { soulseekViewModel.retryDownload(it) },
            onCancel = { soulseekViewModel.cancelDownload(it) },
            onClearFinished = { soulseekViewModel.clearCompletedDownloads() },
            onCancelAll = { soulseekViewModel.cancelAllDownloads() }
        )
    }

    if (showAccountDialog) {
        SoulseekAccountDialog(
            currentUsername = soulseekViewModel.getUsername(),
            onDismiss = { showAccountDialog = false },
            onSave = { user, pass ->
                soulseekViewModel.updateCredentials(user, pass)
                showAccountDialog = false
            }
        )
    }
}

@Composable
private fun SoulseekStatusBar(
    connectionState: SoulseekConnectionState,
    totalDownloadsCount: Int,
    activeDownloadsCount: Int,
    onReconnect: () -> Unit,
    onDownloadsClick: () -> Unit,
    onAccountClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                when (connectionState) {
                    is SoulseekConnectionState.Connected -> {
                        Icon(
                            imageVector = Icons.Rounded.CloudDone,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "Soulseek подключён",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = connectionState.username,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    is SoulseekConnectionState.Connecting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Подключение к Soulseek...",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is SoulseekConnectionState.Error -> {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = connectionState.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    SoulseekConnectionState.Disconnected -> {
                        Icon(
                            imageVector = Icons.Rounded.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Отключено от Soulseek",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (connectionState !is SoulseekConnectionState.Connected) {
                    IconButton(onClick = onReconnect, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Переподключить",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Dedicated Downloads Manager button with badge
                IconButton(onClick = onDownloadsClick, modifier = Modifier.size(40.dp)) {
                    BadgedBox(
                        badge = {
                            if (totalDownloadsCount > 0) {
                                Badge(
                                    containerColor = if (activeDownloadsCount > 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.secondary
                                    }
                                ) {
                                    Text(
                                        text = (if (activeDownloadsCount > 0) activeDownloadsCount else totalDownloadsCount).toString(),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (activeDownloadsCount > 0) Icons.Rounded.Downloading else Icons.Rounded.Download,
                            contentDescription = "Меню загрузок",
                            tint = if (activeDownloadsCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                IconButton(onClick = onAccountClick, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = "Аккаунт Soulseek",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SoulseekAlbumFolderCard(
    group: SoulseekFolderGroup,
    downloadsByKey: Map<String, SoulseekDownloadItem>,
    onOpenFolder: () -> Unit,
    onDownloadAll: () -> Unit
) {
    val completedCount = group.files.count { downloadsByKey[it.id]?.status == SoulseekDownloadStatus.COMPLETED }
    val activeCount = group.files.count {
        val st = downloadsByKey[it.id]?.status
        st == SoulseekDownloadStatus.DOWNLOADING || st == SoulseekDownloadStatus.CONNECTING || st == SoulseekDownloadStatus.QUEUED
    }
    val allCompleted = completedCount == group.files.size && group.files.isNotEmpty()

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpenFolder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Album / Folder icon with slot availability dot
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (group.isLossless) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (group.files.size > 1) Icons.Rounded.Album else Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = if (group.isLossless) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (group.hasFreeSlot) Color(0xFF4CAF50) else Color(0xFFFF9800))
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (group.files.size == 1) group.files.first().displayName else group.folderName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val subtitle = buildString {
                    if (group.files.size == 1) {
                        append(group.folderName)
                    } else if (group.parentFolderName.isNotBlank()) {
                        append(group.parentFolderName)
                    } else {
                        append(group.username)
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(5.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (group.isLossless) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                    ) {
                        Text(
                            text = group.primaryQualityBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (group.isLossless) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = "${group.files.size} тр. • ${group.formattedTotalSize}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (group.formattedSpeed.isNotBlank()) {
                        Text(
                            text = "• ${group.formattedSpeed}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = group.username,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = if (group.hasFreeSlot) "• Свободен" else "• Очередь: ${group.queueLength}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (group.hasFreeSlot) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    )
                }
            }

            // Quick download whole folder/album button
            when {
                allCompleted -> {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Скачано",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(28.dp)
                    )
                }
                activeCount > 0 -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(40.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp
                        )
                        Text(
                            text = activeCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                else -> {
                    IconButton(
                        onClick = onDownloadAll,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Скачать альбом",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoulseekFolderDetailSheet(
    group: SoulseekFolderGroup,
    downloadsByKey: Map<String, SoulseekDownloadItem>,
    onDismiss: () -> Unit,
    onDownloadAll: () -> Unit,
    onDownloadTrack: (SoulseekSearchResult) -> Unit,
    onCancelDownload: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.folderName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${group.username} • ${group.files.size} треков • ${group.formattedTotalSize}" +
                            if (group.formattedSpeed.isNotBlank()) " • ${group.formattedSpeed}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onDownloadAll,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Скачать всю папку (${group.files.size} треков • ${group.formattedTotalSize})")
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = group.files,
                    key = { it.id }
                ) { track ->
                    val dl = downloadsByKey[track.id]
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = dl == null || dl.status == SoulseekDownloadStatus.FAILED) {
                                onDownloadTrack(track)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "${track.qualityBadge} • ${track.formattedSize}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            when (dl?.status) {
                                null, SoulseekDownloadStatus.FAILED -> {
                                    IconButton(
                                        onClick = { onDownloadTrack(track) },
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = "Скачать трек",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                SoulseekDownloadStatus.COMPLETED -> {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = "Скачано",
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                SoulseekDownloadStatus.DOWNLOADING -> {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .clickable { onCancelDownload(dl.id) }
                                    ) {
                                        CircularProgressIndicator(
                                            progress = { dl.progress },
                                            modifier = Modifier.size(32.dp),
                                            strokeWidth = 3.dp
                                        )
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Отменить",
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                SoulseekDownloadStatus.QUEUED, SoulseekDownloadStatus.CONNECTING -> {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .clickable { onCancelDownload(dl.id) }
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            strokeWidth = 2.5.dp
                                        )
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Отменить",
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoulseekDownloadsSheet(
    downloads: List<SoulseekDownloadItem>,
    onDismiss: () -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onClearFinished: () -> Unit,
    onCancelAll: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeCount = downloads.count {
        it.status == SoulseekDownloadStatus.DOWNLOADING ||
            it.status == SoulseekDownloadStatus.CONNECTING ||
            it.status == SoulseekDownloadStatus.QUEUED
    }
    val completedCount = downloads.count { it.status == SoulseekDownloadStatus.COMPLETED }
    val failedCount = downloads.count { it.status == SoulseekDownloadStatus.FAILED }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Загрузки Soulseek",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Активных: $activeCount • Готово: $completedCount" +
                            if (failedCount > 0) " • Ошибок: $failedCount" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (completedCount > 0 || failedCount > 0) {
                        IconButton(onClick = onClearFinished) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = "Очистить завершённые"
                            )
                        }
                    }
                    if (downloads.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onCancelAll,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Очистить всё", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            if (downloads.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DownloadDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "Список загрузок пуст",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Скачанные треки сохраняются в Music/YoPlayer и сразу появляются в медиатеке.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = downloads,
                        key = { it.id }
                    ) { dl ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = dl.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${dl.folderName} • ${dl.username}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    if (dl.status == SoulseekDownloadStatus.FAILED) {
                                        IconButton(
                                            onClick = { onRetry(dl.id) },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Refresh,
                                                contentDescription = "Повторить",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { onCancel(dl.id) },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Удалить",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                val statusText = when (dl.status) {
                                    SoulseekDownloadStatus.QUEUED ->
                                        if (dl.queuePlace > 0) "В очереди у пира (#${dl.queuePlace})" else "В очереди у пира..."
                                    SoulseekDownloadStatus.CONNECTING -> "Подключение к пиру..."
                                    SoulseekDownloadStatus.DOWNLOADING -> buildString {
                                        append("${(dl.progress * 100).toInt()}% • ${dl.formattedProgressBytes}")
                                        if (dl.formattedSpeed.isNotBlank()) append(" • ${dl.formattedSpeed}")
                                    }
                                    SoulseekDownloadStatus.COMPLETED -> "Готово • Music/YoPlayer/${dl.folderName}"
                                    SoulseekDownloadStatus.FAILED -> dl.errorMessage ?: "Ошибка загрузки"
                                }

                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (dl.status) {
                                        SoulseekDownloadStatus.COMPLETED -> Color(0xFF4CAF50)
                                        SoulseekDownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                                        SoulseekDownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )

                                if (dl.status == SoulseekDownloadStatus.DOWNLOADING) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { dl.progress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(CircleShape)
                                    )
                                } else if (dl.status == SoulseekDownloadStatus.CONNECTING) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SoulseekAccountDialog(
    currentUsername: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var username by remember { mutableStateOf(currentUsername) }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Аккаунт Soulseek") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "По умолчанию YoPlayer создаёт гостевой аккаунт автоматически. Вы можете указать свой логин и пароль Soulseek.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Логин") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && password.isNotBlank(),
                onClick = { onSave(username, password) }
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
