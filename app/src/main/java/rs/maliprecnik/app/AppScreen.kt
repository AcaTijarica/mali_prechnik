package rs.maliprecnik.app

/** Сви главни екрани које ручно пребацујемо у `PrechnikScreen`. */
enum class AppScreen(val title: String) {
    Search("Претрага"),
    AddWord("Додај туђицу"),
    AddOldWord("Додај стару реч"),
    Voting("Гласање"),
    EditWord("Измена туђице"),
    EditOldWord("Измена старе речи"),
    ForeignWord("Туђица"),
    ForeignWords("Туђице"),
    OldWord("Стара реч"),
    OldWords("Старе речи"),
    Storage("Складиште"),
    Settings("Подешавања"),
    Acknowledgements("Захвалница"),
    Notifications("Обавештења"),
    Idea("Замисао"),
    PrivacyRules("Правила и приватност")
}
