package rs.maliprecnik.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OldWordEditorScreen(
    modifier: Modifier = Modifier,
    resetKey: Int,
    initialOldWord: OldWordEntry?,
    statusMessage: String,
    onSave: (OldWordEntry) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit
) {
    var oldWordInput by rememberSaveable(resetKey) { mutableStateOf(initialOldWord?.oldWord.orEmpty()) }
    var addendumInput by rememberSaveable(resetKey) { mutableStateOf(initialOldWord?.addendum.orEmpty()) }
    var synonymsInput by rememberSaveable(resetKey) {
        mutableStateOf(initialOldWord?.synonyms?.joinToString("\n").orEmpty())
    }
    var validationMessage by rememberSaveable(resetKey) { mutableStateOf("") }
    var showDeleteDialog by rememberSaveable(resetKey) { mutableStateOf(false) }

    fun buildOldWordOrShowError(): OldWordEntry? {
        val oldWord = oldWordInput.trim()
        if (oldWord.isBlank()) {
            validationMessage = "Потребно је унети стару реч."
            return null
        }

        val synonyms = synonymsInput
            .split(Regex("[,;\\n]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeWord(it) }

        if (synonyms.isEmpty()) {
            validationMessage = "Потребно је унети бар једну сличнозначницу."
            return null
        }

        validationMessage = ""
        return OldWordEntry(
            id = initialOldWord?.id ?: 0,
            oldWord = oldWord,
            addendum = addendumInput.trim(),
            synonyms = synonyms
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Уклањање речи") },
            text = { Text("Да ли заиста желите да уклоните ову реч из складишта?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onDelete?.invoke()
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Да")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Не")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            StatusText(if (validationMessage.isBlank()) statusMessage else validationMessage)
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
                    OutlinedTextField(
                        value = oldWordInput,
                        onValueChange = {
                            oldWordInput = it
                            validationMessage = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Стара реч") }
                    )
                    OutlinedTextField(
                        value = addendumInput,
                        onValueChange = {
                            addendumInput = it
                            validationMessage = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        label = { Text("Додатак") }
                    )
                    OutlinedTextField(
                        value = synonymsInput,
                        onValueChange = {
                            synonymsInput = it
                            validationMessage = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        label = { Text("Сличнозначнице") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { buildOldWordOrShowError()?.let(onSave) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (initialOldWord == null) "Додај" else "Сачувај")
                        }
                        if (onDelete != null) {
                            OutlinedButton(
                                onClick = { showDeleteDialog = true },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Уклони")
                            }
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (initialOldWord == null) "Очисти" else "Назад")
                        }
                    }
                }
            }
        }
    }
}
