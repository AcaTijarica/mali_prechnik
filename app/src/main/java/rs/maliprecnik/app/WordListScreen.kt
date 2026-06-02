package rs.maliprecnik.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Листа свих туђица, груписана по почетном слову. */
@Composable
fun WordListScreen(
    modifier: Modifier = Modifier,
    entries: List<DictionaryEntry>,
    statusMessage: String,
    onEntryClick: (DictionaryEntry) -> Unit
) {
    var expandedLetter by rememberSaveable { mutableStateOf<String?>(null) }
    // `remember(entries)` поново групише листу само када се уноси стварно промене.
    val groupedEntries = remember(entries) {
        entries
            .sortedByForeignWord()
            .groupBy { initialLetter(it.foreignWord) }
            .toSortedMap { first, second -> serbianCollator.compare(first, second) }
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            StatusText("$statusMessage У листи је ${entries.size} уноса.")
        }
        if (groupedEntries.isEmpty()) {
            item {
                Text(
                    text = "Нема унетих туђица.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        groupedEntries.forEach { (letter, letterEntries) ->
            item(key = "letter-$letter") {
                LetterHeaderRow(
                    letter = letter,
                    count = letterEntries.size,
                    expanded = expandedLetter == letter,
                    onClick = {
                        expandedLetter = if (expandedLetter == letter) null else letter
                    }
                )
            }
            if (expandedLetter == letter) {
                items(letterEntries, key = { it.id }) { entry ->
                    ForeignWordRow(
                        entry = entry,
                        onClick = { onEntryClick(entry) }
                    )
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LetterHeaderRow(
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
private fun ForeignWordRow(
    entry: DictionaryEntry,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface
    ) {
        Text(
            text = entry.foreignWord,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
