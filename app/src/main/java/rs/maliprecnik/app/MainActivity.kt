package rs.maliprecnik.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File

/** Улазна тачка Android апликације. */
class MainActivity : ComponentActivity() {
    private lateinit var database: DictionaryDatabase
    private lateinit var proposalRepository: FirestoreProposalRepository
    private lateinit var notificationsRepository: FirestoreNotificationsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DictionaryDatabase.installSeedDatabaseIfMissing(applicationContext)
        database = DictionaryDatabase(applicationContext)
        proposalRepository = FirestoreProposalRepository(applicationContext)
        notificationsRepository = FirestoreNotificationsRepository(applicationContext)
        database.ensureInitialData(
            defaultStorage = loadPackagedDefaultDatabase()
        )

        enableEdgeToEdge()
        setContent {
            // Compose поново исцртава екране када се ова листа замени новом вредношћу.
            val initialStorage = remember { database.getStorage() }
            var entries by remember { mutableStateOf(initialStorage.entries) }
            var oldWords by remember { mutableStateOf(initialStorage.oldWords) }

            fun reloadStorage() {
                val storage = database.getStorage()
                entries = storage.entries
                oldWords = storage.oldWords
            }

            PrechnikTheme {
                PrechnikScreen(
                    entries = entries,
                    oldWords = oldWords,
                    proposalRepository = proposalRepository,
                    notificationsRepository = notificationsRepository,
                    publicStorageSizeBytes = packagedAssetSize("prechnik_seed.db"),
                    publicStorageUpdatedMillis = packageLastUpdateTime(),
                    onInsertEntry = { entry ->
                        database.insertEntry(entry)
                        reloadStorage()
                    },
                    onInsertOldWord = { oldWord ->
                        database.insertOldWord(oldWord)
                        reloadStorage()
                    },
                    onUpdateEntry = { entry ->
                        database.updateEntry(entry)
                        reloadStorage()
                    },
                    onUpdateOldWord = { oldWord ->
                        database.updateOldWord(oldWord)
                        reloadStorage()
                    },
                    onDeleteEntry = { entryId ->
                        database.deleteEntry(entryId)
                        reloadStorage()
                    },
                    onDeleteOldWord = { oldWordId ->
                        database.deleteOldWord(oldWordId)
                        reloadStorage()
                    },
                    onReplaceStorage = { importedStorage ->
                        database.replaceAllStorage(importedStorage)
                        reloadStorage()
                    },
                    onExportDatabase = { uri, currentStorage ->
                        exportDatabase(uri, currentStorage)
                    },
                    onImportDatabase = { uri ->
                        importDatabase(uri)
                    },
                    onLoadPackagedDatabase = {
                        loadPackagedDefaultDatabase()
                    }
                )
            }
        }
    }

    /** Извоз тренутних уноса као SQLite база коју може да увезе Android или Python Пречник. */
    private fun exportDatabase(uri: Uri, storage: DictionaryStorage) {
        val tempFile = File(cacheDir, "prechnik-export.db")
        database.exportStorageToDatabaseFile(
            tempFile,
            storage.copy(
                entries = storage.entries.sortedByForeignWord(),
                oldWords = storage.oldWords.sortedByOldWord()
            )
        )
        contentResolver.openOutputStream(uri)?.use { output ->
            tempFile.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: error("Није могуће отворити изабрану датотеку.")
        tempFile.delete()
    }

    /** Увоз SQLite базе кроз Android системски избор датотека. */
    private fun importDatabase(uri: Uri): DictionaryStorage {
        val tempFile = File(cacheDir, "prechnik-import.db")
        contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Није могуће прочитати изабрану датотеку.")

        return try {
            database.storageFromDatabaseFile(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    /** Чита почетну базу из `assets`, али је не уписује ако корисник већ има своју базу. */
    private fun loadPackagedDefaultDatabase(): DictionaryStorage {
        val tempFile = File(cacheDir, "prechnik-default.db")
        return runCatching {
            assets.open("prechnik_seed.db").use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            database.storageFromDatabaseFile(tempFile)
        }.getOrDefault(DictionaryStorage()).also {
            tempFile.delete()
        }
    }

    /** Величина фајла из `assets`; користи се само за приказ статистике јавног складишта. */
    private fun packagedAssetSize(assetName: String): Long =
        runCatching {
            assets.open(assetName).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
        }.getOrDefault(0L)

    /** Време последње измене APK пакета; приближно време освежавања упакованог јавног складишта. */
    private fun packageLastUpdateTime(): Long =
        runCatching {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
}
