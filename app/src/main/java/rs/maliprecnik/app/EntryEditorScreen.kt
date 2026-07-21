package rs.maliprecnik.app

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun EntryEditorScreen(
    modifier: Modifier = Modifier,
    resetKey: Int,
    initialEntry: DictionaryEntry?,
    statusMessage: String,
    proposalRulesAccepted: Boolean,
    onAcceptProposalRules: () -> Unit,
    onSave: (DictionaryEntry) -> Unit,
    onSuggest: ((DictionaryEntry) -> Unit)?,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit
) {
    val initialOptions = initialEntry?.options?.takeIf { it.isNotEmpty() }
        ?: listOf(ReplacementOption())

    var foreignWordInput by rememberSaveable(resetKey) { mutableStateOf(initialEntry?.foreignWord.orEmpty()) }
    var originInput by rememberSaveable(resetKey) { mutableStateOf(initialEntry?.origin.orEmpty()) }
    var addendumInput by rememberSaveable(resetKey) { mutableStateOf(initialEntry?.addendum.orEmpty()) }
    var replacementInputs by rememberSaveable(resetKey) {
        mutableStateOf(initialOptions.map { it.replacementWord })
    }
    var explanationInputs by rememberSaveable(resetKey) {
        mutableStateOf(initialOptions.map { it.explanation })
    }
    var validationMessage by rememberSaveable(resetKey) { mutableStateOf("") }
    var showProposalRulesDialog by rememberSaveable(resetKey) { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable(resetKey) { mutableStateOf(false) }
    var pendingRemoveOptionIndex by rememberSaveable(resetKey) { mutableStateOf<Int?>(null) }

    fun buildEntryOrShowError(): DictionaryEntry? {
        val foreignWord = foreignWordInput.trim()
        if (foreignWord.isBlank()) {
            validationMessage = "Потребно је унети туђицу."
            return null
        }

        val options = replacementInputs.indices.mapNotNull { index ->
            val replacementWord = replacementInputs[index].trim()
            if (replacementWord.isBlank()) {
                null
            } else {
                ReplacementOption(
                    foreignEntryId = initialEntry?.id ?: 0,
                    replacementWord = replacementWord,
                    explanation = explanationInputs.getOrNull(index).orEmpty().trim(),
                    weight = 1
                )
            }
        }.mapIndexed { optionIndex, option ->
            option.copy(weight = optionIndex + 1)
        }

        validationMessage = ""
        return DictionaryEntry(
            id = initialEntry?.id ?: 0,
            foreignWord = foreignWord,
            origin = originInput.trim(),
            addendum = addendumInput.trim(),
            options = options
        )
    }

    fun saveEntry() {
        buildEntryOrShowError()?.let(onSave)
    }

    fun suggestEntry() {
        val callback = onSuggest ?: return
        val entry = buildEntryOrShowError() ?: return
        if (proposalRulesAccepted) {
            callback(entry)
        } else {
            showProposalRulesDialog = true
        }
    }

    fun updateReplacement(index: Int, value: String) {
        replacementInputs = replacementInputs.updatedAt(index, value)
    }

    fun updateExplanation(index: Int, value: String) {
        explanationInputs = explanationInputs.updatedAt(index, value)
    }

    fun addOption() {
        replacementInputs = replacementInputs + ""
        explanationInputs = explanationInputs + ""
    }

    fun removeOption(index: Int) {
        if (replacementInputs.size <= 1) {
            replacementInputs = listOf("")
            explanationInputs = listOf("")
            return
        }
        replacementInputs = replacementInputs.removedAt(index)
        explanationInputs = explanationInputs.removedAt(index)
    }

    fun moveOption(index: Int, offset: Int) {
        val targetIndex = index + offset
        if (targetIndex !in replacementInputs.indices) return
        replacementInputs = replacementInputs.moved(index, targetIndex)
        explanationInputs = explanationInputs.moved(index, targetIndex)
    }

    if (showProposalRulesDialog) {
        AlertDialog(
            onDismissRequest = { showProposalRulesDialog = false },
            title = { Text("Правила за предлоге") },
            text = {
                Text(
                    "Да би послао предлог, прихвати правила: без увредљивог, незаконитог, " +
                        "вулгарног или обмањујућег садржаја; без личних и осетљивих података; " +
                        "предлози пролазе уреднички преглед пре јавног приказа."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val callback = onSuggest
                        val entry = buildEntryOrShowError()
                        showProposalRulesDialog = false
                        if (callback != null && entry != null) {
                            onAcceptProposalRules()
                            callback(entry)
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Прихватам и шаљем")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showProposalRulesDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Одустани")
                }
            }
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

    pendingRemoveOptionIndex?.let { optionIndex ->
        AlertDialog(
            onDismissRequest = { pendingRemoveOptionIndex = null },
            title = { Text("Уклањање предлога") },
            text = { Text("Да ли желите да уклоните овај предлог?") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRemoveOptionIndex = null
                        removeOption(optionIndex)
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Да")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingRemoveOptionIndex = null },
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
                        value = foreignWordInput,
                        onValueChange = { foreignWordInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Туђица") }
                    )
                    OutlinedTextField(
                        value = originInput,
                        onValueChange = { originInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Порекло") }
                    )

                    replacementInputs.indices.forEach { index ->
                        ReplacementOptionEditor(
                            index = index,
                            replacementWord = replacementInputs[index],
                            explanation = explanationInputs.getOrNull(index).orEmpty(),
                            onReplacementChange = { updateReplacement(index, it) },
                            onExplanationChange = { updateExplanation(index, it) },
                            onRemove = { pendingRemoveOptionIndex = index },
                            onMoveUp = { moveOption(index, -1) },
                            onMoveDown = { moveOption(index, 1) },
                            canMoveUp = index > 0,
                            canMoveDown = index < replacementInputs.lastIndex
                        )
                    }

                    OutlinedButton(
                        onClick = { addOption() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Додај још једну српскословенску реч")
                    }

                    OutlinedTextField(
                        value = addendumInput,
                        onValueChange = { addendumInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        label = { Text("Додатак") }
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { saveEntry() },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (initialEntry == null) "Додај" else "Сачувај")
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
                            Text(if (initialEntry == null) "Очисти" else "Назад")
                        }
                    }

                    if (onSuggest != null) {
                        OutlinedButton(
                            onClick = { suggestEntry() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (initialEntry == null) "Предложи као нову реч" else "Предложи као измену")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplacementOptionEditor(
    index: Int,
    replacementWord: String,
    explanation: String,
    onReplacementChange: (String) -> Unit,
    onExplanationChange: (String) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Предлог ${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedTextField(
                value = replacementWord,
                onValueChange = onReplacementChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("Српскословенска реч") }
            )
            OutlinedTextField(
                value = explanation,
                onValueChange = onExplanationChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("Појашњење") }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRemove,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Уклони овај предлог")
                }
                OutlinedButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("^")
                }
                OutlinedButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("v")
                }
            }
        }
    }
}

private fun List<String>.updatedAt(index: Int, value: String): List<String> =
    mapIndexed { currentIndex, currentValue ->
        if (currentIndex == index) value else currentValue
    }

private fun List<String>.removedAt(index: Int): List<String> =
    filterIndexed { currentIndex, _ -> currentIndex != index }

private fun List<String>.moved(fromIndex: Int, toIndex: Int): List<String> {
    val mutable = toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex, item)
    return mutable
}
