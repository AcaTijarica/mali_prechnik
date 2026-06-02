package rs.maliprecnik.app

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import java.security.MessageDigest

private const val COLLECTION_PROPOSAL_GROUPS = "proposal_groups"
private const val COLLECTION_OPTIONS = "options"
private const val COLLECTION_VOTES = "votes"
private const val COLLECTION_REPORTS = "reports"
private const val PREFERENCES_BLOCKED_AUTHORS = "blocked_proposal_authors"
private const val KEY_BLOCKED_AUTHORS = "blocked_authors"

data class VotingProposalGroup(
    val id: String,
    val foreignWord: String,
    val firstLetter: String,
    val proposals: List<VotingProposalOption>
)

data class VotingProposalOption(
    val id: String,
    val proposalText: String,
    val votesCount: Long,
    val createdBy: String
)

/**
 * Танак слој преко Firestore-а.
 *
 * Када `google-services.json` још није додат, репозиторијум остаје искључен и
 * враћа јасну поруку уместо да апликација падне при покретању.
 */
class FirestoreProposalRepository(context: Context) {
    private val appContext = context.applicationContext

    val isConfigured: Boolean
        get() = firebaseAppOrNull() != null

    fun loadVotingProposals(
        onSuccess: (List<VotingProposalGroup>) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val firestore = firestoreOrNull() ?: run {
            onFailure(missingFirebaseConfigurationError())
            return
        }

        firestore.collection(COLLECTION_PROPOSAL_GROUPS)
            .whereEqualTo("status", "voting")
            .get()
            .addOnSuccessListener { groupsSnapshot ->
                val groupDocuments = groupsSnapshot.documents
                if (groupDocuments.isEmpty()) {
                    onSuccess(emptyList())
                    return@addOnSuccessListener
                }

                val optionTasks = groupDocuments.map { group ->
                    group.reference.collection(COLLECTION_OPTIONS)
                        .whereEqualTo("status", "approved")
                        .get()
                }

                Tasks.whenAllSuccess<QuerySnapshot>(optionTasks)
                    .addOnSuccessListener { optionSnapshots ->
                        val blockedAuthors = blockedAuthorIds()
                        val groups = groupDocuments.mapIndexedNotNull { index, groupDocument ->
                            groupFromSnapshots(groupDocument, optionSnapshots[index], blockedAuthors)
                        }.sortedWith { first, second ->
                            serbianCollator.compare(first.foreignWord, second.foreignWord)
                        }
                        onSuccess(groups)
                    }
                    .addOnFailureListener(onFailure)
            }
            .addOnFailureListener(onFailure)
    }

    fun blockAuthor(authorId: String) {
        val cleanedAuthorId = authorId.trim()
        if (cleanedAuthorId.isBlank()) return

        val preferences = appContext.getSharedPreferences(PREFERENCES_BLOCKED_AUTHORS, Context.MODE_PRIVATE)
        val blockedAuthors = preferences.getStringSet(KEY_BLOCKED_AUTHORS, emptySet()).orEmpty().toMutableSet()
        blockedAuthors.add(cleanedAuthorId)
        preferences.edit().putStringSet(KEY_BLOCKED_AUTHORS, blockedAuthors).apply()
    }

