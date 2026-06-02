package rs.maliprecnik.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Екран за претрагу у једном одабраном смеру. */
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    entries: List<DictionaryEntry>,
    query: String,
    onQueryChange: (String) -> Unit,
    searchDirection: SearchDirection,
    onSearchDirectionChange: (SearchDirection) -> Unit,
    statusMessage: String,
    proposalRulesAccepted: Boolean,
    onAcceptProposalRules: () -> Unit,
    onEditEntry: (DictionaryEntry) -> Unit,
    onSuggestEntryChange: (DictionaryEntry, String) -> Unit
) {
    val result = remember(query, entries, searchDirection) {
        searchDictionary(query, entries, searchDirection)
    }
    val suggestions = remember(query, entries, searchDirection) {
        searchSuggestions(query, entries, searchDirection)
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
                    SearchDirectionButtons(
                        selectedDirection = searchDirection,
                        onDirectionChange = onSearchDirectionChange
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(searchDirection.inputLabel) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {})
                    )
                    if (result.isEmpty && suggestions.isNotEmpty()) {
                        SearchSuggestionsBlock(
                            suggestions = suggestions,
                            onSuggestionClick = onQueryChange
                        )
                    }
                    SearchResult(
                        query = query,
                        direction = searchDirection,
                        result = result,
                        hasSuggestions = suggestions.isNotEmpty(),
                        proposalRulesAccepted = proposalRulesAccepted,
                        onAcceptProposalRules = onAcceptProposalRules,
                        onEditEntry = onEditEntry,
                        onSuggestEntryChange = onSuggestEntryChange
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchDirectionButtons(
    selectedDirection: SearchDirection,
    onDirectionChange: (SearchDirection) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SearchDirection.entries.forEach { direction ->
            if (direction == selectedDirection) {
                Button(
                    onClick = { onDirectionChange(direction) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(direction.label)
                }
            } else {
                OutlinedButton(
                    onClick = { onDirectionChange(direction) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(direction.label)
                }
            }
        }
    }
}

@Composable
private fun SearchResult(
    query: String,
    direction: SearchDirection,
    result: DictionarySearchResult,
    hasSuggestions: Boolean,
    proposalRulesAccepted: Boolean,
    onAcceptProposalRules: () -> Unit,
    onEditEntry: (DictionaryEntry) -> Unit,
    onSuggestEntryChange: (DictionaryEntry, String) -> Unit
) {
    if (query.isBlank()) {
        Text(
            text = direction.emptyPrompt,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    if (result.isEmpty) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface
        ) {
            Text(
                text = if (hasSuggestions) {
                    "Одабери један од понуђених уноса или настави да куцаш."
                } else {
                    "Није пронађен одговарајући унос у пречнику."
                },
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        result.foreignEntry?.let { entry ->
            EntryDetailSurface(
                entry = entry,
                proposalRulesAccepted = proposalRulesAccepted,
                onAcceptProposalRules = onAcceptProposalRules,
                onEditEntry = onEditEntry,
                onSuggestEntryChange = onSuggestEntryChange
            )
        }
        if (result.replacementMatches.isNotEmpty()) {
            Text(
                text = "Туђице у којима се јавља српскословенска реч",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            result.replacementMatches.forEach { match ->
                ReplacementMatchSurface(match)
            }
        }
    }
}

@Composable
private fun SearchSuggestionsBlock(
    suggestions: List<SearchSuggestion>,
    onSuggestionClick: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Предлози",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surfaceContainerLowest
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                suggestions.forEach { suggestion ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSuggestionClick(suggestion.value) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = suggestion.value,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (suggestion.detail.isNotBlank()) {
                            Text(
                                text = suggestion.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Читљив приказ једне туђице који се отвара из листе `Туђице`. */
@Composable
fun ForeignWordDetailScreen(
    modifier: Modifier = Modifier,
    entry: DictionaryEntry,
    statusMessage: String,
    proposalRulesAccepted: Boolean,
    onAcceptProposalRules: () -> Unit,
    onEditEntry: (DictionaryEntry) -> Unit,
    onSuggestEntryChange: (DictionaryEntry, String) -> Unit
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            StatusText(statusMessage)
        }
        item {
            OutlinedTextField(
                value = entry.foreignWord,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                singleLine = true,
                label = { Text("Туђица") }
            )
        }
        item {
            EntryDetailSurface(
                entry = entry,
                proposalRulesAccepted = proposalRulesAccepted,
                onAcceptProposalRules = onAcceptProposalRules,
                onEditEntry = onEditEntry,
                onSuggestEntryChange = onSuggestEntryChange
            )
        }
    }
}

@Composable
fun EntryDetailSurface(
    entry: DictionaryEntry,
    proposalRulesAccepted: Boolean = true,
    onAcceptProposalRules: () -> Unit = {},
    onEditEntry: (DictionaryEntry) -> Unit,
    onSuggestEntryChange: ((DictionaryEntry, String) -> Unit)? = null
) {
    var showProposalBox by rememberSaveable(entry.id) { mutableStateOf(false) }
    var proposalText by rememberSaveable(entry.id) { mutableStateOf("") }
    var proposalValidationMessage by rememberSaveable(entry.id) { mutableStateOf("") }
    var showProposalRulesDialog by rememberSaveable(entry.id) { mutableStateOf(false) }

    fun sendProposal() {
        val callback = onSuggestEntryChange ?: return
        val cleanedText = proposalText.trim()
        if (cleanedText.isBlank()) {
            proposalValidationMessage = "Потребно је унети текст предлога."
            return
        }
        if (proposalRulesAccepted) {
            callback(entry, cleanedText)
            proposalText = ""
            proposalValidationMessage = ""
            showProposalBox = false
        } else {
            showProposalRulesDialog = true
        }
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
                        val callback = onSuggestEntryChange
                        val cleanedText = proposalText.trim()
                        showProposalRulesDialog = false
                        if (callback != null && cleanedText.isNotBlank()) {
                            onAcceptProposalRules()
                            callback(entry, cleanedText)
                            proposalText = ""
                            proposalValidationMessage = ""
                            showProposalBox = false
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

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DetailBlock(label = "Порекло туђице", value = entry.origin, italic = true)
            ReplacementOptionsBlock(entry.options.sortedByReplacementWeight())
            DetailBlock(label = "Додатак", value = entry.addendum)
            OutlinedButton(
                onClick = { onEditEntry(entry) },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Измени")
            }
            if (onSuggestEntryChange != null) {
                OutlinedButton(
                    onClick = {
                        showProposalBox = !showProposalBox
                        proposalValidationMessage = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Предложи измену")
                }
            }
            if (showProposalBox && onSuggestEntryChange != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = proposalText,
                            onValueChange = {
                                proposalText = it
                                proposalValidationMessage = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                            label = { Text("Текст предлога измене") }
                        )
                        if (proposalValidationMessage.isNotBlank()) {
                            Text(
                                text = proposalValidationMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { sendProposal() },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Пошаљи")
                            }
                            OutlinedButton(
                                onClick = {
                                    proposalText = ""
                                    proposalValidationMessage = ""
                                    showProposalBox = false
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Одустани")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplacementOptionsBlock(options: List<ReplacementOption>) {
    if (options.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Српскословенске речи",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surfaceContainerLowest
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { option ->
                    Text(
                        text = replacementOptionLine(option),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailBlock(
    label: String,
    value: String,
    italic: Boolean = false
) {
    if (value.isBlank()) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surfaceContainerLowest
        ) {
            Text(
                text = formattedAnnotatedString(value),
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontStyle = if (italic) FontStyle.Italic else null
                )
            )
        }
    }
}

private fun replacementOptionLine(option: ReplacementOption) =
    buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(textWithInitialCapital(option.replacementWord))
        }
        if (option.explanation.isNotBlank()) {
            append(" - ")
            append(formattedAnnotatedString(option.explanation))
        }
    }

@Composable
private fun ReplacementMatchSurface(match: ReplacementSearchMatch) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface
    ) {
        Text(
            text = replacementMatchLine(match),
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun replacementMatchLine(match: ReplacementSearchMatch) =
    buildAnnotatedString {
        withStyle(
            SpanStyle(
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline
            )
        ) {
            append(textWithInitialCapital(match.entry.foreignWord))
        }
        if (match.entry.origin.isNotBlank()) {
            append(" (")
            append(match.entry.origin)
            append(")")
        }
        append(" - ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(match.option.replacementWord.trim())
        }
        if (match.option.explanation.isNotBlank()) {
            append(" (")
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(formattedAnnotatedString(match.option.explanation))
            }
            append(")")
        }
    }
