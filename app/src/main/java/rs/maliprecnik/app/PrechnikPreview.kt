package rs.maliprecnik.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview

/** Android Studio preview; не убацује никакве пример-уносе у стварну базу. */
@Preview(showBackground = true)
@Composable
fun PrechnikPreview() {
    PrechnikTheme {
        PrechnikScreen(
            entries = emptyList(),
            oldWords = emptyList(),
            proposalRepository = FirestoreProposalRepository(LocalContext.current),
            notificationsRepository = FirestoreNotificationsRepository(LocalContext.current),
            publicStorageSizeBytes = 0L,
            publicStorageUpdatedMillis = 0L,
            onInsertEntry = {},
            onInsertOldWord = {},
            onUpdateEntry = {},
            onUpdateOldWord = {},
            onDeleteEntry = {},
            onDeleteOldWord = {},
            onReplaceStorage = {},
            onExportDatabase = { _, _ -> },
            onImportDatabase = { DictionaryStorage() },
            onLoadPackagedDatabase = { DictionaryStorage() }
        )
    }
}