    fun submitProposal(
        entry: DictionaryEntry,
        type: String,
        proposalText: String = proposalTextFromEntry(entry),
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val cleanedProposalText = proposalText.trim()
        if (cleanedProposalText.isBlank()) {
            onFailure(IllegalArgumentException("Потребно је унети текст предлога."))
            return
        }

        val normalizedForeignWord = normalizeWord(entry.foreignWord)
        if (normalizedForeignWord.isBlank()) {
            onFailure(IllegalArgumentException("Потребно је унети туђицу."))
            return
        }

        val firestore = firestoreOrNull() ?: run {
            onFailure(missingFirebaseConfigurationError())
            return
        }
        ensureSignedIn(
            onSuccess = { userId ->
                val groupId = stableDocumentId(normalizedForeignWord)
                val groupRef = firestore.collection(COLLECTION_PROPOSAL_GROUPS).document(groupId)
                val optionId = stableDocumentId("$normalizedForeignWord|${cleanedProposalText.lowercase()}")
                val optionRef = groupRef.collection(COLLECTION_OPTIONS).document(optionId)

                firestore.runTransaction { transaction ->
                    val groupSnapshot = transaction.get(groupRef)
                    val optionSnapshot = transaction.get(optionRef)

                    if (!groupSnapshot.exists()) {
                        transaction.set(
                            groupRef,
                            mapOf(
                                "foreign_word" to entry.foreignWord.trim(),
                                "normalized_foreign_word" to normalizedForeignWord,
                                "first_letter" to initialLetter(entry.foreignWord),
                                "origin" to entry.origin.trim(),
                                "addendum" to entry.addendum.trim(),
                                "type" to type,
                                "proposal_format" to "text",
                                "status" to "pending",
                                "created_by" to userId,
                                "created_at" to FieldValue.serverTimestamp()
                            )
                        )
                    }

                    if (!optionSnapshot.exists()) {
                        transaction.set(
                            optionRef,
                            mapOf(
                                "proposal_text" to cleanedProposalText,
                                "type" to type,
                                "votes_count" to 0L,
                                "status" to "pending",
                                "created_by" to userId,
                                "created_at" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                    null
                }
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener(onFailure)
            },
            onFailure = onFailure
        )
    }

    fun voteForOption(
        groupId: String,
        optionId: String,
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val firestore = firestoreOrNull() ?: run {
            onFailure(missingFirebaseConfigurationError())
            return
        }
        ensureSignedIn(
            onSuccess = { userId ->
                val groupRef = firestore.collection(COLLECTION_PROPOSAL_GROUPS).document(groupId)
                val optionRef = groupRef.collection(COLLECTION_OPTIONS).document(optionId)
                val voteRef = groupRef.collection(COLLECTION_VOTES).document(userId)

                firestore.runTransaction { transaction ->
                    val voteSnapshot = transaction.get(voteRef)
                    if (voteSnapshot.exists()) {
                        error("Већ је забележен глас за ову туђицу.")
                    }
                    val optionSnapshot = transaction.get(optionRef)
                    if (!optionSnapshot.exists()) {
                        error("Одабрани предлог више не постоји.")
                    }
                    if (optionSnapshot.getString("status") != "approved") {
                        error("Одабрани предлог још није одобрен за гласање.")
                    }
                    val currentVotesCount = optionSnapshot.getLong("votes_count") ?: 0L

                    transaction.set(
                        voteRef,
                        mapOf(
                            "option_id" to optionId,
                            "user_id" to userId,
                            "created_at" to FieldValue.serverTimestamp()
                        )
                    )
                    transaction.update(optionRef, "votes_count", currentVotesCount + 1L)
                    null
                }.addOnSuccessListener {
                    onSuccess()
                }.addOnFailureListener(onFailure)
            },
            onFailure = onFailure
        )
    }

    fun reportOption(
        groupId: String,
        optionId: String,
        reportedAuthorId: String,
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val firestore = firestoreOrNull() ?: run {
            onFailure(missingFirebaseConfigurationError())
            return
        }
        val cleanedReportedAuthorId = reportedAuthorId.trim()
        if (cleanedReportedAuthorId.isBlank()) {
            onFailure(IllegalArgumentException("Није познат предлагач овог предлога."))
            return
        }
        ensureSignedIn(
            onSuccess = { userId ->
                val reportRef = firestore.collection(COLLECTION_PROPOSAL_GROUPS)
                    .document(groupId)
                    .collection(COLLECTION_OPTIONS)
                    .document(optionId)
                    .collection(COLLECTION_REPORTS)
                    .document(userId)

                firestore.runTransaction { transaction ->
                    if (transaction.get(reportRef).exists()) {
                        error("Овај предлог је већ пријављен са овог уређаја.")
                    }
                    transaction.set(
                        reportRef,
                        mapOf(
                            "user_id" to userId,
                            "reported_author_id" to cleanedReportedAuthorId,
                            "reason" to "Корисник је пријавио предлог и предлагача као неумесне или неприхватљиве.",
                            "created_at" to FieldValue.serverTimestamp()
                        )
                    )
                    null
                }.addOnSuccessListener {
                    onSuccess()
                }.addOnFailureListener(onFailure)
            },
            onFailure = onFailure
        )
    }

    private fun ensureSignedIn(
        onSuccess: (String) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val auth = authOrNull() ?: run {
            onFailure(missingFirebaseConfigurationError())
            return
        }
        auth.currentUser?.let { user ->
            onSuccess(user.uid)
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                val userId = result.user?.uid
                if (userId == null) {
                    onFailure(IllegalStateException("Firebase није вратио кориснички id."))
                } else {
                    onSuccess(userId)
                }
            }
            .addOnFailureListener(onFailure)
    }

    private fun groupFromSnapshots(
        groupDocument: DocumentSnapshot,
        optionsSnapshot: QuerySnapshot,
        blockedAuthors: Set<String>
    ): VotingProposalGroup? {
        val foreignWord = groupDocument.getString("foreign_word").orEmpty().trim()
        if (foreignWord.isBlank()) return null

        val proposals = optionsSnapshot.documents.mapNotNull { optionDocument ->
            val proposalText = optionDocument.getString("proposal_text").orEmpty().trim()
                .ifBlank { legacyProposalText(optionDocument) }
            val createdBy = optionDocument.getString("created_by").orEmpty().trim()
            if (proposalText.isBlank()) {
                null
            } else if (createdBy.isNotBlank() && createdBy in blockedAuthors) {
                null
            } else {
                VotingProposalOption(
                    id = optionDocument.id,
                    proposalText = proposalText,
                    votesCount = optionDocument.getLong("votes_count") ?: 0L,
                    createdBy = createdBy
                )
            }
        }.sortedWith { first, second ->
            compareByVotesThenText(first, second)
        }

        if (proposals.isEmpty()) return null

        return VotingProposalGroup(
            id = groupDocument.id,
            foreignWord = foreignWord,
            firstLetter = groupDocument.getString("first_letter") ?: initialLetter(foreignWord),
            proposals = proposals
        )
    }

    private fun compareByVotesThenText(
        first: VotingProposalOption,
        second: VotingProposalOption
    ): Int {
        val voteCompare = second.votesCount.compareTo(first.votesCount)
        return if (voteCompare != 0) {
            voteCompare
        } else {
            serbianCollator.compare(first.proposalText, second.proposalText)
        }
    }

    private fun firestoreOrNull(): FirebaseFirestore? =
        firebaseAppOrNull()?.let { app -> FirebaseFirestore.getInstance(app) }

    private fun authOrNull(): FirebaseAuth? =
        firebaseAppOrNull()?.let { app -> FirebaseAuth.getInstance(app) }

    private fun blockedAuthorIds(): Set<String> =
        appContext
            .getSharedPreferences(PREFERENCES_BLOCKED_AUTHORS, Context.MODE_PRIVATE)
            .getStringSet(KEY_BLOCKED_AUTHORS, emptySet())
            .orEmpty()
            .filterTo(mutableSetOf()) { it.isNotBlank() }

    private fun firebaseAppOrNull(): FirebaseApp? {
        FirebaseApp.getApps(appContext).firstOrNull()?.let { return it }
        return runCatching { FirebaseApp.initializeApp(appContext) }.getOrNull()
    }
}

fun proposalTextFromEntry(entry: DictionaryEntry): String {
    val lines = mutableListOf(
        "Туђица: ${entry.foreignWord.trim()}",
        "Порекло: ${entry.origin.trim()}"
    )
    entry.options.forEach { option ->
        val replacementWord = option.replacementWord.trim()
        if (replacementWord.isNotBlank()) {
            val explanation = option.explanation.trim()
            lines += if (explanation.isBlank()) {
                "Српскословенска реч: $replacementWord"
            } else {
                "Српскословенска реч: $replacementWord - $explanation"
            }
        }
    }
    lines += "Додатак: ${entry.addendum.trim()}"
    return lines.joinToString("\n")
}

private fun legacyProposalText(optionDocument: DocumentSnapshot): String {
    val replacementWord = optionDocument.getString("replacement_word").orEmpty().trim()
    if (replacementWord.isBlank()) return ""

    val explanation = optionDocument.getString("explanation").orEmpty().trim()
    return if (explanation.isBlank()) {
        "Српскословенска реч: $replacementWord"
    } else {
        "Српскословенска реч: $replacementWord - $explanation"
    }
}

private fun missingFirebaseConfigurationError(): IllegalStateException =
    IllegalStateException(
        "Firebase није подешен. Додај app/google-services.json из Firebase конзоле."
    )

fun proposalErrorMessage(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        message.contains("CONFIGURATION_NOT_FOUND", ignoreCase = true) ->
            "Firebase Authentication није довршен: у Firebase Console укључи Authentication > Sign-in method > Anonymous."
        message.contains("PERMISSION_DENIED", ignoreCase = true) ->
            "Firestore правила не дозвољавају овај упис. Провери да су објављена правила из database/firestore.rules."
        message.isNotBlank() -> message
        else -> "Онлајн предлог није могао бити обрађен."
    }
}

private fun stableDocumentId(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
