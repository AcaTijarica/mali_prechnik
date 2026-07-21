package rs.maliprecnik.app

/**
 * Једна туђица у Малом пречнику.
 *
 * Туђица је одвојена од својих српскословенских предлога зато што једна туђица
 * може имати више добрих замена, а свака замена може имати своје појашњење.
 */
data class DictionaryEntry(
    val id: Long = 0,
    val foreignWord: String,
    val origin: String = "",
    val addendum: String = "",
    val options: List<ReplacementOption>
)

/**
 * Један српскословенски предлог за једну туђицу.
 *
 * `foreignEntryId` показује којој туђици предлог припада. Код нових уноса у UI-ју
 * ова вредност може бити `0`; SQLite је попуњава стварним `id`-јем при упису.
 */
data class ReplacementOption(
    val foreignEntryId: Long = 0,
    val replacementWord: String = "",
    val explanation: String = "",
    val weight: Int = 1
)

/**
 * Стара српска или словенска реч са данашњим сличнозначницама.
 *
 * За стару реч не чувамо порекло ни засебна појашњења сличнозначница. `addendum`
 * је необавезна заједничка напомена, а `synonyms` је списак савременијих речи
 * преко којих корисник може да је пронађе.
 */
data class OldWordEntry(
    val id: Long = 0,
    val oldWord: String,
    val addendum: String = "",
    val synonyms: List<String>
)

/** Целокупан преносив садржај личног или јавног складишта. */
data class DictionaryStorage(
    val entries: List<DictionaryEntry> = emptyList(),
    val oldWords: List<OldWordEntry> = emptyList()
)
