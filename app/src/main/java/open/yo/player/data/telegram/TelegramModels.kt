package open.yo.player.data.telegram

import open.yo.player.data.model.Song

data class TelegramChat(
    val id: Long,
    val title: String,
    val isSavedMessages: Boolean = false,
    val isChannel: Boolean = false,
    val isEnabled: Boolean = true,
    val audioTrackCount: Int = 0,
    val photoUrl: String? = null
)

data class TelegramAudioTrack(
    val messageId: Long,
    val chatId: Long,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    val mimeType: String,
    val fileSize: Long,
    val fileId: String,
    val isVoiceMessage: Boolean = false,
    val isVideoNote: Boolean = false,
    val artworkUrl: String? = null,
    val dateEpochMs: Long = System.currentTimeMillis()
) {
    val isMusicTrack: Boolean
        get() = !isVoiceMessage && !isVideoNote && (
            mimeType.startsWith("audio/") ||
            mimeType.endsWith("mp3") ||
            mimeType.endsWith("flac") ||
            mimeType.endsWith("m4a") ||
            mimeType.endsWith("wav") ||
            mimeType.endsWith("ogg")
        )

    fun toSong(): Song {
        val streamUri = "telegram://stream?chat_id=$chatId&msg_id=$messageId"
        return Song(
            id = streamUri.hashCode().toLong(),
            mediaId = streamUri,
            title = title.ifBlank { "Telegram Audio" },
            displayArtist = artist.ifBlank { "Telegram" },
            album = "Telegram Music",
            durationMs = durationSeconds * 1000L,
            artistId = -1L,
            albumId = -1L,
            folderPath = "Telegram",
            trackNumber = 0,
            discNumber = 0,
            year = 0,
            dateAddedEpochMs = dateEpochMs
        )
    }
}

sealed interface TelegramAuthState {
    data object Disconnected : TelegramAuthState
    data object AwaitingApiCredentials : TelegramAuthState
    data object AwaitingPhoneNumber : TelegramAuthState
    data class AwaitingCode(val phoneNumber: String) : TelegramAuthState
    data object AwaitingPassword : TelegramAuthState
    data class Connected(val username: String, val phoneNumber: String) : TelegramAuthState
    data class Error(val message: String) : TelegramAuthState
}
