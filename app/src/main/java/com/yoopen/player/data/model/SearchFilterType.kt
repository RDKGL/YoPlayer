package open.yo.player.data.model

import androidx.compose.runtime.Immutable

@Immutable
enum class SearchFilterType {
    ALL,
    SONGS,
    ALBUMS,
    ARTISTS,
    PLAYLISTS
}
