package rs.maliprecnik.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Горња трака са насловом тренутног екрана и дугметом за леви мени. */
@Composable
fun PrechnikTopBar(
    title: String,
    onMenuClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onMenuClick) {
                Text(
                    text = "☰",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Леви мени. Радни екрани су на врху, а информативне странице при дну. */
@Composable
fun PrechnikDrawer(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit
) {
    ModalDrawerSheet(
        modifier = Modifier.fillMaxWidth(0.6f)
    ) {
        Text(
            text = "Мали пречник",
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        HorizontalDivider()
        DrawerItem(
            label = "Претрага",
            selected = currentScreen == AppScreen.Search,
            onClick = { onNavigate(AppScreen.Search) }
        )
        DrawerItem(
            label = "Туђице",
            selected = currentScreen == AppScreen.ForeignWords || currentScreen == AppScreen.ForeignWord,
            onClick = { onNavigate(AppScreen.ForeignWords) }
        )
        DrawerItem(
            label = "Старе речи",
            selected = currentScreen == AppScreen.OldWords || currentScreen == AppScreen.OldWord,
            onClick = { onNavigate(AppScreen.OldWords) }
        )
        DrawerItem(
            label = "Додај туђицу",
            selected = currentScreen == AppScreen.AddWord,
            onClick = { onNavigate(AppScreen.AddWord) }
        )
        DrawerItem(
            label = "Додај стару реч",
            selected = currentScreen == AppScreen.AddOldWord,
            onClick = { onNavigate(AppScreen.AddOldWord) }
        )
        DrawerItem(
            label = "Гласање",
            selected = currentScreen == AppScreen.Voting,
            onClick = { onNavigate(AppScreen.Voting) }
        )
        DrawerItem(
            label = "Складиште",
            selected = currentScreen == AppScreen.Storage,
            onClick = { onNavigate(AppScreen.Storage) }
        )
        DrawerItem(
            label = "Подешавања",
            selected = currentScreen == AppScreen.Settings,
            onClick = { onNavigate(AppScreen.Settings) }
        )
        HorizontalDivider()
        DrawerItem(
            label = "Захвалница",
            selected = currentScreen == AppScreen.Acknowledgements,
            onClick = { onNavigate(AppScreen.Acknowledgements) }
        )
        DrawerItem(
            label = "Замисао",
            selected = currentScreen == AppScreen.Idea,
            onClick = { onNavigate(AppScreen.Idea) }
        )
        DrawerItem(
            label = "Обавештења",
            selected = currentScreen == AppScreen.Notifications,
            onClick = { onNavigate(AppScreen.Notifications) }
        )
        DrawerItem(
            label = "Правила и приватност",
            selected = currentScreen == AppScreen.PrivacyRules,
            onClick = { onNavigate(AppScreen.PrivacyRules) }
        )
    }
}

@Composable
private fun DrawerItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .height(40.dp)
    )
}
