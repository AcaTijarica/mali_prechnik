package rs.maliprecnik.app

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
    oldWords: List<OldWordEntry>,
    proposalRepository: FirestoreProposalRepository,
    notificationsRepository: FirestoreNotificationsRepository,
    publicStorageSizeBytes: Long,
    publicStorageUpdatedMillis: Long,
    onInsertEntry: (DictionaryEntry) -> Unit,
    onInsertOldWord: (OldWordEntry) -> Unit,
    onUpdateEntry: (DictionaryEntry) -> Unit,
    onUpdateOldWord: (OldWordEntry) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onDeleteOldWord: (Long) -> Unit,
    onReplaceStorage: (DictionaryStorage) -> Unit,
    onExportDatabase: (Uri, DictionaryStorage) -> Unit,
    onImportDatabase: (Uri) -> DictionaryStorage,
    onLoadPackagedDatabase: () -> DictionaryStorage
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val publicStorage = remember { onLoadPackagedDatabase() }
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.Search) }
    var editReturnScreen by rememberSaveable { mutableStateOf(AppScreen.Search) }
    var editOldWordReturnScreen by rememberSaveable { mutableStateOf(AppScreen.OldWords) }
    var editingEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingOldWordId by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailOldWordId by rememberSaveable { mutableStateOf<Long?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchDirection by rememberSaveable { mutableStateOf(SearchDirection.ForeignToReplacement) }
    var showExitEditorDialog by rememberSaveable { mutableStateOf(false) }
    var showExitOldWordEditorDialog by rememberSaveable { mutableStateOf(false) }
    var statusMessage by rememberSaveable {
        mutableStateOf(
            "Складиште учитано. Туђица: ${entries.size}; старих речи: ${oldWords.size}."
        )
    }
    var addResetKey by rememberSaveable { mutableStateOf(0) }
    var addOldWordResetKey by rememberSaveable { mutableStateOf(0) }
    val proposalRulesPreferences = context.getSharedPreferences("proposal_rules", Context.MODE_PRIVATE)
    val displaySettingsPreferences = context.getSharedPreferences("display_settings", Context.MODE_PRIVATE)
    var proposalRulesAccepted by rememberSaveable {
        mutableStateOf(proposalRulesPreferences.getBoolean("accepted", false))
    }
    var displaySettings by remember {
        mutableStateOf(
            DisplaySettings(
                showOrigin = displaySettingsPreferences.getBoolean("show_origin", true),
                showExplanations = displaySettingsPreferences.getBoolean("show_explanations", true),
                showAddendum = displaySettingsPreferences.getBoolean("show_addendum", true),
                fontChoice = AppFontChoice.fromStorageKey(
                    displaySettingsPreferences.getString("font_choice", AppFontChoice.Monomakh.storageKey)
                ),
                fontSizeChoice = AppFontSizeChoice.fromStorageKey(
                    displaySettingsPreferences.getString("font_size", AppFontSizeChoice.Medium.storageKey)
                )
            )
        )
    }

    fun acceptProposalRules() {
        proposalRulesPreferences.edit().putBoolean("accepted", true).apply()
        proposalRulesAccepted = true
    }

    fun applyDisplaySettings(settings: DisplaySettings) {
        displaySettingsPreferences.edit()
            .putBoolean("show_origin", settings.showOrigin)
            .putBoolean("show_explanations", settings.showExplanations)
            .putBoolean("show_addendum", settings.showAddendum)
            .putString("font_choice", settings.fontChoice.storageKey)
            .putString("font_size", settings.fontSizeChoice.storageKey)
            .apply()
        displaySettings = settings
        statusMessage = "Подешавања су примењена."
    }

    fun hideKeyboard() {
        focusManager.clearFocus(force = true)
    }

    // Drawer се отвара/затвара у coroutine-у јер је Material drawer анимирано стање.
    fun openDrawer() {
        hideKeyboard()
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

    fun openOldWordEditor(oldWord: OldWordEntry, returnScreen: AppScreen) {
        editingOldWordId = oldWord.id
        editOldWordReturnScreen = returnScreen
        currentScreen = AppScreen.EditOldWord
    }

    // Листа туђица прво води на читљив приказ, па тек одатле корисник улази у измену.
    fun openForeignWord(entry: DictionaryEntry) {
        detailEntryId = entry.id
        editingEntryId = null
        currentScreen = AppScreen.ForeignWord
    }

    fun openOldWord(oldWord: OldWordEntry) {
        detailOldWordId = oldWord.id
        currentScreen = AppScreen.OldWord
    }

    fun returnFromEditor() {
        editingEntryId = null
        currentScreen = editReturnScreen
    }

    fun returnFromOldWordEditor() {
        editingOldWordId = null
        currentScreen = editOldWordReturnScreen
    }

    fun requestReturnFromEditor() {
        showExitEditorDialog = true
    }

    fun requestReturnFromOldWordEditor() {
        showExitOldWordEditorDialog = true
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
                        writer.write(
                            storageToJson(
                                DictionaryStorage(
                                    entries = entries.sortedByForeignWord(),
                                    oldWords = oldWords.sortedByOldWord()
                                )
                            )
                        )
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
                storageFromJson(json)
            }.onSuccess { importedStorage ->
                if (importedStorage.entries.isEmpty() && importedStorage.oldWords.isEmpty()) {
                    statusMessage = "У изабраној датотеци нема ваљаних уноса."
                } else {
                    onReplaceStorage(importedStorage)
                    editingEntryId = null
                    detailEntryId = null
                    detailOldWordId = null
                    currentScreen = AppScreen.Storage
                    statusMessage =
                        "Увезено је ${importedStorage.entries.size} туђица и " +
                            "${importedStorage.oldWords.size} старих речи."
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
                onExportDatabase(
                    uri,
                    DictionaryStorage(
                        entries = entries.sortedByForeignWord(),
                        oldWords = oldWords.sortedByOldWord()
                    )
                )
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
            }.onSuccess { importedStorage ->
                if (importedStorage.entries.isEmpty() && importedStorage.oldWords.isEmpty()) {
                    statusMessage = "У изабраној SQLite бази нема ваљаних уноса."
                } else {
                    onReplaceStorage(importedStorage)
                    editingEntryId = null
                    detailEntryId = null
                    detailOldWordId = null
                    currentScreen = AppScreen.Storage
                    statusMessage =
                        "Увезено је ${importedStorage.entries.size} туђица и " +
                            "${importedStorage.oldWords.size} старих речи из SQLite складишта."
                }
            }.onFailure { error ->
                statusMessage = "DB увоз складишта није успео: ${error.message}"
            }
        }
    )

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = typographyForDisplaySettings(displaySettings),
        shapes = MaterialTheme.shapes
    ) {
    if (showExitEditorDialog) {
        AlertDialog(
            onDismissRequest = { showExitEditorDialog = false },
            title = { Text("Излазак из измене") },
            text = { Text("Уколико изађете, промене неће бити сачуване.") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitEditorDialog = false
                        returnFromEditor()
                    }
                ) {
                    Text("Изађи")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExitEditorDialog = false }) {
                    Text("Остани")
                }
            }
        )
    }

    if (showExitOldWordEditorDialog) {
        AlertDialog(
            onDismissRequest = { showExitOldWordEditorDialog = false },
            title = { Text("Излазак из измене") },
            text = { Text("Уколико изађете, промене неће бити сачуване.") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitOldWordEditorDialog = false
                        returnFromOldWordEditor()
                    }
                ) {
                    Text("Изађи")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExitOldWordEditorDialog = false }) {
                    Text("Остани")
                }
            }
        )
    }

    // Android дугме "назад" прво затвара мени или враћа са детаља/измене,
    // уместо да одмах изађе из апликације.
    BackHandler(
        enabled = isKeyboardVisible ||
            drawerState.isOpen ||
            currentScreen == AppScreen.EditWord ||
            currentScreen == AppScreen.EditOldWord ||
            currentScreen == AppScreen.ForeignWord ||
            currentScreen == AppScreen.OldWord
    ) {
        when {
            isKeyboardVisible -> hideKeyboard()
            drawerState.isOpen -> closeDrawer()
            currentScreen == AppScreen.EditWord -> requestReturnFromEditor()
            currentScreen == AppScreen.EditOldWord -> requestReturnFromOldWordEditor()
            currentScreen == AppScreen.ForeignWord -> {
                detailEntryId = null
                currentScreen = AppScreen.ForeignWords
            }
            currentScreen == AppScreen.OldWord -> {
                detailOldWordId = null
                currentScreen = AppScreen.OldWords
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            PrechnikDrawer(
                currentScreen = currentScreen,
                onNavigate = { screen ->
                    hideKeyboard()
                    currentScreen = screen
                    editingEntryId = null
                    editingOldWordId = null
                    detailEntryId = null
                    detailOldWordId = null
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
                    oldWords = oldWords,
                    query = query,
                    onQueryChange = { query = it },
                    searchDirection = searchDirection,
                    onSearchDirectionChange = {
                        searchDirection = it
                        query = ""
                    },
                    displaySettings = displaySettings,
                    statusMessage = statusMessage,
                    proposalRulesAccepted = proposalRulesAccepted,
                    onAcceptProposalRules = { acceptProposalRules() },
                    onEditEntry = { entry -> openEditor(entry, AppScreen.Search) },
                    onEditOldWord = { oldWord -> openOldWordEditor(oldWord, AppScreen.Search) },
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

                AppScreen.AddOldWord -> OldWordEditorScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    resetKey = addOldWordResetKey,
                    initialOldWord = null,
                    statusMessage = statusMessage,
                    onSave = { oldWord ->
                        if (oldWords.hasOldWord(oldWord.oldWord)) {
                            statusMessage = "Стара реч већ постоји у бази."
                        } else {
                            onInsertOldWord(oldWord)
                            addOldWordResetKey += 1
                            statusMessage = "Стара реч је додата у SQLite базу."
                        }
                    },
                    onDelete = null,
                    onCancel = {
                        addOldWordResetKey += 1
                        statusMessage = "Образац за стару реч је очишћен."
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
                            onCancel = { requestReturnFromEditor() }
                        )
                    }
                }

                AppScreen.EditOldWord -> {
                    val editingOldWord = oldWords.firstOrNull { it.id == editingOldWordId }
                    if (editingOldWord == null) {
                        MissingEntryScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            onBack = { currentScreen = editOldWordReturnScreen }
                        )
                    } else {
                        OldWordEditorScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            resetKey = editingOldWord.id.toInt(),
                            initialOldWord = editingOldWord,
                            statusMessage = statusMessage,
                            onSave = { oldWord ->
                                if (oldWords.hasOldWord(oldWord.oldWord, editingOldWord.id)) {
                                    statusMessage = "Стара реч већ постоји у бази."
                                } else {
                                    onUpdateOldWord(oldWord)
                                    statusMessage = "Измена старе речи је сачувана у SQLite бази."
                                }
                            },
                            onDelete = {
                                onDeleteOldWord(editingOldWord.id)
                                editingOldWordId = null
                                if (editOldWordReturnScreen == AppScreen.OldWord) {
                                    detailOldWordId = null
                                    currentScreen = AppScreen.OldWords
                                } else {
                                    currentScreen = editOldWordReturnScreen
                                }
                                statusMessage = "Стара реч је обрисана из базе."
                            },
                            onCancel = { requestReturnFromOldWordEditor() }
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
                            displaySettings = displaySettings,
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

                AppScreen.OldWord -> {
                    val detailOldWord = oldWords.firstOrNull { it.id == detailOldWordId }
                    if (detailOldWord == null) {
                        MissingEntryScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            onBack = {
                                detailOldWordId = null
                                currentScreen = AppScreen.OldWords
                            }
                        )
                    } else {
                        OldWordDetailScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            oldWord = detailOldWord,
                            statusMessage = statusMessage,
                            onEditOldWord = { oldWord -> openOldWordEditor(oldWord, AppScreen.OldWord) }
                        )
                    }
                }

                AppScreen.OldWords -> OldWordListScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    oldWords = oldWords,
                    statusMessage = statusMessage,
                    onOldWordClick = { oldWord -> openOldWord(oldWord) }
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
                    oldWords = oldWords,
                    statusMessage = statusMessage,
                    databaseSizeBytes = context.getDatabasePath("prechnik.db").length(),
                    databaseLastModifiedMillis = context.getDatabasePath("prechnik.db").lastModified(),
                    publicEntries = publicStorage.entries,
                    publicOldWords = publicStorage.oldWords,
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
                        val packagedStorage = onLoadPackagedDatabase()
                        if (packagedStorage.entries.isEmpty() && packagedStorage.oldWords.isEmpty()) {
                            statusMessage = "Јавно складиште из апликације није пронађено."
                        } else {
                            onReplaceStorage(packagedStorage)
                            editingEntryId = null
                            detailEntryId = null
                            detailOldWordId = null
                            currentScreen = AppScreen.Storage
                            statusMessage =
                                "Лично складиште је преписано јавним. Туђица: " +
                                    "${packagedStorage.entries.size}; старих речи: " +
                                    "${packagedStorage.oldWords.size}."
                        }
                    },
                    onAddNewWordsFromPublicStorage = {
                        val packagedStorage = onLoadPackagedDatabase()
                        if (packagedStorage.entries.isEmpty() && packagedStorage.oldWords.isEmpty()) {
                            statusMessage = "Јавно складиште из апликације није пронађено."
                        } else {
                            val (mergedEntries, addedEntriesCount) =
                                entries.withOnlyNewForeignWordsFrom(packagedStorage.entries)
                            val (mergedOldWords, addedOldWordsCount) =
                                oldWords.withOnlyNewOldWordsFrom(packagedStorage.oldWords)
                            if (addedEntriesCount == 0 && addedOldWordsCount == 0) {
                                statusMessage = "Нема нових речи за додавање из јавног складишта."
                            } else {
                                onReplaceStorage(
                                    DictionaryStorage(
                                        entries = mergedEntries,
                                        oldWords = mergedOldWords
                                    )
                                )
                                editingEntryId = null
                                detailEntryId = null
                                detailOldWordId = null
                                currentScreen = AppScreen.Storage
                                statusMessage =
                                    "Додато је $addedEntriesCount нових туђица и " +
                                        "$addedOldWordsCount старих речи из јавног складишта."
                            }
                        }
                    }
                )

                AppScreen.Settings -> SettingsScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    settings = displaySettings,
                    statusMessage = statusMessage,
                    onApplySettings = { settings -> applyDisplaySettings(settings) }
                )

                AppScreen.Acknowledgements -> AcknowledgementsScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )

                AppScreen.Idea -> IdeaScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )

                AppScreen.Notifications -> NotificationsScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    repository = notificationsRepository
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
}
