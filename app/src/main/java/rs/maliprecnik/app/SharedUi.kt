package rs.maliprecnik.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Приказ за случај да је унос обрисан док је екран још покушавао да га отвори. */
@Composable
fun MissingEntryScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusText("Овај унос више не постоји у бази.")
        OutlinedButton(
            onClick = onBack,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Назад")
        }
    }
}

/** Мала статусна трака која се појављује при врху већине екрана. */
@Composable
fun StatusText(message: String) {
    if (message.isBlank()) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/** Један именован ред детаља: наслов поља и форматирани текст испод њега. */
@Composable
fun DetailLine(label: String, value: String) {
    if (value.isBlank()) return

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = formattedAnnotatedString(value),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
