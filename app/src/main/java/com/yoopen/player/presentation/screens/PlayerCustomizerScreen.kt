package com.yoopen.player.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import com.yoopen.player.R
import com.yoopen.player.data.preferences.PlayerTitleAlignment
import com.yoopen.player.data.preferences.PlayerTitlePosition
import com.yoopen.player.data.preferences.PlayerTextSize
import com.yoopen.player.data.preferences.PlayerCoverStyle
import com.yoopen.player.data.preferences.UserPreferencesRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerCustomizerScreen(
    navController: NavController,
    userPreferencesRepository: UserPreferencesRepository
) {
    val scope = rememberCoroutineScope()

    val titleAlignment by userPreferencesRepository.playerTitleAlignmentFlow.collectAsStateWithLifecycle(initialValue = PlayerTitleAlignment.LEFT)
    val titlePosition by userPreferencesRepository.playerTitlePositionFlow.collectAsStateWithLifecycle(initialValue = PlayerTitlePosition.BELOW_ARTWORK)
    val textSize by userPreferencesRepository.playerTextSizeFlow.collectAsStateWithLifecycle(initialValue = PlayerTextSize.LARGE)
    val coverStyle by userPreferencesRepository.playerCoverStyleFlow.collectAsStateWithLifecycle(initialValue = PlayerCoverStyle.STANDARD)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.player_view_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                userPreferencesRepository.setPlayerTitleAlignment(PlayerTitleAlignment.LEFT)
                                userPreferencesRepository.setPlayerTitlePosition(PlayerTitlePosition.BELOW_ARTWORK)
                                userPreferencesRepository.setPlayerTextSize(PlayerTextSize.LARGE)
                                userPreferencesRepository.setPlayerCoverStyle(PlayerCoverStyle.STANDARD)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.action_reset), color = MaterialTheme.colorScheme.primary)
                    }
                    Button(
                        onClick = { navController.navigateUp() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(stringResource(R.string.action_done), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 140.dp + navBarBottom),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item(key = "live_preview") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    PlayerTitleLivePreview(
                        titleAlignment = titleAlignment,
                        titlePosition = titlePosition,
                        textSize = textSize,
                        coverStyle = coverStyle
                    )
                }
            }

            item(key = "alignment_section") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.player_title_alignment_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SelectablePill(
                                text = androidx.compose.ui.res.stringResource(R.string.player_title_align_left),
                                selected = titleAlignment == PlayerTitleAlignment.LEFT,
                                onClick = { scope.launch { userPreferencesRepository.setPlayerTitleAlignment(PlayerTitleAlignment.LEFT) } },
                                modifier = Modifier.weight(1f)
                            )
                            SelectablePill(
                                text = androidx.compose.ui.res.stringResource(R.string.player_title_align_center),
                                selected = titleAlignment == PlayerTitleAlignment.CENTER,
                                onClick = { scope.launch { userPreferencesRepository.setPlayerTitleAlignment(PlayerTitleAlignment.CENTER) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            item(key = "position_section") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.player_title_position_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SelectablePill(
                                text = androidx.compose.ui.res.stringResource(R.string.player_title_pos_below),
                                selected = titlePosition == PlayerTitlePosition.BELOW_ARTWORK,
                                onClick = { scope.launch { userPreferencesRepository.setPlayerTitlePosition(PlayerTitlePosition.BELOW_ARTWORK) } },
                                modifier = Modifier.weight(1f)
                            )
                            SelectablePill(
                                text = androidx.compose.ui.res.stringResource(R.string.player_title_pos_above),
                                selected = titlePosition == PlayerTitlePosition.ABOVE_ARTWORK,
                                onClick = { scope.launch { userPreferencesRepository.setPlayerTitlePosition(PlayerTitlePosition.ABOVE_ARTWORK) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            item(key = "size_section") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.player_text_size_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SelectablePill(
                                text = androidx.compose.ui.res.stringResource(R.string.player_text_size_mini),
                                selected = textSize == PlayerTextSize.MINI,
                                onClick = { scope.launch { userPreferencesRepository.setPlayerTextSize(PlayerTextSize.MINI) } },
                                modifier = Modifier.weight(1f)
                            )
                            SelectablePill(
                                text = androidx.compose.ui.res.stringResource(R.string.player_text_size_medium),
                                selected = textSize == PlayerTextSize.MEDIUM,
                                onClick = { scope.launch { userPreferencesRepository.setPlayerTextSize(PlayerTextSize.MEDIUM) } },
                                modifier = Modifier.weight(1f)
                            )
                            SelectablePill(
                                text = androidx.compose.ui.res.stringResource(R.string.player_text_size_large),
                                selected = textSize == PlayerTextSize.LARGE,
                                onClick = { scope.launch { userPreferencesRepository.setPlayerTextSize(PlayerTextSize.LARGE) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            item(key = "cover_style_section") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.player_cover_style_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SelectablePill(
                                text = androidx.compose.ui.res.stringResource(R.string.player_cover_style_standard),
                                selected = coverStyle == PlayerCoverStyle.STANDARD,
                                onClick = { scope.launch { userPreferencesRepository.setPlayerCoverStyle(PlayerCoverStyle.STANDARD) } },
                                modifier = Modifier.weight(1f)
                            )
                            SelectablePill(
                                text = androidx.compose.ui.res.stringResource(R.string.player_cover_style_vinyl),
                                selected = coverStyle == PlayerCoverStyle.VINYL,
                                onClick = { scope.launch { userPreferencesRepository.setPlayerCoverStyle(PlayerCoverStyle.VINYL) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SelectablePill(
                                text = androidx.compose.ui.res.stringResource(R.string.player_cover_style_glow),
                                selected = coverStyle == PlayerCoverStyle.GLOW,
                                onClick = { scope.launch { userPreferencesRepository.setPlayerCoverStyle(PlayerCoverStyle.GLOW) } },
                                modifier = Modifier.weight(1f)
                            )
                            SelectablePill(
                                text = androidx.compose.ui.res.stringResource(R.string.player_cover_style_circle),
                                selected = coverStyle == PlayerCoverStyle.CIRCLE,
                                onClick = { scope.launch { userPreferencesRepository.setPlayerCoverStyle(PlayerCoverStyle.CIRCLE) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectablePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 14.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PlayerTitleLivePreview(
    titleAlignment: PlayerTitleAlignment,
    titlePosition: PlayerTitlePosition,
    textSize: PlayerTextSize,
    coverStyle: PlayerCoverStyle
) {
    val align = if (titleAlignment == PlayerTitleAlignment.CENTER) Alignment.CenterHorizontally else Alignment.Start
    val txtAlign = if (titleAlignment == PlayerTitleAlignment.CENTER) TextAlign.Center else TextAlign.Start

    val (titleStyle, artistStyle) = when (textSize) {
        PlayerTextSize.MINI -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold) to MaterialTheme.typography.labelSmall
        PlayerTextSize.MEDIUM -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold) to MaterialTheme.typography.bodySmall
        PlayerTextSize.LARGE -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold) to MaterialTheme.typography.bodyMedium
    }

    Surface(
        modifier = Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(28.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val titleBlock = @Composable {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = align
                ) {
                    Text(
                        text = "The Good Life",
                        style = titleStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = txtAlign
                    )
                    Text(
                        text = "Weezer",
                        style = artistStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = txtAlign
                    )
                }
            }

            if (titlePosition == PlayerTitlePosition.ABOVE_ARTWORK) {
                titleBlock()
                Spacer(modifier = Modifier.height(12.dp))
            }

            val coverShape = when (coverStyle) {
                PlayerCoverStyle.CIRCLE, PlayerCoverStyle.VINYL -> CircleShape
                else -> RoundedCornerShape(18.dp)
            }
            val coverModifier = when (coverStyle) {
                PlayerCoverStyle.CIRCLE, PlayerCoverStyle.VINYL -> Modifier.size(130.dp)
                else -> Modifier.fillMaxWidth().height(130.dp)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                contentAlignment = Alignment.Center
            ) {
                if (coverStyle == PlayerCoverStyle.GLOW) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .height(118.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(22.dp)
                            )
                    )
                }

                Box(
                    modifier = coverModifier
                        .clip(coverShape)
                        .background(
                            if (coverStyle == PlayerCoverStyle.VINYL) Color(0xFF161616)
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverStyle == PlayerCoverStyle.VINYL) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            )
                        }
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.rounded_music_note_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }

            if (titlePosition == PlayerTitlePosition.BELOW_ARTWORK) {
                Spacer(modifier = Modifier.height(12.dp))
                titleBlock()
            }
        }
    }
}
