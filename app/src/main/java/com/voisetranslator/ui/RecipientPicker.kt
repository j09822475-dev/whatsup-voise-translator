package com.voisetranslator.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Chooses which saved WhatsApp chat the next message goes to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipientPicker(
    state: UiState,
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
) {
    val recipients = state.settings.recipients

    if (recipients.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(
                "Ни одного чата ещё не добавлено — без номера WhatsApp спросит адресата сам.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenSettings) { Text("Добавить чат") }
        }
        return
    }

    var expanded by remember { mutableStateOf(false) }
    val selected = state.settings.selectedRecipient

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        OutlinedTextField(
            value = selected?.name.orEmpty(),
            onValueChange = { },
            readOnly = true,
            label = { Text("Кому") },
            supportingText = {
                val phone = selected?.phone.orEmpty()
                if (phone.isNotBlank()) Text("+$phone")
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )

        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            recipients.forEach { recipient ->
                DropdownMenuItem(
                    text = { Text(recipient.name) },
                    onClick = {
                        viewModel.selectRecipient(recipient.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
