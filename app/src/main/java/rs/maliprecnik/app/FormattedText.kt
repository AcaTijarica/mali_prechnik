package rs.maliprecnik.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

private data class FormattedSegment(
    val text: String,
    val styles: Set<String>
)

private const val URL_ANNOTATION_TAG = "URL"
private val UrlRegex = Regex("""https?://[^\s<>()]+""")

/**
 * Мали parser за једноставне ознаке у тексту:
 * `**подебљано**`, `_искошено_`, `*искошено*`, `__подвучено__`.
 *
 * У бази чувамо изворни текст са ознакама, а само га при приказу претварамо
 * у `AnnotatedString`, који Compose уме да нацрта са стиловима.
 */
fun formattedAnnotatedString(value: String): AnnotatedString =
    buildAnnotatedString {
        formattedSegments(value).forEach { segment ->
            val start = length
            append(segment.text)
            val end = length
            addStyle(
                SpanStyle(
                    fontWeight = if ("bold" in segment.styles) FontWeight.Bold else null,
                    fontStyle = if ("italic" in segment.styles) FontStyle.Italic else null,
                    textDecoration = if ("underline" in segment.styles) {
                        TextDecoration.Underline
                    } else {
                        null
                    }
                ),
                start,
                end
            )
        }
        addUrlAnnotations()
    }

fun urlAnnotationsAt(value: AnnotatedString, offset: Int): List<AnnotatedString.Range<String>> =
    value.getStringAnnotations(URL_ANNOTATION_TAG, offset, offset)

private fun formattedSegments(value: String): List<FormattedSegment> {
    val segments = mutableListOf<FormattedSegment>()
    val activeStyles = mutableSetOf<String>()
    val buffer = StringBuilder()
    var index = 0

    // Скупљамо обичан текст у баферу док не наиђемо на ознаку форматирања.
    fun flush() {
        if (buffer.isEmpty()) return
        segments += FormattedSegment(
            text = buffer.toString(),
            styles = activeStyles.toSet()
        )
        buffer.clear()
    }

    while (index < value.length) {
        val marker = matchingFormatMarker(value, index)
        if (marker == null) {
            buffer.append(value[index])
            index += 1
            continue
        }

        flush()
        if (marker.style in activeStyles) {
            activeStyles -= marker.style
        } else {
            activeStyles += marker.style
        }
        index += marker.token.length
    }

    flush()
    return segments
}

private data class FormatMarker(
    val token: String,
    val style: String
)

private fun matchingFormatMarker(value: String, index: Int): FormatMarker? =
    when {
        value.startsWith("__", index) -> FormatMarker("__", "underline")
        value.startsWith("**", index) -> FormatMarker("**", "bold")
        value[index] == '_' -> FormatMarker("_", "italic")
        value[index] == '*' -> FormatMarker("*", "italic")
        else -> null
    }

private fun AnnotatedString.Builder.addUrlAnnotations() {
    UrlRegex.findAll(toString()).forEach { match ->
        val cleanedUrl = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
        if (cleanedUrl.isBlank()) return@forEach
        val start = match.range.first
        val end = start + cleanedUrl.length
        addStringAnnotation(
            tag = URL_ANNOTATION_TAG,
            annotation = cleanedUrl,
            start = start,
            end = end
        )
        addStyle(
            style = SpanStyle(textDecoration = TextDecoration.Underline),
            start = start,
            end = end
        )
    }
}
