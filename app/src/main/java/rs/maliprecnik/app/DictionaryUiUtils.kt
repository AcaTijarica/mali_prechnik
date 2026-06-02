package rs.maliprecnik.app

import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReplacementSearchMatch(
    val entry: DictionaryEntry,
    val option: ReplacementOption
)

data class SearchSuggestion(
    val value: String,
    val detail: String
)

enum class SearchDirection(
    val label: String,
    val inputLabel: String,
    val emptyPrompt: String
) {
    ForeignToReplacement(
        label = "туђица -> српслв",
        inputLabel = "Унеси туђицу",
        emptyPrompt = "Унеси туђицу да би се приказале српскословенске речи."
    ),
    ReplacementToForeign(
        label = "српслв -> туђица",
        inputLabel = "Унеси српскословенску реч",
        emptyPrompt = "Унеси српскословенску реч да би се приказале туђице у којима се јавља."
    )
}

data class DictionarySearchResult(
    val foreignEntry: DictionaryEntry?,
    val replacementMatches: List<ReplacementSearchMatch>
) {
    val isEmpty: Boolean
        get() = foreignEntry == null && replacementMatches.isEmpty()
}

/**
 * Двосмерна претрага:
 * - ако је упит туђица, враћа целу туђицу са свим предлозима;
 * - ако је упит српскословенска реч, враћа све туђице у којима се та реч јавља.
 */
fun searchDictionary(
    query: String,
    entries: List<DictionaryEntry>,
    direction: SearchDirection
): DictionarySearchResult {
    val normalizedQuery = normalizeWord(query)
    if (normalizedQuery.isBlank()) {
        return DictionarySearchResult(foreignEntry = null, replacementMatches = emptyList())
    }

    val foreignEntry = if (direction == SearchDirection.ForeignToReplacement) {
        entries.firstOrNull { entry ->
            normalizeWord(entry.foreignWord) == normalizedQuery
        }
    } else {
        null
    }

    val replacementMatches = if (direction == SearchDirection.ReplacementToForeign) {
        entries.flatMap { entry ->
            entry.options.filter { option ->
                normalizeWord(option.replacementWord) == normalizedQuery
            }.map { option ->
                ReplacementSearchMatch(entry = entry, option = option)
            }
        }.sortedWith { first, second ->
            serbianCollator.compare(first.entry.foreignWord, second.entry.foreignWord).ifZero {
                serbianCollator.compare(first.option.replacementWord, second.option.replacementWord)
            }
        }
    } else {
        emptyList()
    }

    return DictionarySearchResult(
        foreignEntry = foreignEntry,
        replacementMatches = replacementMatches
    )
}

fun searchSuggestions(
    query: String,
    entries: List<DictionaryEntry>,
    direction: SearchDirection,
    limit: Int = 8
): List<SearchSuggestion> {
    val normalizedQuery = normalizeWord(query)
    if (normalizedQuery.isBlank()) return emptyList()

    return when (direction) {
        SearchDirection.ForeignToReplacement -> entries
            .asSequence()
            .filter { entry -> normalizeWord(entry.foreignWord).startsWith(normalizedQuery) }
            .sortedWith { first, second -> serbianCollator.compare(first.foreignWord, second.foreignWord) }
            .map { entry ->
                SearchSuggestion(
                    value = entry.foreignWord,
                    detail = ""
                )
            }
            .take(limit)
            .toList()

        SearchDirection.ReplacementToForeign -> entries
            .asSequence()
            .flatMap { entry ->
                entry.options.asSequence().map { option -> option to entry }
            }
            .filter { (option, _) -> normalizeWord(option.replacementWord).startsWith(normalizedQuery) }
            .distinctBy { (option, _) -> normalizeWord(option.replacementWord) }
            .sortedWith { first, second ->
                serbianCollator.compare(first.first.replacementWord, second.first.replacementWord)
            }
            .map { (option, entry) ->
                SearchSuggestion(
                    value = option.replacementWord,
                    detail = "нпр. ${entry.foreignWord}"
                )
            }
            .take(limit)
            .toList()
    }
}

fun List<DictionaryEntry>.hasForeignWord(
    foreignWord: String,
    exceptId: Long? = null
): Boolean {
    val normalizedWord = normalizeWord(foreignWord)
    if (normalizedWord.isBlank()) return false

    return any { entry ->
        entry.id != exceptId && normalizeWord(entry.foreignWord) == normalizedWord
    }
}

private val serbianLocale: Locale = Locale.forLanguageTag("sr-Cyrl-RS")

val serbianCollator: Collator = Collator.getInstance(serbianLocale).apply {
    strength = Collator.PRIMARY
}

fun normalizeWord(value: String): String =
    value.trim().lowercase(serbianLocale)

fun initialLetter(value: String): String =
    value.trim().firstOrNull()?.uppercase(serbianLocale) ?: "#"

fun textWithInitialCapital(value: String): String {
    val trimmedValue = value.trim()
    if (trimmedValue.isBlank()) return trimmedValue

    return trimmedValue.replaceFirstChar { character ->
        if (character.isLowerCase()) character.titlecase(serbianLocale) else character.toString()
    }
}

fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"

    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }

    return if (unitIndex == 0) {
        "$bytes ${units[unitIndex]}"
    } else {
        String.format(serbianLocale, "%.1f %s", value, units[unitIndex])
    }
}

fun formatTimestamp(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return "непознато"
    return SimpleDateFormat("dd.MM.yyyy. HH:mm", serbianLocale).format(Date(timestampMillis))
}

fun List<DictionaryEntry>.sortedByForeignWord(): List<DictionaryEntry> =
    sortedWith { first, second ->
        serbianCollator.compare(first.foreignWord, second.foreignWord)
    }

fun List<DictionaryEntry>.withOnlyNewForeignWordsFrom(
    publicEntries: List<DictionaryEntry>
): Pair<List<DictionaryEntry>, Int> {
    val existingWords = map { entry -> normalizeWord(entry.foreignWord) }.toMutableSet()
    val newEntries = publicEntries.filter { entry ->
        val key = normalizeWord(entry.foreignWord)
        key.isNotBlank() && existingWords.add(key)
    }
    return (this + newEntries).sortedByForeignWord() to newEntries.size
}

fun List<ReplacementOption>.sortedByReplacementWeight(): List<ReplacementOption> =
    sortedWith { first, second ->
        first.weight.compareTo(second.weight).ifZero {
            serbianCollator.compare(first.replacementWord, second.replacementWord)
        }
    }

private inline fun Int.ifZero(fallback: () -> Int): Int =
    if (this == 0) fallback() else this
