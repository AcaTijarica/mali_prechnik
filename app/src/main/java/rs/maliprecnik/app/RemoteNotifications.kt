package rs.maliprecnik.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val COLLECTION_NOTIFICATIONS = "notifications"

data class RemoteNotificationArticle(
    val id: String,
    val title: String,
    val author: String,
    val body: String,
    val publishedAtMillis: Long
)

/**
 * Чита уредничка обавештења из Firestore-а.
 *
 * Android апликација има само читање `status = published` докумената. Додавање
 * и измена обавештења раде се уредничким Python скриптама преко service account
 * кључа, па се не излажу корисницима апликације.
 */
class FirestoreNotificationsRepository(context: Context) {
    private val appContext = context.applicationContext

    fun loadNotifications(
        onSuccess: (List<RemoteNotificationArticle>) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val firestore = firestoreOrNull() ?: run {
            onFailure(missingFirebaseConfigurationError())
            return
        }

        firestore.collection(COLLECTION_NOTIFICATIONS)
            .whereEqualTo("status", "published")
            .get()
            .addOnSuccessListener { snapshot ->
                val notifications = snapshot.documents
                    .mapNotNull { document -> document.toRemoteNotificationArticle() }
                    .sortedWith(
                        compareByDescending<RemoteNotificationArticle> { it.publishedAtMillis }
                            .thenBy { it.title }
                    )
                onSuccess(notifications)
            }
            .addOnFailureListener(onFailure)
    }

    private fun firestoreOrNull(): FirebaseFirestore? =
        firebaseAppOrNull()?.let { app -> FirebaseFirestore.getInstance(app) }

    private fun firebaseAppOrNull(): FirebaseApp? {
        FirebaseApp.getApps(appContext).firstOrNull()?.let { return it }
        return runCatching { FirebaseApp.initializeApp(appContext) }.getOrNull()
    }
}

fun notificationErrorMessage(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        message.contains("PERMISSION_DENIED", ignoreCase = true) ->
            "Firestore правила не дозвољавају читање обавештења. Провери да су објављена правила из database/firestore.rules."
        message.isNotBlank() -> message
        else -> "Обавештења нису могла бити учитана."
    }
}

fun formatNotificationDate(millis: Long): String {
    if (millis <= 0L) return "Датум није наведен"
    return SimpleDateFormat("dd.MM.yyyy.", Locale("sr", "RS")).format(Date(millis))
}

private fun missingFirebaseConfigurationError(): IllegalStateException =
    IllegalStateException(
        "Firebase није подешен. Додај app/google-services.json из Firebase конзоле."
    )

private fun DocumentSnapshot.toRemoteNotificationArticle(): RemoteNotificationArticle? {
    val title = getString("title").orEmpty().trim()
    val body = getString("body").orEmpty().trim()
    if (title.isBlank() || body.isBlank()) return null

    return RemoteNotificationArticle(
        id = id,
        title = title,
        author = getString("author").orEmpty().trim(),
        body = body,
        publishedAtMillis = (get("published_at") as? Timestamp)?.toDate()?.time ?: 0L
    )
}
