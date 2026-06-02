package rs.maliprecnik.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun VotingScreen(
    modifier: Modifier = Modifier,
    entries: List<DictionaryEntry>,
    repository: FirestoreProposalRepository
) {
    var proposals by remember { mutableStateOf(emptyList<VotingProposalGroup>()) }
    var message by rememberSaveable { mutableStateOf("Учитавање предлога за гласање...") }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var expandedLetter by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var authorIdPendingBlock by rememberSaveable { mutableStateOf<String?>(null) }
    var groupIdPendingReport by rememberSaveable { mutableStateOf<String?>(null) }
    var optionIdPendingReport by rememberSaveable { mutableStateOf<String?>(null) }
    var authorIdPendingReport by rememberSaveable { mutableStateOf<String?>(null) }

    fun loadProposals() {
        isLoading = true
        repository.loadVotingProposals(
            onSuccess = { loadedProposals ->
                proposals = loadedProposals
                isLoading = false
                selectedGroupId = null
                message = if (loadedProposals.isEmpty()) {
                    "Тренутно нема предлога за гласање."
                } else {
                    "Предлози су учитани. Број туђица на гласању: ${loadedProposals.size}."
                }
            },
            onFailure = { error ->
                isLoading = false
                message = proposalErrorMessage(error)
            }
        )
    }

    authorIdPendingBlock?.let { authorId ->
        AlertDialog(
            onDismissRequest = { authorIdPendingBlock = null },
            title = { Text("Блокирање предлагача") },
            text = {
                Text(
                    "Да ли сте сигурни да желите да сакријете све предлоге овог предлагача? " +
                        "Ово важи само на овом уређају."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.blockAuthor(authorId)
                        authorIdPendingBlock = null
                        message = "Предлози овог предлагача су сакривени на овом уређају."
                        loadProposals()
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Да")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { authorIdPendingBlock = null },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Не")
                }
            }
        )
    }

    val reportGroupId = groupIdPendingReport
    val reportOptionId = optionIdPendingReport
    val reportAuthorId = authorIdPendingReport
    if (reportGroupId != null && reportOptionId != null && reportAuthorId != null) {
        AlertDialog(
            onDismissRequest = {
                groupIdPendingReport = null
                optionIdPendingReport = null
                authorIdPendingReport = null
            },
            title = { Text("Пријава предлога и предлагача") },
            text = {
                Text(
                    "Да ли желите да пријавите овај предлог и његовог предлагача уреднику као неумесне или неприхватљиве?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.reportOption(
                            groupId = reportGroupId,
                            optionId = reportOptionId,
                            reportedAuthorId = reportAuthorId,
                            onSuccess = {
                                groupIdPendingReport = null
                                optionIdPendingReport = null
                                authorIdPendingReport = null
                                message = "Предлог и предлагач су пријављени уреднику."
                            },
                            onFailure = { error ->
                                groupIdPendingReport = null
                                optionIdPendingReport = null
                                authorIdPendingReport = null
                                message = proposalErrorMessage(error)
                            }
                        )
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Да")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        groupIdPendingReport = null
                        optionIdPendingReport = null
                        authorIdPendingReport = null
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Не")
                }
            }
        )
    }

    LaunchedEffect(repository) {
        loadProposals()
    }

    val entriesByForeignWord = remember(entries) {
        entries.associateBy { normalizeWord(it.foreignWord) }
    }
    val groupedProposals = remember(proposals) {
        proposals
            .groupBy { it.firstLetter }
            .toSortedMap { first, second -> serbianCollator.compare(first, second) }
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            StatusText(message)
        }
        item {
            Button(
                onClick = { loadProposals() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isLoading) "Учитавање..." else "Освежи предлоге")
            }
        }

        groupedProposals.forEach { (letter, letterProposals) ->
            item(key = "vote-letter-$letter") {
                VotingLetterHeaderRow(
                    letter = letter,
                    count = letterProposals.size,
                    expanded = expandedLetter == letter,
                    onClick = {
                        expandedLetter = if (expandedLetter == letter) null else letter
                        selectedGroupId = null
                    }
                )
            }
            if (expandedLetter == letter) {
                items(letterProposals, key = { it.id }) { group ->
                    VotingProposalRow(
                        group = group,
                        selected = selectedGroupId == group.id,
                        onClick = {
                            selectedGroupId = if (selectedGroupId == group.id) null else group.id
                        }
                    )
                    if (selectedGroupId == group.id) {
                        VotingProposalDetails(
                            group = group,
                            currentEntry = entriesByForeignWord[normalizeWord(group.foreignWord)],
                            onBlockAuthor = { authorIdPendingBlock = it },
                            onReportOption = { optionId, authorId ->
                                groupIdPendingReport = group.id
                                optionIdPendingReport = optionId
                                authorIdPendingReport = authorId
                            },
                            onVote = { optionId ->
                                repository.voteForOption(
                                    groupId = group.id,
                                    optionId = optionId,
                                    onSuccess = {
                                        message = "Глас је забележен."
                                        loadProposals()
                                    },
                                    onFailure = { error ->
                                        message = proposalErrorMessage(error)
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun VotingLetterHeaderRow(
    letter: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = letter,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${if (expanded) "▾" else "▸"} $count",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VotingProposalRow(
    group: VotingProposalGroup,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Text(
            text = group.foreignWord,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun VotingProposalDetails(
    group: VotingProposalGroup,
    currentEntry: DictionaryEntry?,
    onBlockAuthor: (String) -> Unit,
    onReportOption: (String, String) -> Unit,
    onVote: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = group.foreignWord,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            CurrentStateBlock(currentEntry)
            Text(
                text = "Предлози",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            group.proposals.forEach { option ->
                VotingOptionRow(
                    option = option,
                    onVote = { onVote(option.id) },
                    onBlockAuthor = { onBlockAuthor(option.createdBy) },
                    onReportOption = { onReportOption(option.id, option.createdBy) }
                )
            }
        }
    }
}

@Composable
private fun CurrentStateBlock(entry: DictionaryEntry?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Садашње стање",
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
                if (entry == null) {
                    Text(
                        text = "Ова туђица још није у личном складишту апликације.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    DetailLine(label = "Порекло", value = entry.origin)
                    VotingReplacementOptionsBlock(entry.options.sortedByReplacementWeight())
                    DetailLine(label = "Додатак", value = entry.addendum)
                }
            }
        }
    }
}

@Composable
private fun VotingReplacementOptionsBlock(options: List<ReplacementOption>) {
    if (options.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Српскословенске речи",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        options.forEach { option ->
            Text(
                text = votingReplacementOptionLine(option),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun votingReplacementOptionLine(option: ReplacementOption) =
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
private fun VotingOptionRow(
    option: VotingProposalOption,
    onVote: () -> Unit,
    onBlockAuthor: () -> Unit,
    onReportOption: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = formattedAnnotatedString(option.proposalText),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Гласова: ${option.votesCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onVote,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Гласај")
            }
            if (option.createdBy.isNotBlank()) {
                OutlinedButton(
                    onClick = onReportOption,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Пријави предлог и предлагача")
                }
                OutlinedButton(
                    onClick = onBlockAuthor,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Блокирај предлагача")
                }
            }
        }
    }
}
