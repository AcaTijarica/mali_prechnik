package rs.maliprecnik.app

/** Сви главни екрани које ручно пребацујемо у `PrechnikScreen`. */
enum class AppScreen(val title: String) {
    Search("Претрага"),
    Voting("Гласање"),
    AddWord("Додај реч"),
    EditWord("Измена речи"),
    ForeignWord("Туђица"),
    ForeignWords("Туђице"),
    Storage("Складиште"),
    Notifications("Обавештења"),
    Idea("Замисао"),
    PrivacyRules("Правила и приватност")
}
