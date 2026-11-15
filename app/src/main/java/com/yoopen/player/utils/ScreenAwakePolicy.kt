package open.yo.player.utils

internal fun shouldKeepScreenAwake(
    preferenceEnabled: Boolean,
    isPlaying: Boolean,
): Boolean = preferenceEnabled && isPlaying
