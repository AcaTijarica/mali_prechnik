package rs.maliprecnik.app

/** Korisnicka podesavanja koja menjaju samo prikaz tudjica, ne i podatke u skladistu. */
data class DisplaySettings(
    val showOrigin: Boolean = true,
    val showExplanations: Boolean = true,
    val showAddendum: Boolean = true,
    val fontChoice: AppFontChoice = AppFontChoice.Monomakh,
    val fontSizeChoice: AppFontSizeChoice = AppFontSizeChoice.Medium
)

/** Писма која корисник може да изабере за приказ целог приложенија. */
enum class AppFontChoice(
    val storageKey: String,
    val label: String
) {
    Monomakh("monomakh", "Мономах"),
    EBGaramond("eb_garamond", "ЕБ Гарамонд"),
    NotoSerif("noto_serif", "Ното Сериф"),
    System("system", "Склоповски");

    companion object {
        fun fromStorageKey(value: String?): AppFontChoice =
            values().firstOrNull { it.storageKey == value } ?: Monomakh
    }
}

/** Општа величина слова у приложенију. */
enum class AppFontSizeChoice(
    val storageKey: String,
    val label: String,
    val scale: Float
) {
    Smallest("smallest", "Најмања", 0.86f),
    Small("small", "Мала", 0.94f),
    Medium("medium", "Средња", 1.0f),
    Large("large", "Већа", 1.1f),
    Largest("largest", "Највећа", 1.2f);

    companion object {
        fun fromStorageKey(value: String?): AppFontSizeChoice =
            values().firstOrNull { it.storageKey == value } ?: Medium
    }
}
