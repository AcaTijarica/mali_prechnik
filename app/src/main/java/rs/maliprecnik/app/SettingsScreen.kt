package rs.maliprecnik.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    settings: DisplaySettings,
    statusMessage: String,
    onApplySettings: (DisplaySettings) -> Unit
) {
    var showOrigin by rememberSaveable(settings.showOrigin) { mutableStateOf(settings.showOrigin) }
    var showExplanations by rememberSaveable(settings.showExplanations) {
        mutableStateOf(settings.showExplanations)
    }
    var showAddendum by rememberSaveable(settings.showAddendum) {
        mutableStateOf(settings.showAddendum)
    }
    var selectedFontKey by rememberSaveable(settings.fontChoice.storageKey) {
        mutableStateOf(settings.fontChoice.storageKey)
    }
    var selectedFontSizeKey by rememberSaveable(settings.fontSizeChoice.storageKey) {
        mutableStateOf(settings.fontSizeChoice.storageKey)
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            StatusText(statusMessage)
        }
        item {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Приказ туђица",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    SettingSwitchRow(
                        label = "Порекло туђице",
                        checked = showOrigin,
                        onCheckedChange = { showOrigin = it }
                    )
                    SettingSwitchRow(
                        label = "Појашњења за сваку туђицу",
                        checked = showExplanations,
                        onCheckedChange = { showExplanations = it }
                    )
                    SettingSwitchRow(
                        label = "Додатак за сваку туђицу",
                        checked = showAddendum,
                        onCheckedChange = { showAddendum = it }
                    )
                    SettingDropdown(
                        label = "Писмо",
                        selectedLabel = AppFontChoice.fromStorageKey(selectedFontKey).label,
                        options = AppFontChoice.values().map { it.storageKey to it.label },
                        onOptionSelected = { selectedFontKey = it }
                    )
                    SettingDropdown(
                        label = "Величина слова",
                        selectedLabel = AppFontSizeChoice.fromStorageKey(selectedFontSizeKey).label,
                        options = AppFontSizeChoice.values().map { it.storageKey to it.label },
                        onOptionSelected = { selectedFontSizeKey = it }
                    )
                    Button(
                        onClick = {
                            onApplySettings(
                                DisplaySettings(
                                    showOrigin = showOrigin,
                                    showExplanations = showExplanations,
                                    showAddendum = showAddendum,
                                    fontChoice = AppFontChoice.fromStorageKey(selectedFontKey),
                                    fontSizeChoice = AppFontSizeChoice.fromStorageKey(selectedFontSizeKey)
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Постави")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingDropdown(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    onOptionSelected: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                singleLine = true
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (key, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            onOptionSelected(key)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
