package com.yoopen.player.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.yoopen.player.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.yoopen.player.data.model.Song
import com.yoopen.player.presentation.components.OptimizedAlbumArt
import com.yoopen.player.presentation.viewmodel.OrbitalSongItem
import com.yoopen.player.presentation.viewmodel.PlayerViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Stage 1-6 Orbital YoMix System Component:
 * - Clean "YoMix" header title at top
 * - Single 3D Orbital space with dashed ellipse paths
 * - Monochromatic Central Planet Button with Black Shuffle Icon
 * - Normal high-res square/rounded album covers (16.dp corner radius)
 * - 3-second hold gesture to trigger onRebuildMix()
 */
@Composable
fun OrbitalYoMixSection(
    songs: ImmutableList<Song>,
    playerViewModel: PlayerViewModel,
    onSongClick: (Song) -> Unit = {},
    onRebuildMix: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val displaySongs = remember(songs) { songs.take(7).toImmutableList() }
    val orbitalItems = remember(displaySongs) {
        val count = displaySongs.size.coerceAtLeast(1)
        val angleStep = (2 * Math.PI / count).toFloat()
        displaySongs.mapIndexed { index, song ->
            OrbitalSongItem(
                song = song,
                initialAngleRad = index * angleStep
            )
        }
    }

    val flashStates = remember(displaySongs) {
        mutableStateListOf<Boolean>().apply {
            repeat(displaySongs.size) { add(false) }
        }
    }

    fun triggerCascadeFlash() {
        scope.launch {
            for (i in flashStates.indices) {
                flashStates[i] = true
                delay(90)
                flashStates[i] = false
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "YoMixOrbitalTransition")
    val orbitAngleRad by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 28000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "OrbitAngle"
    )

    val planetPulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PlanetPulse"
    )

    val holdProgress = remember { Animatable(0f) }
    var isHolding by remember { mutableStateOf(false) }

    val primaryAccent = MaterialTheme.colorScheme.primary
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier.fillMaxWidth()) {
        // YoMix Header
        Text(
            text = "YoMix",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, top = 24.dp)
        )
        Text(
            text = stringResource(R.string.home_your_mix_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(310.dp),
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current
            val radiusX = with(density) { 150.dp.toPx() }
            val radiusY = with(density) { 75.dp.toPx() }

            // Stage 3: Dashed Ellipse Orbit Paths Canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)

                // Inner orbit guide
                drawOval(
                    color = primaryAccent.copy(alpha = 0.18f),
                    topLeft = Offset(center.x - radiusX * 0.75f, center.y - radiusY * 0.75f),
                    size = Size(radiusX * 1.5f, radiusY * 1.5f),
                    style = Stroke(width = 1.2.dp.toPx(), pathEffect = dashEffect)
                )

                // Main orbit guide
                drawOval(
                    color = primaryAccent.copy(alpha = 0.28f),
                    topLeft = Offset(center.x - radiusX, center.y - radiusY),
                    size = Size(radiusX * 2f, radiusY * 2f),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = dashEffect)
                )
            }

            // Orbiting Covers
            orbitalItems.forEachIndexed { index, item ->
                val song = item.song
                val angle = orbitAngleRad + item.initialAngleRad

                val cosA = cos(angle.toDouble()).toFloat()
                val sinA = sin(angle.toDouble()).toFloat()

                val xPx = cosA * radiusX
                val yPx = sinA * radiusY

                val z = sinA
                val normalizedZ = ((z + 1f) / 2f).coerceIn(0f, 1f)

                val scale = 0.65f + (normalizedZ * 0.65f)
                val alpha = 0.40f + (normalizedZ * 0.60f)
                val isFlashing = flashStates.getOrElse(index) { false }

                val borderColor by animateColorAsState(
                    targetValue = if (isFlashing) primaryAccent else outlineVariant.copy(alpha = 0.35f),
                    animationSpec = tween(150),
                    label = "CoverBorderColor"
                )

                val coverShape = CircleShape

                Box(
                    modifier = Modifier
                        .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                        .zIndex(z)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        }
                        .clip(coverShape)
                        .border(if (isFlashing) 3.dp else 1.5.dp, borderColor, coverShape)
                        .clickable {
                            triggerCascadeFlash()
                            onSongClick(song)
                        }
                        .size(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    OptimizedAlbumArt(
                        uri = song.albumArtUriString,
                        title = song.title,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Central Planet Core - Monochromatic Theme Primary with Black Shuffle Icon
            Box(
                modifier = Modifier
                    .zIndex(0f)
                    .scale(planetPulseScale)
                    .size(100.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                triggerCascadeFlash()
                                if (displaySongs.isNotEmpty()) {
                                    playerViewModel.shuffleAllSongs("YoMix")
                                }
                            },
                            onPress = {
                                isHolding = true
                                val holdJob = scope.launch {
                                    holdProgress.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(durationMillis = 3000, easing = LinearEasing)
                                    )
                                    if (holdProgress.value >= 0.99f) {
                                        triggerCascadeFlash()
                                        onRebuildMix()
                                    }
                                }
                                try {
                                    awaitRelease()
                                } finally {
                                    isHolding = false
                                    holdJob.cancel()
                                    scope.launch {
                                        holdProgress.animateTo(
                                            targetValue = 0f,
                                            animationSpec = tween(durationMillis = 350)
                                        )
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                // Radial Progress Arc
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val progressSweep = holdProgress.value * 360f
                    if (progressSweep > 0f) {
                        drawArc(
                            color = primaryAccent,
                            startAngle = -90f,
                            sweepAngle = progressSweep,
                            useCenter = false,
                            style = Stroke(width = 5.dp.toPx())
                        )
                    }
                }

                // Monochromatic Theme Surface
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(primaryAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = "YoMix Shuffle",
                            tint = Color.Black,
                            modifier = Modifier.size(38.dp)
                        )
                        if (holdProgress.value > 0f) {
                            Text(
                                text = "${(holdProgress.value * 3).toInt() + 1}s",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
