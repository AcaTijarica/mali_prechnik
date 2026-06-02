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
            proposalRepository = FirestoreProposalRepository(LocalContext.current),
            publicStorageSizeBytes = 0L,
            publicStorageUpdatedMillis = 0L,
            onInsertEntry = {},
            onUpdateEntry = {},
            onDeleteEntry = {},
            onReplaceEntries = {},
            onExportDatabase = { _, _ -> },
            onImportDatabase = { emptyList() },
            onLoadPackagedDatabase = { emptyList() }
        )
    }
}
