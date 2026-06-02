package rs.maliprecnik.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

private const val DATABASE_NAME = "prechnik.db"
private const val DATABASE_VERSION = 7
private const val SEED_DATABASE_ASSET = "prechnik_seed.db"

private const val TABLE_FOREIGN_TERMS = "foreign_terms"
private const val TABLE_REPLACEMENT_OPTIONS = "replacement_options"

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
        } else {
            recreateCurrentTables(db)
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
    }

    private fun recreateCurrentTables(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_REPLACEMENT_OPTIONS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FOREIGN_TERMS")
        createCurrentTables(db)
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

    fun ensureInitialData(defaultEntries: List<DictionaryEntry>) {
        if (countEntries() > 0) return
        if (defaultEntries.isNotEmpty()) replaceAll(defaultEntries)
    }

    fun countEntries(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_FOREIGN_TERMS", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getAllEntries(): List<DictionaryEntry> =
        readEntriesFromDatabase(readableDatabase).deduplicatedByForeignWord()

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
        require(cleanedEntry.options.isNotEmpty()) {
            "Потребно је унети бар једну српскословенску реч."
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

    fun replaceAll(entries: List<DictionaryEntry>) {
        val db = writableDatabase
        val uniqueEntries = entries.deduplicatedByForeignWord()
        db.beginTransaction()
        try {
            db.delete(TABLE_REPLACEMENT_OPTIONS, null, null)
            db.delete(TABLE_FOREIGN_TERMS, null, null)
            uniqueEntries.forEach { entry ->
                insertEntryUnchecked(db, entry.cleanedForStorage())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun exportEntriesToDatabaseFile(targetFile: File, entries: List<DictionaryEntry>) {
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) targetFile.delete()

        SQLiteDatabase.openOrCreateDatabase(targetFile, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            createCurrentTables(db)
            db.execSQL("PRAGMA user_version = $DATABASE_VERSION")
            db.beginTransaction()
            try {
                entries.deduplicatedByForeignWord().forEach { entry ->
                    insertEntryUnchecked(db, entry.cleanedForStorage())
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun entriesFromDatabaseFile(sourceFile: File): List<DictionaryEntry> {
        SQLiteDatabase.openDatabase(
            sourceFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            return readEntriesFromDatabase(db).deduplicatedByForeignWord()
        }
    }

    private fun insertEntryUnchecked(db: SQLiteDatabase, entry: DictionaryEntry): Long {
        val cleanedEntry = entry.cleanedForStorage()
        require(cleanedEntry.foreignWord.isNotBlank()) {
            "Туђица не сме бити празна."
        }
        require(cleanedEntry.options.isNotEmpty()) {
            "Потребно је унети бар једну српскословенску реч."
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
        key.isNotBlank() && entry.options.isNotEmpty() && seen.add(key)
    }
}
