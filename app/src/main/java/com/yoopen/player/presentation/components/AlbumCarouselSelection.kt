package com.yoopen.player.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import coil.size.Size
import com.yoopen.player.data.model.Song
import com.yoopen.player.data.preferences.CarouselStyle
import com.yoopen.player.presentation.components.scoped.PrefetchAlbumNeighbors
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.first

import com.yoopen.player.data.preferences.AlbumArtQuality
import com.yoopen.player.data.preferences.PlayerCoverStyle
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs

private const val SETTLED_PAGE_OFFSET_EPSILON = 0.001f

@Composable
fun AlbumCarouselSection(
    currentSong: Song?,
    queue: ImmutableList<Song>,
    expansionFraction: Float,
    currentMediaItemIndex: Int = -1,
    requestedScrollIndex: Int? = null,
    onSongSelected: (Song, Int) -> Unit,
    onAlbumClick: (Song) -> Unit = {},
    modifier: Modifier = Modifier,
    carouselStyle: String = CarouselStyle.NO_PEEK,
    itemSpacing: Dp = 8.dp,
    albumArtQuality: AlbumArtQuality = AlbumArtQuality.MEDIUM,
    coverStyle: PlayerCoverStyle = PlayerCoverStyle.STANDARD,
    isPlaying: Boolean = false
) {
    if (queue.isEmpty()) return

    val initialIndex = remember(currentSong?.id, currentMediaItemIndex, queue) {
        resolveCurrentQueueIndex(
            currentSong = currentSong,
            currentMediaItemIndex = currentMediaItemIndex,
            queue = queue
        )
    }

    val pagerState = rememberPagerState(initialPage = initialIndex) { queue.size }

    val targetSize = remember(albumArtQuality) {
        if (albumArtQuality.maxSize == 0) SafeOriginalAlbumArtSize
        else Size(albumArtQuality.maxSize, albumArtQuality.maxSize)
    }

    val currentSongIndex = remember(currentSong?.id, currentMediaItemIndex, queue) {
        resolveCurrentQueueIndex(
            currentSong = currentSong,
            currentMediaItemIndex = currentMediaItemIndex,
            queue = queue
        )
    }
    val requestedTargetIndex = remember(requestedScrollIndex, queue) {
        requestedScrollIndex?.takeIf { it in queue.indices }
    }
    val effectiveTargetIndex = requestedTargetIndex ?: currentSongIndex
    val carouselItemKeys = remember(queue) {
        buildQueueOccurrenceKeys(queue)
    }

    PrefetchAlbumNeighbors(
        isActive = expansionFraction > 0.08f,
        pagerState = pagerState,
        queue = queue,
        radius = 1,
        targetSize = targetSize,
        anchorIndex = effectiveTargetIndex
    )
    // Scrolls this section runs itself: skips, and the snap when the queue shifts around the
    // playing song. A counter rather than a flag, because a cancelled scroll's cleanup can run
    // after the scroll that replaced it has already started.
    var ownScrollsInFlight by remember { mutableIntStateOf(0) }
    var lastSettledSongId by remember { mutableStateOf(currentSong?.id) }

    // A settle only picks a song after a scroll this section didn't start. User drags come
    // through the pager's interaction source. Accessibility page actions, keyboard and mouse-wheel
    // scrolls emit no DragInteraction, so any scroll that runs while none of ours does counts too.
    var isUserDragging by remember { mutableStateOf(false) }
    var userScrollSettlePending by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    isUserDragging = true
                    userScrollSettlePending = true
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> isUserDragging = false
            }
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress && ownScrollsInFlight == 0 }
            .filter { it }
            .collect { userScrollSettlePending = true }
    }

    // Player -> Carousel. Each new target cancels the in-flight animation (the pager's
    // MutatorMutex) and animates on from the current position, so a burst of skips keeps moving.
    // Only an active user drag is waited for; waiting for our own just-cancelled scroll to go
    // idle stalled the carousel until the taps stopped.
    LaunchedEffect(effectiveTargetIndex, requestedTargetIndex, queue) {
        if (isUserDragging) {
            snapshotFlow { isUserDragging }.first { !it }
        }

        val currentPage = pagerState.currentPage
        // A scroll cancelled past the halfway point already reports the target as its current
        // page, so also finish it when it was left between pages.
        val isBetweenPages =
            abs(pagerState.currentPageOffsetFraction) > SETTLED_PAGE_OFFSET_EPSILON
        if (currentPage != effectiveTargetIndex || isBetweenPages) {
            val isShiftOnly = currentSong?.id != null &&
                              currentSong.id == lastSettledSongId &&
                              requestedTargetIndex == null

            ownScrollsInFlight++
            try {
                if (isShiftOnly) {
                    pagerState.scrollToPage(effectiveTargetIndex)
                } else {
                    pagerState.animateScrollToPage(effectiveTargetIndex, animationSpec = spring())
                }
            } finally {
                ownScrollsInFlight--
            }
        }
        lastSettledSongId = currentSong?.id
    }

    val hapticFeedback = LocalHapticFeedback.current
    LaunchedEffect(pagerState, currentSongIndex, queue) {
        snapshotFlow { pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .collect {
                // Only a settle that follows a user scroll picks a song. The player already drove
                // this section's own scrolls, and a skip burst cancels them on intermediate pages.
                if (!userScrollSettlePending) return@collect
                userScrollSettlePending = false
                val settled = pagerState.currentPage
                if (settled != currentSongIndex) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    queue.getOrNull(settled)?.let { onSongSelected(it, settled) }
                }
            }
    }

    val corner = when (coverStyle) {
        PlayerCoverStyle.CIRCLE, PlayerCoverStyle.VINYL -> 999.dp
        else -> 18.dp
    }

    val infiniteTransition = rememberInfiniteTransition(label = "AlbumCoverEffects")
    val vinylAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "VinylRotation"
    )
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowPulse"
    )

    BoxWithConstraints(modifier = modifier) {
        val availableWidth = this.maxWidth

        ParallaxAlbumPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            itemSpacing = itemSpacing,
            itemCornerRadius = corner,
            carouselStyle = if (pagerState.pageCount == 1) CarouselStyle.NO_PEEK else carouselStyle,
            carouselWidth = availableWidth,
            itemKey = { index -> carouselItemKeys.getOrNull(index) ?: "queue_item_$index" },
            content = { index ->
                val song = queue[index]
                val isFocusedItem = pagerState.currentPage == index
                val currentRotation = if (isFocusedItem && isPlaying && coverStyle == PlayerCoverStyle.VINYL) vinylAngle else 0f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverStyle == PlayerCoverStyle.GLOW && isFocusedItem) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize(0.90f)
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    scaleX = 1.08f
                                    scaleY = 1.08f
                                    alpha = if (isPlaying) glowPulse else 0.35f
                                }
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = RoundedCornerShape(32.dp)
                                )
                        )
                    }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .aspectRatio(1f)
                            .rotate(currentRotation)
                            .clip(if (coverStyle == PlayerCoverStyle.VINYL || coverStyle == PlayerCoverStyle.CIRCLE) CircleShape else RoundedCornerShape(corner))
                            .clickable(
                                enabled = isFocusedItem && song.albumId != -1L,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onAlbumClick(song) }
                            )
                    ) {
                        OptimizedAlbumArt(
                            uri = song.albumArtUriString,
                            title = song.title,
                            modifier = Modifier.fillMaxSize(),
                            targetSize = targetSize
                        )

                        if (coverStyle == PlayerCoverStyle.VINYL) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val strokeWidth = 1.5.dp.toPx()
                                val radius = size.minDimension / 2f
                                drawCircle(
                                    color = Color.Black.copy(alpha = 0.28f),
                                    radius = radius * 0.92f,
                                    style = Stroke(width = strokeWidth)
                                )
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.12f),
                                    radius = radius * 0.84f,
                                    style = Stroke(width = strokeWidth)
                                )
                                drawCircle(
                                    color = Color.Black.copy(alpha = 0.24f),
                                    radius = radius * 0.74f,
                                    style = Stroke(width = strokeWidth)
                                )
                                drawCircle(
                                    color = Color.Black.copy(alpha = 0.55f),
                                    radius = radius * 0.22f
                                )
                                drawCircle(
                                    color = Color(0xFF1E1E1E),
                                    radius = radius * 0.065f
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}

private fun resolveCurrentQueueIndex(
    currentSong: Song?,
    currentMediaItemIndex: Int,
    queue: ImmutableList<Song>
): Int {
    val songId = currentSong?.id ?: return 0
    if (currentMediaItemIndex in queue.indices && queue[currentMediaItemIndex].id == songId) {
        return currentMediaItemIndex
    }
    return queue.indexOfFirst { it.id == songId }
        .takeIf { it >= 0 }
        ?: queue.indexOf(currentSong)
            .takeIf { it >= 0 }
        ?: 0
}

private fun buildQueueOccurrenceKeys(queue: ImmutableList<Song>): List<String> {
    val occurrencesBySongId = HashMap<String, Int>()
    return queue.mapIndexed { index, song ->
        val occurrence = occurrencesBySongId.getOrDefault(song.id, 0)
        occurrencesBySongId[song.id] = occurrence + 1
        "queue_carousel_${song.id}_${occurrence}_${song.albumArtUriString.orEmpty().hashCode()}_$index"
    }
}
