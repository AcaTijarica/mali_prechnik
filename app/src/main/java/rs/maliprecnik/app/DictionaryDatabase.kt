package rs.maliprecnik.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

private const val DATABASE_NAME = "prechnik.db"
private const val DATABASE_VERSION = 8
private const val SEED_DATABASE_ASSET = "prechnik_seed.db"

private const val TABLE_FOREIGN_TERMS = "foreign_terms"
private const val TABLE_REPLACEMENT_OPTIONS = "replacement_options"
private const val TABLE_OLD_WORDS = "old_words"
private const val TABLE_OLD_WORD_SYNONYMS = "old_word_synonyms"

private const val COLUMN_ID = "id"
private const val COLUMN_WORD = "word"
private const val COLUMN_NORMALIZED_WORD = "normalized_word"
private const val COLUMN_ORIGIN = "origin"
private const val COLUMN_FOREIGN_TERM_ID = "foreign_term_id"
private const val COLUMN_REPLACEMENT_WORD = "replacement_word"
private const val COLUMN_NORMALIZED_REPLACEMENT_WORD = "normalized_replacement_word"
private const val COLUMN_EXPLANATION = "explanation"
private const val COLUMN_WEIGHT = "weight"
private const val COLUMN_ADDENDUM = "addendum"
private const val COLUMN_OLD_WORD_ID = "old_word_id"
private const val COLUMN_SYNONYM = "synonym"
private const val COLUMN_NORMALIZED_SYNONYM = "normalized_synonym"
private const val COLUMN_POSITION = "position"
private const val COLUMN_UPDATED_AT = "updated_at"

/**
 * SQLite слој за службену локалну базу.
 *
 * База је намерно релациона: један ред у `foreign_terms` представља једну
 * туђицу, а више редова у `replacement_options` представљају њене засебне
 * српскословенске замене. Тако свака замена може имати своје појашњење, док
 * порекло и додатак стоје једном на нивоу саме туђице.
 */
class DictionaryDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        /**
         * На чистој инсталацији копира службену seed базу из `assets`.
         *
         * Ако корисник већ има радну базу, не преписујемо је аутоматски. За ручно
         * враћање на службену базу постоји дугме на екрану `Складиште`.
         */
        fun installSeedDatabaseIfMissing(context: Context) {
            val databaseFile = context.getDatabasePath(DATABASE_NAME)
            if (databaseFile.exists()) return

            databaseFile.parentFile?.mkdirs()
            runCatching {
                context.assets.open(SEED_DATABASE_ASSET).use { input ->
                    FileOutputStream(databaseFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }.onFailure {
                databaseFile.delete()
            }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createCurrentTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 7) {
            addWeightColumnIfMissing(db)
        }
        if (oldVersion < 8) {
            createOldWordTables(db)
        }
    }

    private fun createCurrentTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_FOREIGN_TERMS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_WORD TEXT NOT NULL,
                $COLUMN_NORMALIZED_WORD TEXT NOT NULL UNIQUE,
                $COLUMN_ORIGIN TEXT NOT NULL DEFAULT '',
                $COLUMN_ADDENDUM TEXT NOT NULL DEFAULT '',
                $COLUMN_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_REPLACEMENT_OPTIONS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_FOREIGN_TERM_ID INTEGER NOT NULL,
                $COLUMN_REPLACEMENT_WORD TEXT NOT NULL,
                $COLUMN_NORMALIZED_REPLACEMENT_WORD TEXT NOT NULL,
                $COLUMN_EXPLANATION TEXT NOT NULL DEFAULT '',
                $COLUMN_WEIGHT INTEGER NOT NULL DEFAULT 1,
                $COLUMN_UPDATED_AT INTEGER NOT NULL,
                FOREIGN KEY($COLUMN_FOREIGN_TERM_ID)
                    REFERENCES $TABLE_FOREIGN_TERMS($COLUMN_ID)
                    ON DELETE CASCADE,
                UNIQUE($COLUMN_FOREIGN_TERM_ID, $COLUMN_NORMALIZED_REPLACEMENT_WORD)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX index_foreign_terms_word ON $TABLE_FOREIGN_TERMS($COLUMN_WORD)")
        db.execSQL(
            "CREATE INDEX index_replacement_options_word " +
                "ON $TABLE_REPLACEMENT_OPTIONS($COLUMN_REPLACEMENT_WORD)"
        )
        db.execSQL(
            "CREATE INDEX index_replacement_options_normalized_word " +
                "ON $TABLE_REPLACEMENT_OPTIONS($COLUMN_NORMALIZED_REPLACEMENT_WORD)"
        )
        createOldWordTables(db)
    }

    private fun createOldWordTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_OLD_WORDS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_WORD TEXT NOT NULL,
                $COLUMN_NORMALIZED_WORD TEXT NOT NULL UNIQUE,
                $COLUMN_ADDENDUM TEXT NOT NULL DEFAULT '',
                $COLUMN_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_OLD_WORD_SYNONYMS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_OLD_WORD_ID INTEGER NOT NULL,
                $COLUMN_SYNONYM TEXT NOT NULL,
                $COLUMN_NORMALIZED_SYNONYM TEXT NOT NULL,
                $COLUMN_POSITION INTEGER NOT NULL DEFAULT 0,
                $COLUMN_UPDATED_AT INTEGER NOT NULL,
                FOREIGN KEY($COLUMN_OLD_WORD_ID)
                    REFERENCES $TABLE_OLD_WORDS($COLUMN_ID)
                    ON DELETE CASCADE,
                UNIQUE($COLUMN_OLD_WORD_ID, $COLUMN_NORMALIZED_SYNONYM)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_old_words_word " +
                "ON $TABLE_OLD_WORDS($COLUMN_WORD)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_old_word_synonyms_normalized " +
                "ON $TABLE_OLD_WORD_SYNONYMS($COLUMN_NORMALIZED_SYNONYM)"
        )
    }

    private fun addWeightColumnIfMissing(db: SQLiteDatabase) {
        if (!columnExists(db, TABLE_REPLACEMENT_OPTIONS, COLUMN_WEIGHT)) {
            db.execSQL(
                "ALTER TABLE $TABLE_REPLACEMENT_OPTIONS " +
                    "ADD COLUMN $COLUMN_WEIGHT INTEGER NOT NULL DEFAULT 1"
            )
        }
    }

    private fun columnExists(db: SQLiteDatabase, tableName: String, columnName: String): Boolean {
        db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == columnName) return true
            }
        }
        return false
    }

    fun ensureInitialData(defaultStorage: DictionaryStorage) {
        if (countEntries() > 0 || countOldWords() > 0) return
        if (defaultStorage.entries.isNotEmpty() || defaultStorage.oldWords.isNotEmpty()) {
            replaceAllStorage(defaultStorage)
        }
    }

    fun countEntries(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_FOREIGN_TERMS", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun countOldWords(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_OLD_WORDS", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getAllEntries(): List<DictionaryEntry> =
        readEntriesFromDatabase(readableDatabase).deduplicatedByForeignWord()

    fun getAllOldWords(): List<OldWordEntry> =
        readOldWordsFromDatabase(readableDatabase).deduplicatedByOldWord()

    fun getStorage(): DictionaryStorage =
        DictionaryStorage(entries = getAllEntries(), oldWords = getAllOldWords())

    fun insertEntry(entry: DictionaryEntry): Long {
        require(findDuplicateForeignWord(entry.foreignWord) == null) {
            "Туђица већ постоји у бази."
        }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val entryId = insertEntryUnchecked(db, entry.cleanedForStorage())
            db.setTransactionSuccessful()
            entryId
        } finally {
            db.endTransaction()
        }
    }

    fun updateEntry(entry: DictionaryEntry) {
        val cleanedEntry = entry.cleanedForStorage()
        require(findDuplicateForeignWord(cleanedEntry.foreignWord, cleanedEntry.id) == null) {
            "Туђица већ постоји у бази."
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                TABLE_FOREIGN_TERMS,
                cleanedEntry.toForeignTermContentValues(),
                "$COLUMN_ID = ?",
                arrayOf(cleanedEntry.id.toString())
            )
            db.delete(
                TABLE_REPLACEMENT_OPTIONS,
                "$COLUMN_FOREIGN_TERM_ID = ?",
                arrayOf(cleanedEntry.id.toString())
            )
            cleanedEntry.options.forEach { option ->
                db.insertOrThrow(
                    TABLE_REPLACEMENT_OPTIONS,
                    null,
                    option.copy(foreignEntryId = cleanedEntry.id).toReplacementContentValues()
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteEntry(entryId: Long) {
        writableDatabase.delete(TABLE_FOREIGN_TERMS, "$COLUMN_ID = ?", arrayOf(entryId.toString()))
    }

    fun insertOldWord(oldWord: OldWordEntry): Long {
        require(findDuplicateOldWord(oldWord.oldWord) == null) {
            "Стара реч већ постоји у бази."
        }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val oldWordId = insertOldWordUnchecked(db, oldWord.cleanedForStorage())
            db.setTransactionSuccessful()
            oldWordId
        } finally {
            db.endTransaction()
        }
    }

    fun updateOldWord(oldWord: OldWordEntry) {
        val cleanedOldWord = oldWord.cleanedForStorage()
        require(findDuplicateOldWord(cleanedOldWord.oldWord, cleanedOldWord.id) == null) {
            "Стара реч већ постоји у бази."
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                TABLE_OLD_WORDS,
                cleanedOldWord.toOldWordContentValues(),
                "$COLUMN_ID = ?",
                arrayOf(cleanedOldWord.id.toString())
            )
            db.delete(
                TABLE_OLD_WORD_SYNONYMS,
                "$COLUMN_OLD_WORD_ID = ?",
                arrayOf(cleanedOldWord.id.toString())
            )
            cleanedOldWord.synonyms.forEachIndexed { index, synonym ->
                db.insertOrThrow(
                    TABLE_OLD_WORD_SYNONYMS,
                    null,
                    synonym.toOldWordSynonymContentValues(cleanedOldWord.id, index)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteOldWord(oldWordId: Long) {
        writableDatabase.delete(TABLE_OLD_WORDS, "$COLUMN_ID = ?", arrayOf(oldWordId.toString()))
    }

    fun replaceAll(entries: List<DictionaryEntry>) {
        replaceAllStorage(DictionaryStorage(entries = entries))
    }

    fun replaceAllStorage(storage: DictionaryStorage) {
        val db = writableDatabase
        val uniqueEntries = storage.entries.deduplicatedByForeignWord()
        val uniqueOldWords = storage.oldWords.deduplicatedByOldWord()
        db.beginTransaction()
        try {
            db.delete(TABLE_REPLACEMENT_OPTIONS, null, null)
            db.delete(TABLE_FOREIGN_TERMS, null, null)
            db.delete(TABLE_OLD_WORD_SYNONYMS, null, null)
            db.delete(TABLE_OLD_WORDS, null, null)
            uniqueEntries.forEach { entry ->
                insertEntryUnchecked(db, entry.cleanedForStorage())
            }
            uniqueOldWords.forEach { oldWord ->
                insertOldWordUnchecked(db, oldWord.cleanedForStorage())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun exportStorageToDatabaseFile(targetFile: File, storage: DictionaryStorage) {
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) targetFile.delete()

        SQLiteDatabase.openOrCreateDatabase(targetFile, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            createCurrentTables(db)
            db.execSQL("PRAGMA user_version = $DATABASE_VERSION")
            db.beginTransaction()
            try {
                storage.entries.deduplicatedByForeignWord().forEach { entry ->
                    insertEntryUnchecked(db, entry.cleanedForStorage())
                }
                storage.oldWords.deduplicatedByOldWord().forEach { oldWord ->
                    insertOldWordUnchecked(db, oldWord.cleanedForStorage())
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun exportEntriesToDatabaseFile(targetFile: File, entries: List<DictionaryEntry>) {
        exportStorageToDatabaseFile(targetFile, DictionaryStorage(entries = entries))
    }

    fun storageFromDatabaseFile(sourceFile: File): DictionaryStorage {
        SQLiteDatabase.openDatabase(
            sourceFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            val entries = readEntriesFromDatabase(db).deduplicatedByForeignWord()
            val oldWords = if (tableExists(db, TABLE_OLD_WORDS)) {
                readOldWordsFromDatabase(db).deduplicatedByOldWord()
            } else {
                emptyList()
            }
            return DictionaryStorage(entries = entries, oldWords = oldWords)
        }
    }

    fun entriesFromDatabaseFile(sourceFile: File): List<DictionaryEntry> =
        storageFromDatabaseFile(sourceFile).entries

    private fun insertEntryUnchecked(db: SQLiteDatabase, entry: DictionaryEntry): Long {
        val cleanedEntry = entry.cleanedForStorage()
        require(cleanedEntry.foreignWord.isNotBlank()) {
            "Туђица не сме бити празна."
        }
        val entryId = db.insertOrThrow(
            TABLE_FOREIGN_TERMS,
            null,
            cleanedEntry.toForeignTermContentValues()
        )
        cleanedEntry.options.forEach { option ->
            db.insertOrThrow(
                TABLE_REPLACEMENT_OPTIONS,
                null,
                option.copy(foreignEntryId = entryId).toReplacementContentValues()
            )
        }
        return entryId
    }

    private fun insertOldWordUnchecked(db: SQLiteDatabase, oldWord: OldWordEntry): Long {
        val cleanedOldWord = oldWord.cleanedForStorage()
        require(cleanedOldWord.oldWord.isNotBlank()) {
            "Стара реч не сме бити празна."
        }
        require(cleanedOldWord.synonyms.isNotEmpty()) {
            "Потребно је унети бар једну сличнозначницу."
        }

        val oldWordId = db.insertOrThrow(
            TABLE_OLD_WORDS,
            null,
            cleanedOldWord.toOldWordContentValues()
        )
        cleanedOldWord.synonyms.forEachIndexed { index, synonym ->
            db.insertOrThrow(
                TABLE_OLD_WORD_SYNONYMS,
                null,
                synonym.toOldWordSynonymContentValues(oldWordId, index)
            )
        }
        return oldWordId
    }

    private fun findDuplicateForeignWord(
        foreignWord: String,
        exceptId: Long? = null
    ): DictionaryEntry? {
        val normalizedWord = normalizeDatabaseWord(foreignWord)
        if (normalizedWord.isBlank()) return null

        return getAllEntries().firstOrNull { entry ->
            entry.id != exceptId && normalizeDatabaseWord(entry.foreignWord) == normalizedWord
        }
    }

    private fun findDuplicateOldWord(
        oldWord: String,
        exceptId: Long? = null
    ): OldWordEntry? {
        val normalizedWord = normalizeDatabaseWord(oldWord)
        if (normalizedWord.isBlank()) return null

        return getAllOldWords().firstOrNull { entry ->
            entry.id != exceptId && normalizeDatabaseWord(entry.oldWord) == normalizedWord
        }
    }

    private fun readEntriesFromDatabase(db: SQLiteDatabase): List<DictionaryEntry> {
        val entries = mutableListOf<DictionaryEntry>()
        db.query(
            TABLE_FOREIGN_TERMS,
            null,
            null,
            null,
            null,
            null,
            "$COLUMN_WORD ASC"
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val wordIndex = cursor.getColumnIndexOrThrow(COLUMN_WORD)
            val originIndex = cursor.getColumnIndexOrThrow(COLUMN_ORIGIN)
            val addendumIndex = cursor.getColumnIndexOrThrow(COLUMN_ADDENDUM)

            while (cursor.moveToNext()) {
                val entryId = cursor.getLong(idIndex)
                entries += DictionaryEntry(
                    id = entryId,
                    foreignWord = cursor.getString(wordIndex),
                    origin = cursor.getString(originIndex),
                    addendum = cursor.getString(addendumIndex),
                    options = readOptionsForEntry(db, entryId)
                )
            }
        }
        return entries
    }

    private fun readOptionsForEntry(
        db: SQLiteDatabase,
        entryId: Long
    ): List<ReplacementOption> {
        val options = mutableListOf<ReplacementOption>()
        db.query(
            TABLE_REPLACEMENT_OPTIONS,
            null,
            "$COLUMN_FOREIGN_TERM_ID = ?",
            arrayOf(entryId.toString()),
            null,
            null,
            "$COLUMN_WEIGHT ASC, $COLUMN_REPLACEMENT_WORD ASC"
        ).use { cursor ->
            val replacementWordIndex = cursor.getColumnIndexOrThrow(COLUMN_REPLACEMENT_WORD)
            val explanationIndex = cursor.getColumnIndexOrThrow(COLUMN_EXPLANATION)
            val weightIndex = cursor.getColumnIndex(COLUMN_WEIGHT)

            while (cursor.moveToNext()) {
                options += ReplacementOption(
                    foreignEntryId = entryId,
                    replacementWord = cursor.getString(replacementWordIndex),
                    explanation = cursor.getString(explanationIndex),
                    weight = if (weightIndex >= 0) cursor.getInt(weightIndex).coerceAtLeast(1) else 1
                )
            }
        }
        return options
    }

    private fun readOldWordsFromDatabase(db: SQLiteDatabase): List<OldWordEntry> {
        if (!tableExists(db, TABLE_OLD_WORDS)) return emptyList()

        val oldWords = mutableListOf<OldWordEntry>()
        db.query(
            TABLE_OLD_WORDS,
            null,
            null,
            null,
            null,
            null,
            "$COLUMN_WORD ASC"
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val wordIndex = cursor.getColumnIndexOrThrow(COLUMN_WORD)
            val addendumIndex = cursor.getColumnIndexOrThrow(COLUMN_ADDENDUM)

            while (cursor.moveToNext()) {
                val oldWordId = cursor.getLong(idIndex)
                oldWords += OldWordEntry(
                    id = oldWordId,
                    oldWord = cursor.getString(wordIndex),
                    addendum = cursor.getString(addendumIndex),
                    synonyms = readSynonymsForOldWord(db, oldWordId)
                )
            }
        }
        return oldWords
    }

    private fun readSynonymsForOldWord(db: SQLiteDatabase, oldWordId: Long): List<String> {
        val synonyms = mutableListOf<String>()
        db.query(
            TABLE_OLD_WORD_SYNONYMS,
            arrayOf(COLUMN_SYNONYM),
            "$COLUMN_OLD_WORD_ID = ?",
            arrayOf(oldWordId.toString()),
            null,
            null,
            "$COLUMN_POSITION ASC, $COLUMN_SYNONYM ASC"
        ).use { cursor ->
            val synonymIndex = cursor.getColumnIndexOrThrow(COLUMN_SYNONYM)
            while (cursor.moveToNext()) {
                synonyms += cursor.getString(synonymIndex)
            }
        }
        return synonyms
    }

    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(tableName)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }
}

private fun DictionaryEntry.toForeignTermContentValues(): ContentValues =
    ContentValues().apply {
        put(COLUMN_WORD, foreignWord)
        put(COLUMN_NORMALIZED_WORD, normalizeDatabaseWord(foreignWord))
        put(COLUMN_ORIGIN, origin)
        put(COLUMN_ADDENDUM, addendum)
        put(COLUMN_UPDATED_AT, System.currentTimeMillis())
    }

private fun ReplacementOption.toReplacementContentValues(): ContentValues =
    ContentValues().apply {
        put(COLUMN_FOREIGN_TERM_ID, foreignEntryId)
        put(COLUMN_REPLACEMENT_WORD, replacementWord)
        put(COLUMN_NORMALIZED_REPLACEMENT_WORD, normalizeDatabaseWord(replacementWord))
        put(COLUMN_EXPLANATION, explanation)
        put(COLUMN_WEIGHT, weight.coerceAtLeast(1))
        put(COLUMN_UPDATED_AT, System.currentTimeMillis())
    }

private fun OldWordEntry.toOldWordContentValues(): ContentValues =
    ContentValues().apply {
        put(COLUMN_WORD, oldWord)
        put(COLUMN_NORMALIZED_WORD, normalizeDatabaseWord(oldWord))
        put(COLUMN_ADDENDUM, addendum)
        put(COLUMN_UPDATED_AT, System.currentTimeMillis())
    }

private fun String.toOldWordSynonymContentValues(
    oldWordId: Long,
    position: Int
): ContentValues =
    ContentValues().apply {
        put(COLUMN_OLD_WORD_ID, oldWordId)
        put(COLUMN_SYNONYM, this@toOldWordSynonymContentValues)
        put(COLUMN_NORMALIZED_SYNONYM, normalizeDatabaseWord(this@toOldWordSynonymContentValues))
        put(COLUMN_POSITION, position)
        put(COLUMN_UPDATED_AT, System.currentTimeMillis())
    }

private val databaseLocale: Locale = Locale.forLanguageTag("sr-Cyrl-RS")

private fun normalizeDatabaseWord(value: String): String =
    value.trim().lowercase(databaseLocale)

private fun DictionaryEntry.cleanedForStorage(): DictionaryEntry =
    copy(
        foreignWord = foreignWord.trim(),
        origin = origin.trim(),
        addendum = addendum.trim(),
        options = options.cleanedForStorage()
    )

private fun List<ReplacementOption>.cleanedForStorage(): List<ReplacementOption> {
    val seen = mutableSetOf<String>()
    return map { option ->
        option.copy(
            replacementWord = option.replacementWord.trim(),
            explanation = option.explanation.trim(),
            weight = option.weight.coerceAtLeast(1)
        )
    }.filter { option ->
        val key = normalizeDatabaseWord(option.replacementWord)
        key.isNotBlank() && seen.add(key)
    }
}

private fun List<DictionaryEntry>.deduplicatedByForeignWord(): List<DictionaryEntry> {
    val seen = mutableSetOf<String>()
    return map { it.cleanedForStorage() }.filter { entry ->
        val key = normalizeDatabaseWord(entry.foreignWord)
        key.isNotBlank() && seen.add(key)
    }
}

private fun OldWordEntry.cleanedForStorage(): OldWordEntry {
    val seen = mutableSetOf<String>()
    return copy(
        oldWord = oldWord.trim(),
        addendum = addendum.trim(),
        synonyms = synonyms.map(String::trim).filter { synonym ->
            val key = normalizeDatabaseWord(synonym)
            key.isNotBlank() && seen.add(key)
        }
    )
}

private fun List<OldWordEntry>.deduplicatedByOldWord(): List<OldWordEntry> {
    val seen = mutableSetOf<String>()
    return map { it.cleanedForStorage() }.filter { oldWord ->
        val key = normalizeDatabaseWord(oldWord.oldWord)
        key.isNotBlank() && oldWord.synonyms.isNotEmpty() && seen.add(key)
    }
}
