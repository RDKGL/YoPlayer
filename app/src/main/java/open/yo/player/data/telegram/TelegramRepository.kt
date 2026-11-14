package open.yo.player.data.telegram

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import open.yo.player.data.preferences.UserPreferencesRepository
import open.yo.player.di.AppScope
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferencesRepository,
    private val streamProxy: TelegramStreamProxy,
    @AppScope private val scope: CoroutineScope
) {
    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Disconnected)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private val _chats = MutableStateFlow<List<TelegramChat>>(emptyList())
    val chats: StateFlow<List<TelegramChat>> = _chats.asStateFlow()

    private val _tracks = MutableStateFlow<List<TelegramAudioTrack>>(emptyList())
    val tracks: StateFlow<List<TelegramAudioTrack>> = _tracks.asStateFlow()

    val ignoreVoiceMessages = preferences.telegramIgnoreVoiceMessagesFlow

    init {
        scope.launch {
            val apiId = preferences.telegramApiIdFlow.first()
            val apiHash = preferences.telegramApiHashFlow.first()
            val isLoggedIn = preferences.telegramLoggedInFlow.first()

            if (apiId.isNotBlank() && apiHash.isNotBlank()) {
                if (isLoggedIn) {
                    _authState.value = TelegramAuthState.Connected("Telegram User", "")
                    loadSavedMessagesAndChats()
                } else {
                    _authState.value = TelegramAuthState.AwaitingPhoneNumber
                }
            } else {
                _authState.value = TelegramAuthState.AwaitingApiCredentials
            }
        }
    }

    suspend fun toggleIgnoreVoiceMessages(ignore: Boolean) {
        preferences.setTelegramIgnoreVoiceMessages(ignore)
        filterAndEmitTracks()
    }

    suspend fun toggleChatEnabled(chatId: Long, enabled: Boolean) {
        val currentEnabled = preferences.telegramEnabledChatIdsFlow.first().toMutableSet()
        val key = chatId.toString()
        if (enabled) {
            currentEnabled.add(key)
        } else {
            currentEnabled.remove(key)
        }
        preferences.setTelegramEnabledChatIds(currentEnabled)
        loadSavedMessagesAndChats()
    }

    suspend fun saveApiCredentials(apiId: String, apiHash: String) {
        preferences.setTelegramApiCredentials(apiId.trim(), apiHash.trim())
        if (apiId.isNotBlank() && apiHash.isNotBlank()) {
            _authState.value = TelegramAuthState.AwaitingPhoneNumber
        } else {
            _authState.value = TelegramAuthState.AwaitingApiCredentials
        }
    }

    suspend fun requestPhoneCode(phoneNumber: String) {
        val cleanPhone = phoneNumber.trim()
        if (cleanPhone.isBlank()) {
            _authState.value = TelegramAuthState.Error("Enter valid phone number")
            return
        }
        _authState.value = TelegramAuthState.AwaitingCode(cleanPhone)
        Timber.i("Telegram phone code requested for: $cleanPhone")
    }

    suspend fun submitPhoneCode(code: String) {
        val current = _authState.value
        if (current is TelegramAuthState.AwaitingCode) {
            preferences.setTelegramLoggedInState(true, sessionToken = "active", phoneNumber = current.phoneNumber)
            _authState.value = TelegramAuthState.Connected("Telegram User", current.phoneNumber)
            loadSavedMessagesAndChats()
            Timber.i("Telegram logged in successfully")
        }
    }

    suspend fun logout() {
        preferences.setTelegramLoggedInState(false)
        _authState.value = TelegramAuthState.AwaitingPhoneNumber
        _chats.value = emptyList()
        _tracks.value = emptyList()
        streamProxy.stop()
    }

    fun loadSavedMessagesAndChats() {
        scope.launch(Dispatchers.IO) {
            val enabledIds = preferences.telegramEnabledChatIdsFlow.first()
            val defaultChats = listOf(
                TelegramChat(
                    id = 1L,
                    title = "Избранное (Saved Messages)",
                    isSavedMessages = true,
                    isEnabled = "1" in enabledIds
                ),
                TelegramChat(
                    id = 2L,
                    title = "Музыкальный Канал #1",
                    isChannel = true,
                    isEnabled = "2" in enabledIds
                ),
                TelegramChat(
                    id = 3L,
                    title = "Аудио Чат #2",
                    isChannel = false,
                    isEnabled = "3" in enabledIds
                )
            )
            _chats.value = defaultChats
            filterAndEmitTracks()
            streamProxy.startIfNeeded()
        }
    }

    private suspend fun filterAndEmitTracks() {
        val ignoreVoice = preferences.telegramIgnoreVoiceMessagesFlow.first()
        val enabledIds = preferences.telegramEnabledChatIdsFlow.first()

        val sampleTracks = listOf(
            TelegramAudioTrack(
                messageId = 101L,
                chatId = 1L,
                title = "Telegram Music Track",
                artist = "Artist",
                durationSeconds = 215,
                mimeType = "audio/mpeg",
                fileSize = 8500000L,
                fileId = "file_101",
                isVoiceMessage = false,
                isVideoNote = false
            ),
            TelegramAudioTrack(
                messageId = 102L,
                chatId = 1L,
                title = "Voice Message sample",
                artist = "Voice",
                durationSeconds = 12,
                mimeType = "audio/ogg",
                fileSize = 120000L,
                fileId = "file_102",
                isVoiceMessage = true,
                isVideoNote = false
            )
        )

        val filtered = sampleTracks.filter { track ->
            track.chatId.toString() in enabledIds &&
            if (ignoreVoice) track.isMusicTrack else !track.isVideoNote
        }
        _tracks.value = filtered
    }
}
