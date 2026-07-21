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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Азбучни списак старих српских и словенских речи. */
@Composable
fun OldWordListScreen(
    modifier: Modifier = Modifier,
    oldWords: List<OldWordEntry>,
    statusMessage: String,
    onOldWordClick: (OldWordEntry) -> Unit
) {
    var expandedLetter by rememberSaveable { mutableStateOf<String?>(null) }
    val groupedOldWords = remember(oldWords) {
        oldWords
            .sortedByOldWord()
            .groupBy { initialLetter(it.oldWord) }
            .toSortedMap { first, second -> serbianCollator.compare(first, second) }
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            StatusText("$statusMessage У списку је ${oldWords.size} старих речи.")
        }
        if (groupedOldWords.isEmpty()) {
            item {
                Text(
                    text = "Нема унетих старих речи.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        groupedOldWords.forEach { (letter, wordsForLetter) ->
            item(key = "old-letter-$letter") {
                OldWordLetterHeader(
                    letter = letter,
                    count = wordsForLetter.size,
                    expanded = expandedLetter == letter,
                    onClick = {
                        expandedLetter = if (expandedLetter == letter) null else letter
                    }
                )
            }
            if (expandedLetter == letter) {
                items(wordsForLetter, key = { it.id }) { oldWord ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp)
                            .clickable { onOldWordClick(oldWord) },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            text = oldWord.oldWord,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge
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

/** Читљив приказ једне старе речи који се отвара из азбучног списка. */
@Composable
fun OldWordDetailScreen(
    modifier: Modifier = Modifier,
    oldWord: OldWordEntry,
    statusMessage: String,
    onEditOldWord: (OldWordEntry) -> Unit
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
                value = oldWord.oldWord,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                singleLine = true,
                label = { Text("Стара реч") }
            )
        }
        item {
            OldWordDetailSurface(
                oldWord = oldWord,
                onEditOldWord = onEditOldWord
            )
        }
    }
}

/** Заједнички приказ који користе и претрага и страница једне старе речи. */
@Composable
fun OldWordDetailSurface(
    oldWord: OldWordEntry,
    onEditOldWord: ((OldWordEntry) -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = oldWord.oldWord,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (oldWord.addendum.isNotBlank()) {
                OldWordValueBlock(
                    label = "Додатак",
                    value = oldWord.addendum,
                    italic = true
                )
            }
            OldWordValueBlock(
                label = "Сличнозначнице",
                value = oldWord.synonyms.joinToString(", ")
            )
            if (onEditOldWord != null) {
                OutlinedButton(
                    onClick = { onEditOldWord(oldWord) },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Измени")
                }
            }
        }
    }
}

@Composable
private fun OldWordValueBlock(
    label: String,
    value: String,
    italic: Boolean = false
) {
    if (value.isBlank()) return

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = formattedAnnotatedString(value),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontStyle = if (italic) FontStyle.Italic else null
            )
        )
    }
}

@Composable
private fun OldWordLetterHeader(
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
