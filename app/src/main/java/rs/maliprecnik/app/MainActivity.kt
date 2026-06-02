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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DictionaryDatabase.installSeedDatabaseIfMissing(applicationContext)
        database = DictionaryDatabase(applicationContext)
        proposalRepository = FirestoreProposalRepository(applicationContext)
        database.ensureInitialData(
            defaultEntries = loadPackagedDefaultDatabase()
        )

        enableEdgeToEdge()
        setContent {
            // Compose поново исцртава екране када се ова листа замени новом вредношћу.
            var entries by remember { mutableStateOf(database.getAllEntries()) }

            fun reloadEntries() {
                entries = database.getAllEntries()
            }

            PrechnikTheme {
                PrechnikScreen(
                    entries = entries,
                    proposalRepository = proposalRepository,
                    publicStorageSizeBytes = packagedAssetSize("prechnik_seed.db"),
                    publicStorageUpdatedMillis = packageLastUpdateTime(),
                    onInsertEntry = { entry ->
                        database.insertEntry(entry)
                        reloadEntries()
                    },
                    onUpdateEntry = { entry ->
                        database.updateEntry(entry)
                        reloadEntries()
                    },
                    onDeleteEntry = { entryId ->
                        database.deleteEntry(entryId)
                        reloadEntries()
                    },
                    onReplaceEntries = { importedEntries ->
                        database.replaceAll(importedEntries)
                        reloadEntries()
                    },
                    onExportDatabase = { uri, currentEntries ->
                        exportDatabase(uri, currentEntries)
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
    private fun exportDatabase(uri: Uri, entries: List<DictionaryEntry>) {
        val tempFile = File(cacheDir, "prechnik-export.db")
        database.exportEntriesToDatabaseFile(tempFile, entries.sortedByForeignWord())
        contentResolver.openOutputStream(uri)?.use { output ->
            tempFile.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: error("Није могуће отворити изабрану датотеку.")
        tempFile.delete()
    }

    /** Увоз SQLite базе кроз Android системски избор датотека. */
    private fun importDatabase(uri: Uri): List<DictionaryEntry> {
        val tempFile = File(cacheDir, "prechnik-import.db")
        contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Није могуће прочитати изабрану датотеку.")

        return try {
            database.entriesFromDatabaseFile(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    /** Чита почетну базу из `assets`, али је не уписује ако корисник већ има своју базу. */
    private fun loadPackagedDefaultDatabase(): List<DictionaryEntry> {
        val tempFile = File(cacheDir, "prechnik-default.db")
        return runCatching {
            assets.open("prechnik_seed.db").use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            database.entriesFromDatabaseFile(tempFile)
        }.getOrDefault(emptyList()).also {
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
