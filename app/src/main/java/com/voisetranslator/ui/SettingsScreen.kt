package com.voisetranslator.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.voisetranslator.core.DeliveryMode
import com.voisetranslator.core.TranslationEngine
import com.voisetranslator.overlay.BubbleService
import com.voisetranslator.whatsapp.AutoSendCoordinator
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = state.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
        ) {
            Section("Что отправлять") {
                DeliveryMode.entries.forEach { mode ->
                    RadioRow(
                        selected = settings.deliveryMode == mode,
                        title = when (mode) {
                            DeliveryMode.AUDIO -> "Аудиофайл с английской речью"
                            DeliveryMode.TEXT -> "Английский текст"
                        },
                        subtitle = when (mode) {
                            DeliveryMode.AUDIO ->
                                "WhatsApp покажет вложение с плеером. Настоящее голосовое (с волной) без root невозможно."
                            DeliveryMode.TEXT ->
                                "Текст подставляется в поле ввода чата по ссылке wa.me."
                        },
                        onClick = { viewModel.setDeliveryMode(mode) },
                    )
                }
            }

            Section("Перевод") {
                TranslationEngine.entries.forEach { engine ->
                    RadioRow(
                        selected = settings.engine == engine,
                        title = when (engine) {
                            TranslationEngine.OFFLINE -> "Офлайн (ML Kit)"
                            TranslationEngine.GEMINI -> "Gemini API"
                        },
                        subtitle = when (engine) {
                            TranslationEngine.OFFLINE ->
                                "Бесплатно и без ключа. Модель ru→en скачивается один раз, дальше работает без интернета."
                            TranslationEngine.GEMINI ->
                                "Лучше справляется с разговорной речью и идиомами. Нужен свой ключ и интернет."
                        },
                        onClick = { viewModel.setEngine(engine) },
                    )
                }

                if (settings.engine == TranslationEngine.OFFLINE) {
                    OutlinedButton(
                        onClick = viewModel::downloadOfflineModel,
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Скачать модель заранее") }
                } else {
                    var key by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("Ключ Gemini API") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { viewModel.setGeminiKey(key) }) { Text("Сохранить ключ") }
                    }

                    var model by remember(settings.geminiModel) { mutableStateOf(settings.geminiModel) }
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Модель") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { viewModel.setGeminiModel(model) }) { Text("Сохранить модель") }
                    }
                }
            }

            Section("Голос") {
                if (state.voices.isEmpty()) {
                    Text(
                        "Список голосов пуст. Откройте системные настройки синтеза речи и скачайте английский язык.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { context.openTtsSettings() },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Настройки синтеза речи") }
                } else {
                    state.voices.forEach { voice ->
                        RadioRow(
                            selected = settings.voiceName == voice.name,
                            title = voice.label,
                            subtitle = if (voice.needsNetwork) "Требуется интернет" else null,
                            onClick = { viewModel.setVoice(voice.name) },
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { viewModel.previewVoice(settings.voiceName) }) {
                            Text("Прослушать образец")
                        }
                    }
                }

                SliderRow(
                    label = "Скорость речи",
                    value = settings.speechRate,
                    range = 0.5f..2.0f,
                    onChange = viewModel::setSpeechRate,
                )
                SliderRow(
                    label = "Тон",
                    value = settings.pitch,
                    range = 0.5f..2.0f,
                    onChange = viewModel::setPitch,
                )
            }

            Section("Автоматическая отправка") {
                val serviceEnabled = AutoSendCoordinator.isServiceEnabled(context)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Нажимать «Отправить» за меня", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Служба спец. возможностей выберет чат и нажмёт кнопку отправки. " +
                                "Она включается только на 20 секунд после того, как вы сами запустили отправку.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.autoSend,
                        onCheckedChange = viewModel::setAutoSend,
                    )
                }

                if (settings.autoSend && !serviceEnabled) {
                    Text(
                        "Служба ещё не включена в системных настройках — сообщение остановится на экране отправки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedButton(
                        onClick = { context.openAccessibilitySettings() },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Открыть спец. возможности") }
                }
            }

            Section("Плавающая кнопка") {
                val canOverlay = AndroidSettings.canDrawOverlays(context)
                Text(
                    "Круглая кнопка поверх других приложений: нажали прямо в WhatsApp — надиктовали — сообщение ушло.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!canOverlay) {
                        OutlinedButton(onClick = { context.openOverlaySettings() }) {
                            Text("Разрешить наложение")
                        }
                    } else {
                        Button(onClick = { BubbleService.start(context) }) { Text("Показать") }
                        OutlinedButton(onClick = { BubbleService.stop(context) }) { Text("Скрыть") }
                    }
                }
            }

            Section("Чаты") {
                settings.recipients.forEach { recipient ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(recipient.name, fontWeight = FontWeight.Medium)
                            Text(
                                if (recipient.hasPhone) "+${recipient.phone}" else "без номера — WhatsApp спросит адресата",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.removeRecipient(recipient.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить")
                        }
                    }
                }

                AddRecipientRow(onAdd = viewModel::addRecipient)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AddRecipientRow(onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Имя в WhatsApp") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text("Номер в международном формате") },
        placeholder = { Text("+7 900 123-45-67") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(
            onClick = {
                onAdd(name, phone)
                name = ""
                phone = ""
            },
            enabled = name.isNotBlank(),
        ) { Text("Добавить чат") }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            content()
        }
    }
}

@Composable
private fun RadioRow(
    selected: Boolean,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    // Dragging fires continuously; the value is only persisted once the finger lifts.
    var draft by remember(value) { mutableStateOf(value) }

    Column(Modifier.padding(top = 12.dp)) {
        Text(
            "$label: ${(draft * 10).roundToInt() / 10f}×",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onChange(draft) },
            valueRange = range,
            steps = 14,
        )
    }
}

private fun Context.openAccessibilitySettings() {
    startActivity(
        Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun Context.openTtsSettings() {
    // Not every OEM ships this screen under the AOSP action.
    runCatching {
        startActivity(
            Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        startActivity(Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun Context.openOverlaySettings() {
    startActivity(
        Intent(
            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
