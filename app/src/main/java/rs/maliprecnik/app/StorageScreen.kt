package rs.maliprecnik.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Екран за лично складиште, јавну службену базу и увоз/извоз. */
@Composable
fun StorageScreen(
    modifier: Modifier = Modifier,
    entries: List<DictionaryEntry>,
    statusMessage: String,
    databaseSizeBytes: Long,
    databaseLastModifiedMillis: Long,
    publicEntries: List<DictionaryEntry>,
    publicDatabaseSizeBytes: Long,
    publicStorageUpdatedMillis: Long,
    onImportJson: () -> Unit,
    onExportJson: () -> Unit,
    onImportDatabase: () -> Unit,
    onExportDatabase: () -> Unit,
    onOverwritePersonalStorage: () -> Unit,
    onAddNewWordsFromPublicStorage: () -> Unit
) {
    var showOverwriteConfirmation by rememberSaveable { mutableStateOf(false) }
    var showAddNewConfirmation by rememberSaveable { mutableStateOf(false) }

    if (showOverwriteConfirmation) {
        AlertDialog(
            onDismissRequest = { showOverwriteConfirmation = false },
            title = { Text("Препис личног складишта") },
            text = {
                Text(
                    "Ово ће лично складиште потпуно заменити јавним складиштем које је упаковано у ову верзију апликације. " +
                        "Ако желиш да сачуваш личне измене, прво извези складиште."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOverwriteConfirmation = false
                        onOverwritePersonalStorage()
                    }
                ) {
                    Text("Препиши")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverwriteConfirmation = false }) {
                    Text("Одустани")
                }
            }
        )
    }

    if (showAddNewConfirmation) {
        AlertDialog(
            onDismissRequest = { showAddNewConfirmation = false },
            title = { Text("Допуна јавним складиштем") },
            text = {
                Text(
                    "Ово ће у лично складиште додати само туђице које постоје у јавном складишту, " +
                        "а не постоје у личном. Постојеће личне измене се не преписују."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAddNewConfirmation = false
                        onAddNewWordsFromPublicStorage()
                    }
                ) {
                    Text("Додај")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNewConfirmation = false }) {
                    Text("Одустани")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            StatusText(statusMessage)
        }
        item {
            StorageCard(title = "Лично складиште") {
                StatisticLine(label = "Број туђица", value = entries.size.toString())
                StatisticLine(label = "Величина складишта", value = formatByteSize(databaseSizeBytes))
                StatisticLine(label = "Последња измена", value = formatTimestamp(databaseLastModifiedMillis))
            }
        }
        item {
            StorageCard(title = "Јавно складиште") {
                StatisticLine(label = "Број туђица", value = publicEntries.size.toString())
                StatisticLine(label = "Величина складишта", value = formatByteSize(publicDatabaseSizeBytes))
                StatisticLine(label = "Последње освежавање", value = formatTimestamp(publicStorageUpdatedMillis))
                OutlinedButton(
                    onClick = { showOverwriteConfirmation = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Препиши лично складиште")
                }
                OutlinedButton(
                    onClick = { showAddNewConfirmation = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Додај нове речи из јавног складишта")
                }
            }
        }
        item {
            StorageCard(title = "Увоз и извоз") {
                Button(
                    onClick = onImportJson,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Увези JSON")
                }
                Button(
                    onClick = onExportJson,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Извези JSON")
                }
                OutlinedButton(
                    onClick = onImportDatabase,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Увези DB")
                }
                OutlinedButton(
                    onClick = onExportDatabase,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Извези DB")
                }
            }
        }
    }
}

@Composable
private fun StorageCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun StatisticLine(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
