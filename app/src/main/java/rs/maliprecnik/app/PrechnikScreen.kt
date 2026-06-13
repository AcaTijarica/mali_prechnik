package rs.maliprecnik.app

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.io.OutputStreamWriter
import kotlinx.coroutines.launch

/**
 * Главни Compose екран апликације.
 *
 * Овде се држи тренутно UI стање: који екран је отворен, која реч се мења,
 * која реч је отворена из листе, и покретачи Android прозора за складиште.
 */
@Composable
fun PrechnikScreen(
    entries: List<DictionaryEntry>,
    proposalRepository: FirestoreProposalRepository,
    publicStorageSizeBytes: Long,
    publicStorageUpdatedMillis: Long,
    onInsertEntry: (DictionaryEntry) -> Unit,
    onUpdateEntry: (DictionaryEntry) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onReplaceEntries: (List<DictionaryEntry>) -> Unit,
    onExportDatabase: (Uri, List<DictionaryEntry>) -> Unit,
    onImportDatabase: (Uri) -> List<DictionaryEntry>,
    onLoadPackagedDatabase: () -> List<DictionaryEntry>
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val publicStorageEntries = remember { onLoadPackagedDatabase() }

    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.Search) }
    var editReturnScreen by rememberSaveable { mutableStateOf(AppScreen.Search) }
    var editingEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchDirection by rememberSaveable { mutableStateOf(SearchDirection.ForeignToReplacement) }
    var statusMessage by rememberSaveable {
        mutableStateOf("Складиште учитано. Број туђица: ${entries.size}.")
    }
    var addResetKey by rememberSaveable { mutableStateOf(0) }
    val proposalRulesPreferences = context.getSharedPreferences("proposal_rules", Context.MODE_PRIVATE)
    var proposalRulesAccepted by rememberSaveable {
        mutableStateOf(proposalRulesPreferences.getBoolean("accepted", false))
    }

    fun acceptProposalRules() {
        proposalRulesPreferences.edit().putBoolean("accepted", true).apply()
        proposalRulesAccepted = true
    }

    // Drawer се отвара/затвара у coroutine-у јер је Material drawer анимирано стање.
    fun openDrawer() {
        scope.launch { drawerState.open() }
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    fun openEditor(entry: DictionaryEntry, returnScreen: AppScreen) {
        editingEntryId = entry.id
        editReturnScreen = returnScreen
        currentScreen = AppScreen.EditWord
    }

    // Листа туђица прво води на читљив приказ, па тек одатле корисник улази у измену.
    fun openForeignWord(entry: DictionaryEntry) {
        detailEntryId = entry.id
        editingEntryId = null
        currentScreen = AppScreen.ForeignWord
    }

    fun returnFromEditor() {
        editingEntryId = null
        currentScreen = editReturnScreen
    }

    fun submitProposal(entry: DictionaryEntry, type: String, proposalText: String = proposalTextFromEntry(entry)) {
        statusMessage = "Предлог се шаље на уреднички преглед..."
        proposalRepository.submitProposal(
            entry = entry,
            type = type,
            proposalText = proposalText,
            onSuccess = {
                statusMessage = "Предлог је послат на уреднички преглед. Ако буде одобрен, појавиће се на гласању."
            },
            onFailure = { error ->
                statusMessage = proposalErrorMessage(error)
            }
        )
    }

    // CreateDocument/OpenDocument покрећу Android-ов системски избор датотеке.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                        writer.write(entriesToJson(entries.sortedByForeignWord()))
                    }
                } ?: error("Није могуће отворити изабрану датотеку.")
            }.onSuccess {
                statusMessage = "Складиште је извезено."
                Toast.makeText(context, "Складиште је извезено.", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                statusMessage = "Извоз складишта није успео: ${error.message}"
            }
        }
    )

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                val json = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                } ?: error("Није могуће прочитати изабрану датотеку.")
                entriesFromJson(json)
            }.onSuccess { importedEntries ->
                if (importedEntries.isEmpty()) {
                    statusMessage = "У изабраној датотеци нема ваљаних уноса."
                } else {
                    onReplaceEntries(importedEntries)
                    editingEntryId = null
                    detailEntryId = null
                    currentScreen = AppScreen.Storage
                    statusMessage = "Увезено је ${importedEntries.size} туђица."
                }
            }.onFailure { error ->
                statusMessage = "Увоз складишта није успео: ${error.message}"
            }
        }
    )

    val exportDatabaseLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.sqlite3"),
        onResult = { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                onExportDatabase(uri, entries.sortedByForeignWord())
            }.onSuccess {
                statusMessage = "SQLite складиште је извезено."
                Toast.makeText(context, "SQLite складиште је извезено.", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                statusMessage = "DB извоз складишта није успео: ${error.message}"
            }
        }
    )

    val importDatabaseLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                onImportDatabase(uri)
            }.onSuccess { importedEntries ->
                if (importedEntries.isEmpty()) {
                    statusMessage = "У изабраној SQLite бази нема ваљаних уноса."
                } else {
                    onReplaceEntries(importedEntries)
                    editingEntryId = null
                    detailEntryId = null
                    currentScreen = AppScreen.Storage
                    statusMessage = "Увезено је ${importedEntries.size} туђица из SQLite складишта."
                }
            }.onFailure { error ->
                statusMessage = "DB увоз складишта није успео: ${error.message}"
            }
        }
    )

    // Android дугме "назад" прво затвара мени или враћа са детаља/измене,
    // уместо да одмах изађе из апликације.
    BackHandler(
        enabled = drawerState.isOpen ||
            currentScreen == AppScreen.EditWord ||
            currentScreen == AppScreen.ForeignWord
    ) {
        when {
            drawerState.isOpen -> closeDrawer()
            currentScreen == AppScreen.EditWord -> returnFromEditor()
            currentScreen == AppScreen.ForeignWord -> {
                detailEntryId = null
                currentScreen = AppScreen.ForeignWords
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            PrechnikDrawer(
                currentScreen = currentScreen,
                onNavigate = { screen ->
                    currentScreen = screen
                    editingEntryId = null
                    detailEntryId = null
                    closeDrawer()
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                PrechnikTopBar(
                    title = currentScreen.title,
                    onMenuClick = { openDrawer() }
                )
            }
        ) { innerPadding ->
            when (currentScreen) {
                AppScreen.Search -> SearchScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    entries = entries,
                    query = query,
                    onQueryChange = { query = it },
                    searchDirection = searchDirection,
                    onSearchDirectionChange = {
                        searchDirection = it
                        query = ""
                    },
                    statusMessage = statusMessage,
                    proposalRulesAccepted = proposalRulesAccepted,
                    onAcceptProposalRules = { acceptProposalRules() },
                    onEditEntry = { entry -> openEditor(entry, AppScreen.Search) },
                    onSuggestEntryChange = { entry, proposalText -> submitProposal(entry, "edit_word", proposalText) }
                )

                AppScreen.AddWord -> EntryEditorScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    resetKey = addResetKey,
                    initialEntry = null,
                    statusMessage = statusMessage,
                    proposalRulesAccepted = proposalRulesAccepted,
                    onAcceptProposalRules = { acceptProposalRules() },
                    onSave = { entry ->
                        if (entries.hasForeignWord(entry.foreignWord)) {
                            statusMessage = "Туђица већ постоји у бази."
                        } else {
                            onInsertEntry(entry)
                            addResetKey += 1
                            statusMessage = "Унос је додат у SQLite базу."
                        }
                    },
                    onSuggest = { entry -> submitProposal(entry, "new_word") },
                    onDelete = null,
                    onCancel = {
                        addResetKey += 1
                        statusMessage = "Образац је очишћен."
                    }
                )

                AppScreen.EditWord -> {
                    val editingEntry = entries.firstOrNull { it.id == editingEntryId }
                    if (editingEntry == null) {
                        MissingEntryScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            onBack = { currentScreen = editReturnScreen }
                        )
                    } else {
                        EntryEditorScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            resetKey = editingEntry.id.toInt(),
                            initialEntry = editingEntry,
                            statusMessage = statusMessage,
                            proposalRulesAccepted = proposalRulesAccepted,
                            onAcceptProposalRules = { acceptProposalRules() },
                            onSave = { entry ->
                                if (entries.hasForeignWord(entry.foreignWord, editingEntry.id)) {
                                    statusMessage = "Туђица већ постоји у бази."
                                } else {
                                    onUpdateEntry(entry)
                                    statusMessage = "Измена је сачувана у SQLite бази."
                                }
                            },
                            onSuggest = null,
                            onDelete = {
                                onDeleteEntry(editingEntry.id)
                                editingEntryId = null
                                if (editReturnScreen == AppScreen.ForeignWord) {
                                    detailEntryId = null
                                    currentScreen = AppScreen.ForeignWords
                                } else {
                                    currentScreen = editReturnScreen
                                }
                                statusMessage = "Унос је обрисан из базе."
                            },
                            onCancel = {
                                editingEntryId = null
                                currentScreen = editReturnScreen
                            }
                        )
                    }
                }

                AppScreen.ForeignWord -> {
                    val detailEntry = entries.firstOrNull { it.id == detailEntryId }
                    if (detailEntry == null) {
                        MissingEntryScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            onBack = {
                                detailEntryId = null
                                currentScreen = AppScreen.ForeignWords
                            }
                        )
                    } else {
                        ForeignWordDetailScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            entry = detailEntry,
                            statusMessage = statusMessage,
                            proposalRulesAccepted = proposalRulesAccepted,
                            onAcceptProposalRules = { acceptProposalRules() },
                            onEditEntry = { entry -> openEditor(entry, AppScreen.ForeignWord) },
                            onSuggestEntryChange = { entry, proposalText -> submitProposal(entry, "edit_word", proposalText) }
                        )
                    }
                }

                AppScreen.ForeignWords -> WordListScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    entries = entries,
                    statusMessage = statusMessage,
                    onEntryClick = { entry -> openForeignWord(entry) }
                )

                AppScreen.Voting -> VotingScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    entries = entries,
                    repository = proposalRepository
                )

                AppScreen.Storage -> StorageScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    entries = entries,
                    statusMessage = statusMessage,
                    databaseSizeBytes = context.getDatabasePath("prechnik.db").length(),
                    databaseLastModifiedMillis = context.getDatabasePath("prechnik.db").lastModified(),
                    publicEntries = publicStorageEntries,
                    publicDatabaseSizeBytes = publicStorageSizeBytes,
                    publicStorageUpdatedMillis = publicStorageUpdatedMillis,
                    onImportJson = {
                        importLauncher.launch(arrayOf("application/json", "text/*"))
                    },
                    onExportJson = {
                        exportLauncher.launch("prechnik-baza.json")
                    },
                    onImportDatabase = {
                        importDatabaseLauncher.launch(
                            arrayOf(
                                "application/vnd.sqlite3",
                                "application/x-sqlite3",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onExportDatabase = {
                        exportDatabaseLauncher.launch("prechnik-baza.db")
                    },
                    onOverwritePersonalStorage = {
                        val packagedEntries = onLoadPackagedDatabase()
                        if (packagedEntries.isEmpty()) {
                            statusMessage = "Јавно складиште из апликације није пронађено."
                        } else {
                            onReplaceEntries(packagedEntries)
                            editingEntryId = null
                            detailEntryId = null
                            currentScreen = AppScreen.Storage
                            statusMessage = "Лично складиште је преписано јавним. Број туђица: ${packagedEntries.size}."
                        }
                    },
                    onAddNewWordsFromPublicStorage = {
                        val packagedEntries = onLoadPackagedDatabase()
                        if (packagedEntries.isEmpty()) {
                            statusMessage = "Јавно складиште из апликације није пронађено."
                        } else {
                            val (mergedEntries, addedCount) = entries.withOnlyNewForeignWordsFrom(packagedEntries)
                            if (addedCount == 0) {
                                statusMessage = "Нема нових туђица за додавање из јавног складишта."
                            } else {
                                onReplaceEntries(mergedEntries)
                                editingEntryId = null
                                detailEntryId = null
                                currentScreen = AppScreen.Storage
                                statusMessage = "Додато је $addedCount нових туђица из јавног складишта."
                            }
                        }
                    }
                )

                AppScreen.Idea -> IdeaScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )

                AppScreen.Notifications -> NotificationsScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )

                AppScreen.PrivacyRules -> PrivacyRulesScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }
        }
    }
}
