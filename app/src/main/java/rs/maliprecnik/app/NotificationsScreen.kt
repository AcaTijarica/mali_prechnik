package rs.maliprecnik.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun NotificationsScreen(
    modifier: Modifier = Modifier,
    repository: FirestoreNotificationsRepository
) {
    var notifications by remember { mutableStateOf(emptyList<RemoteNotificationArticle>()) }
    var message by rememberSaveable { mutableStateOf("Учитавање обавештења...") }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var selectedNotificationId by rememberSaveable { mutableStateOf<String?>(null) }

    fun loadNotifications() {
        isLoading = true
        repository.loadNotifications(
            onSuccess = { loadedNotifications ->
                notifications = loadedNotifications
                isLoading = false
                message = if (loadedNotifications.isEmpty()) {
                    "Тренутно нема објављених обавештења."
                } else {
                    "Обавештења су учитана. Број обавештења: ${loadedNotifications.size}."
                }
            },
            onFailure = { error ->
                isLoading = false
                message = notificationErrorMessage(error)
            }
        )
    }

    LaunchedEffect(repository) {
        loadNotifications()
    }

    val selectedNotification = notifications.firstOrNull { it.id == selectedNotificationId }
    BackHandler(enabled = selectedNotification != null) {
        selectedNotificationId = null
    }

    if (selectedNotification != null) {
        NotificationDetailScreen(
            modifier = modifier,
            notification = selectedNotification,
            onBack = { selectedNotificationId = null }
        )
    } else {
        NotificationListScreen(
            modifier = modifier,
            notifications = notifications,
            message = message,
            isLoading = isLoading,
            onRefresh = { loadNotifications() },
            onOpenNotification = { selectedNotificationId = it.id }
        )
    }
}

@Composable
private fun NotificationListScreen(
    modifier: Modifier,
    notifications: List<RemoteNotificationArticle>,
    message: String,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onOpenNotification: (RemoteNotificationArticle) -> Unit
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            StatusText(message)
        }
        item {
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isLoading) "Учитавање..." else "Освежи обавештења")
            }
        }
        items(notifications, key = { it.id }) { notification ->
            NotificationPreviewCard(
                notification = notification,
                onOpen = { onOpenNotification(notification) }
            )
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NotificationPreviewCard(
    notification: RemoteNotificationArticle,
    onOpen: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = notification.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            NotificationMetaRow(notification)
            ClickableFormattedText(
                text = formattedAnnotatedString(notification.body),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Прикажи више")
            }
        }
    }
}

@Composable
private fun NotificationDetailScreen(
    modifier: Modifier,
    notification: RemoteNotificationArticle,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Назад")
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = notification.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    NotificationMetaRow(notification)
                    ClickableFormattedText(
                        text = formattedAnnotatedString(notification.body),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun ClickableFormattedText(
    text: androidx.compose.ui.text.AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val uriHandler = LocalUriHandler.current
    ClickableText(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        onClick = { offset ->
            urlAnnotationsAt(text, offset).firstOrNull()?.let { annotation ->
                runCatching { uriHandler.openUri(annotation.item) }
            }
        }
    )
}

@Composable
private fun NotificationMetaRow(notification: RemoteNotificationArticle) {
    val authorText = notification.author.ifBlank { "Уредништво" }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = authorText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "• ${formatNotificationDate(notification.publishedAtMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
