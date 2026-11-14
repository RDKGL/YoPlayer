package open.yo.player.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import open.yo.player.R
import open.yo.player.data.telegram.TelegramAuthState
import open.yo.player.data.telegram.TelegramRepository

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TelegramSettingsScreen(
    onBack: () -> Unit,
    telegramRepository: TelegramRepository = hiltViewModel()
) {
    val authState by telegramRepository.authState.collectAsStateWithLifecycle()
    val chats by telegramRepository.chats.collectAsStateWithLifecycle()
    val ignoreVoice by telegramRepository.ignoreVoiceMessages.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()

    var apiIdInput by remember { mutableStateOf("") }
    var apiHashInput by remember { mutableStateOf("") }
    var phoneInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Telegram Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            )
        }
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "auth_card") {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.telegram),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Column {
                                Text(
                                    text = "Telegram Account",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Music streaming from Saved Messages and channels",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        when (val state = authState) {
                            TelegramAuthState.AwaitingApiCredentials, TelegramAuthState.Disconnected -> {
                                Text(
                                    text = "Step 1: Enter your Telegram API ID & Hash from my.telegram.org",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                OutlinedTextField(
                                    value = apiIdInput,
                                    onValueChange = { apiIdInput = it },
                                    label = { Text("API ID (e.g. 28475912)") },
                                    leadingIcon = { Icon(Icons.Rounded.Key, null) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = apiHashInput,
                                    onValueChange = { apiHashInput = it },
                                    label = { Text("API Hash") },
                                    leadingIcon = { Icon(Icons.Rounded.VpnKey, null) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Button(
                                    onClick = {
                                        scope.launch {
                                            telegramRepository.saveApiCredentials(apiIdInput, apiHashInput)
                                        }
                                    },
                                    enabled = apiIdInput.isNotBlank() && apiHashInput.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Save API Credentials")
                                }
                            }
                            TelegramAuthState.AwaitingPhoneNumber -> {
                                Text(
                                    text = "Step 2: Enter your Telegram Phone Number",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                OutlinedTextField(
                                    value = phoneInput,
                                    onValueChange = { phoneInput = it },
                                    label = { Text("Phone Number (e.g. +79991234567)") },
                                    leadingIcon = { Icon(Icons.Rounded.Phone, null) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Button(
                                    onClick = {
                                        scope.launch {
                                            telegramRepository.requestPhoneCode(phoneInput)
                                        }
                                    },
                                    enabled = phoneInput.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Send Confirmation Code")
                                }
                            }
                            is TelegramAuthState.AwaitingCode -> {
                                Text(
                                    text = "Step 3: Enter the code sent to ${state.phoneNumber}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                OutlinedTextField(
                                    value = codeInput,
                                    onValueChange = { codeInput = it },
                                    label = { Text("Confirmation Code") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Button(
                                    onClick = {
                                        scope.launch {
                                            telegramRepository.submitPhoneCode(codeInput)
                                        }
                                    },
                                    enabled = codeInput.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Sign In")
                                }
                            }
                            is TelegramAuthState.Connected -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Connected as ${state.username}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            telegramRepository.logout()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Log Out")
                                }
                            }
                            is TelegramAuthState.Error -> {
                                Text(
                                    text = "Error: ${state.message}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            telegramRepository.saveApiCredentials("", "")
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Reset Credentials")
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }

            if (authState is TelegramAuthState.Connected) {
                item(key = "voice_filter_card") {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Rounded.MicOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Column {
                                    Text(
                                        text = "Игнорировать голосовые и кружки",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Пропускать голосовые сообщения и видео-кружочки, импортировать только аудиозаписи",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = ignoreVoice,
                                onCheckedChange = {
                                    scope.launch {
                                        telegramRepository.toggleIgnoreVoiceMessages(it)
                                    }
                                }
                            )
                        }
                    }
                }

                item(key = "chats_header") {
                    Text(
                        text = "Выбор чатов и каналов для музыки",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(chats, key = { it.id }) { chat ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Rounded.Forum,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Column {
                                    Text(
                                        text = chat.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (chat.isSavedMessages) "Ваши сохранённые треки" else if (chat.isChannel) "Музыкальный канал" else "Чат",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = chat.isEnabled,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        telegramRepository.toggleChatEnabled(chat.id, checked)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
