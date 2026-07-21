package rs.maliprecnik.app

import org.json.JSONArray
import org.json.JSONObject

private const val JSON_FORMAT = "mali-precnik"
private const val JSON_VERSION = 7

/**
 * Извози локалну базу у JSON који чува релациону структуру:
 * једна туђица, па више засебних српскословенских предлога.
 */
fun storageToJson(storage: DictionaryStorage): String {
    val entriesArray = JSONArray()
    storage.entries.forEach { entry ->
        val optionsArray = JSONArray()
        entry.options.forEach { option ->
            optionsArray.put(
                JSONObject()
                    .put("srpskoslovenski", option.replacementWord)
                    .put("pojasnjenje", option.explanation)
                    .put("tezina", option.weight.coerceAtLeast(1))
            )
        }

        entriesArray.put(
            JSONObject()
                .put("id", entry.id)
                .put("tudjica", entry.foreignWord)
                .put("poreklo", entry.origin)
                .put("dodatak", entry.addendum)
                .put("predlozi", optionsArray)
        )
    }

    val oldWordsArray = JSONArray()
    storage.oldWords.forEach { oldWord ->
        val synonymsArray = JSONArray()
        oldWord.synonyms.forEach { synonym -> synonymsArray.put(synonym) }
        oldWordsArray.put(
            JSONObject()
                .put("id", oldWord.id)
                .put("stara_rec", oldWord.oldWord)
                .put("dodatak", oldWord.addendum)
                .put("slicnoznacnice", synonymsArray)
        )
    }

    return JSONObject()
        .put("format", JSON_FORMAT)
        .put("version", JSON_VERSION)
        .put("storage", "sqlite-relational")
        .put("entries", entriesArray)
        .put("stare_reci", oldWordsArray)
        .toString(2)
}

/** Задржано за позиве којима треба извоз само постојећих туђица. */
fun entriesToJson(entries: List<DictionaryEntry>): String =
    storageToJson(DictionaryStorage(entries = entries))

/**
 * Чита JSON из увоза.
 *
 * Нови формат користи поље `predlozi`. Ради лакшег преласка, читамо и стари
 * кључ `resenja`, као и раније пробне JSON облике.
 */
fun storageFromJson(json: String): DictionaryStorage {
    val trimmedJson = json.trim()
    val root = if (trimmedJson.startsWith("[")) {
        null
    } else {
        JSONObject(trimmedJson)
    }
    val entriesArray = if (root == null) {
        JSONArray(trimmedJson)
    } else {
        root.optJSONArray("entries")
            ?: root.optJSONArray("tudjice")
            ?: JSONArray()
    }

    val entries = buildList {
        for (index in 0 until entriesArray.length()) {
            val item = entriesArray.getJSONObject(index)
            val foreignWord = item.optString("tudjica").trim()
            if (foreignWord.isBlank()) continue

            val options = optionsFromJson(item)

            add(
                DictionaryEntry(
                    id = item.optLong("id", 0),
                    foreignWord = foreignWord,
                    origin = item.optString("poreklo", item.optString("origin")).trim(),
                    addendum = item.optString("dodatak", item.optString("addendum")).trim(),
                    options = options
                )
            )
        }
    }

    val oldWordsArray = root?.optJSONArray("stare_reci") ?: JSONArray()
    val oldWords = buildList {
        for (index in 0 until oldWordsArray.length()) {
            val item = oldWordsArray.getJSONObject(index)
            val oldWord = item.optString("stara_rec", item.optString("old_word")).trim()
            if (oldWord.isBlank()) continue

            val synonyms = synonymsFromJson(item)
            if (synonyms.isEmpty()) continue

            add(
                OldWordEntry(
                    id = item.optLong("id", 0),
                    oldWord = oldWord,
                    addendum = item.optString("dodatak", item.optString("addendum")).trim(),
                    synonyms = synonyms
                )
            )
        }
    }

    return DictionaryStorage(entries = entries, oldWords = oldWords)
}

fun entriesFromJson(json: String): List<DictionaryEntry> =
    storageFromJson(json).entries

private fun optionsFromJson(item: JSONObject): List<ReplacementOption> {
    val explicitOptions = item.optJSONArray("predlozi")
        ?: item.optJSONArray("resenja")
        ?: item.optJSONArray("options")
    if (explicitOptions != null) {
        return buildList {
            for (index in 0 until explicitOptions.length()) {
                val option = explicitOptions.getJSONObject(index)
                val replacementWord = option.optString("srpskoslovenski").trim()
                if (replacementWord.isBlank()) continue

                add(
                    ReplacementOption(
                        replacementWord = replacementWord,
                        explanation = option.optString("pojasnjenje").trim(),
                        weight = optionWeightFromJson(option)
                    )
                )
            }
        }
    }

    val replacementWord = item.optString("srpskoslovenski").trim()
    if (replacementWord.isBlank()) return emptyList()

    return listOf(
        ReplacementOption(
            replacementWord = replacementWord,
            explanation = item.optString("pojasnjenje").trim(),
            weight = item.optInt("tezina", item.optInt("weight", 1)).coerceAtLeast(1)
        )
    )
}

private fun optionWeightFromJson(option: JSONObject): Int =
    option.optInt("tezina", option.optInt("weight", 1)).coerceAtLeast(1)

private fun synonymsFromJson(item: JSONObject): List<String> {
    val explicitSynonyms = item.optJSONArray("slicnoznacnice")
        ?: item.optJSONArray("synonyms")
    val values = if (explicitSynonyms != null) {
        buildList {
            for (index in 0 until explicitSynonyms.length()) {
                add(explicitSynonyms.optString(index))
            }
        }
    } else {
        item.optString("slicnoznacnice", item.optString("synonyms"))
            .split(',', ';')
    }

    val seen = mutableSetOf<String>()
    return values.map { it.trim() }.filter { value ->
        val key = normalizeWord(value)
        key.isNotBlank() && seen.add(key)
    }
}
