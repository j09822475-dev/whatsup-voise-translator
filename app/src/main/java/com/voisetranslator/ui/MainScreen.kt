package com.voisetranslator.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voisetranslator.core.DeliveryMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onRequestMic: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error, state.notice) {
        val message = state.error ?: state.notice
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.dismissMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Voise Translator") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.whatsAppInstalled) {
                Warning("WhatsApp не найден на устройстве — отправлять будет некуда.")
            }
            if (!state.speechAvailable) {
                Warning("На устройстве нет службы распознавания речи. Установите «Речевые сервисы Google».")
            }

            RecipientPicker(state = state, viewModel = viewModel, onOpenSettings = onOpenSettings)

            Spacer(Modifier.height(24.dp))

            MicButton(
                stage = state.stage,
                level = state.micLevel,
                onClick = {
                    if (state.stage == Stage.LISTENING) viewModel.stopDictation() else onRequestMic()
                },
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = state.stage.caption(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            AnimatedVisibility(visible = state.displayedRussian.isNotBlank()) {
                TextBlock(
                    label = "Русский (что услышано)",
                    value = state.displayedRussian,
                    enabled = state.stage != Stage.LISTENING && !state.isBusy,
                    onValueChange = viewModel::editRussian,
                    readOnly = state.stage == Stage.LISTENING,
                    trailing = {
                        TextButton(
                            onClick = { viewModel.retranslate(state.russianText) },
                            enabled = state.russianText.isNotBlank() && !state.isBusy,
                        ) { Text("Перевести заново") }
                    },
                )
            }

            AnimatedVisibility(visible = state.englishText.isNotBlank()) {
                TextBlock(
                    label = "English (что уйдёт)",
                    value = state.englishText,
                    enabled = !state.isBusy,
                    onValueChange = viewModel::editEnglish,
                    readOnly = false,
                    trailing = {
                        if (state.settings.deliveryMode == DeliveryMode.AUDIO) {
                            TextButton(
                                onClick = viewModel::resynthesize,
                                enabled = !state.isBusy,
                            ) { Text("Озвучить заново") }
                        }
                        TextButton(
                            onClick = { viewModel.previewVoice(state.settings.voiceName) },
                            enabled = !state.isBusy,
                        ) { Text("Прослушать") }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            val canSend = state.englishText.isNotBlank() &&
                !state.isBusy &&
                state.whatsAppInstalled &&
                (state.settings.deliveryMode == DeliveryMode.TEXT || state.audio != null)

            FilledTonalButton(
                onClick = viewModel::send,
                enabled = canSend,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    when (state.settings.deliveryMode) {
                        DeliveryMode.AUDIO -> "Отправить голосом в WhatsApp"
                        DeliveryMode.TEXT -> "Отправить текстом в WhatsApp"
                    }
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun MicButton(stage: Stage, level: Float, onClick: () -> Unit) {
    val listening = stage == Stage.LISTENING
    val busy = stage == Stage.TRANSLATING || stage == Stage.SYNTHESIZING || stage == Stage.SENDING
    val scale by animateFloatAsState(
        targetValue = if (listening) 1f + level * 0.35f else 1f,
        label = "micLevel",
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
        if (listening) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(scale)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape)
            )
        }

        Box(
            modifier = Modifier
                .size(96.dp)
                .background(
                    if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    CircleShape,
                )
                .clickable(enabled = !busy, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                busy -> CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                listening -> Icon(
                    Icons.Default.Stop,
                    contentDescription = "Остановить",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )

                else -> Icon(
                    Icons.Default.Mic,
                    contentDescription = "Начать диктовку",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

@Composable
private fun TextBlock(
    label: String,
    value: String,
    enabled: Boolean,
    readOnly: Boolean,
    onValueChange: (String) -> Unit,
    trailing: @Composable () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                enabled = enabled,
                readOnly = readOnly,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) { trailing() }
        }
    }
}

@Composable
private fun Warning(text: String) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun Stage.caption(): String = when (this) {
    Stage.IDLE -> "Нажмите на микрофон и говорите по-русски"
    Stage.LISTENING -> "Слушаю… нажмите ещё раз, чтобы остановить"
    Stage.TRANSLATING -> "Перевожу на английский…"
    Stage.SYNTHESIZING -> "Озвучиваю…"
    Stage.READY -> "Готово к отправке"
    Stage.SENDING -> "Передаю в WhatsApp…"
}
