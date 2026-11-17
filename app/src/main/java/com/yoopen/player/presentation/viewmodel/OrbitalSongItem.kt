package com.yoopen.player.presentation.viewmodel

import androidx.compose.runtime.Immutable
import com.yoopen.player.data.model.Song

/**
 * Represents a track item orbiting in the 3D YoMix Orbital Space.
 */
@Immutable
data class OrbitalSongItem(
    val song: Song,
    val initialAngleRad: Float,
    val isFlashing: Boolean = false
)
