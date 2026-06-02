package rs.maliprecnik.app

import org.json.JSONArray
import org.json.JSONObject

private const val JSON_FORMAT = "mali-precnik"
private const val JSON_VERSION = 6

/**
 * Извози локалну базу у JSON који чува релациону структуру:
 * једна туђица, па више засебних српскословенских предлога.
 */
fun entriesToJson(entries: List<DictionaryEntry>): String {
    val entriesArray = JSONArray()
    entries.forEach { entry ->
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

    return JSONObject()
        .put("format", JSON_FORMAT)
        .put("version", JSON_VERSION)
        .put("storage", "sqlite-relational")
        .put("entries", entriesArray)
        .toString(2)
}

/**
 * Чита JSON из увоза.
 *
 * Нови формат користи поље `predlozi`. Ради лакшег преласка, читамо и стари
 * кључ `resenja`, као и раније пробне JSON облике.
 */
fun entriesFromJson(json: String): List<DictionaryEntry> {
    val trimmedJson = json.trim()
    val array = if (trimmedJson.startsWith("[")) {
        JSONArray(trimmedJson)
    } else {
        JSONObject(trimmedJson).getJSONArray("entries")
    }

    return buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val foreignWord = item.optString("tudjica").trim()
            if (foreignWord.isBlank()) continue

            val options = optionsFromJson(item)
            if (options.isEmpty()) continue

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
}

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
