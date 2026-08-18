package vn.nghetruyen.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vn.nghetruyen.app.core.model.StorySummary

val ReferenceScreenBackground = Color(0xFFF0F2F5)
val ReferencePanelBackground = Color(0xFFFFFFFF)
val ReferenceDivider = Color(0xFFE5E5EA)
val ReferenceBlue = Color(0xFF0066CC)
val ReferenceGreen = Color(0xFF238636)
val ReferencePurple = Color(0xFF5856D6)
val ReferenceGray = Color(0xFF5F6368)
val ReferenceText = Color(0xFF111111)
val ReferenceSecondaryText = Color(0xFF555555)

private val HiddenSettingsHomeActions = setOf(
    "TTS & GIỌNG ĐỌC",
    "XUẤT SÁCH NÓI",
    "CHẨN ĐOÁN",
    "NHẠC NỀN & NHẠC CẢNH",
)

@Composable
fun ScreenHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = ReferenceText,
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
fun ReferenceActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accessibilityLabel: String = text,
    selected: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = 54.dp,
    selectedColor: Color = ReferenceGreen,
    normalColor: Color = ReferenceBlue,
    normalContentColor: Color = Color.White,
    roleValue: Role = Role.Button,
) {


    val isHiddenSettingsHomeAction =
        text in HiddenSettingsHomeActions &&
            minHeight == 50.dp &&
            normalColor == ReferencePanelBackground &&
            normalContentColor == ReferenceText
    if (isHiddenSettingsHomeAction) return

    val background = if (selected) selectedColor else normalColor
    val foreground = if (selected) Color.White else normalContentColor
    val spokenLabel = when {
        accessibilityLabel.trim() != text.trim() -> accessibilityLabel.trim()
        text == "ĐỌC NGAY" -> "Đọc truyện"
        text.startsWith("ĐỌC TIẾP") -> {
            val chapter = text.removePrefix("ĐỌC TIẾP").trim().replace('\n', ' ')
            if (chapter.isBlank()) "Đọc truyện tiếp" else "Đọc truyện tiếp. $chapter"
        }
        else -> text.trim().replace('\n', ' ')
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = background,
            contentColor = foreground,
            disabledContainerColor = ReferenceDivider,
            disabledContentColor = ReferenceSecondaryText,
        ),
        modifier = modifier
            .heightIn(min = minHeight)
            .semantics {
                role = roleValue
                if (roleValue == Role.Tab || selected) {
                    this.selected = selected
                }
                contentDescription = spokenLabel
            },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
fun ReferenceTabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accessibilityLabel: String = text,
    minHeight: Dp = 60.dp,
    unselectedColor: Color = ReferenceBlue,
    unselectedContentColor: Color = Color.White,
) {
    ReferenceActionButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        accessibilityLabel = accessibilityLabel,
        selected = selected,
        minHeight = minHeight,
        normalColor = unselectedColor,
        normalContentColor = unselectedContentColor,
        roleValue = Role.Tab,
    )
}

@Composable
fun LargeActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ReferenceActionButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minHeight = 56.dp,
    )
}

@Composable
fun StoryCard(story: StorySummary, onClick: () -> Unit) {
    val spoken = buildString {
        append(story.title)
        if (story.author.isNotBlank()) append(". Tác giả: ${story.author}")
        if (story.description.isNotBlank()) append(". ${story.description}")
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = ReferencePanelBackground),
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = spoken
            }
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(story.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ReferenceText)
            if (story.author.isNotBlank()) {
                Text(story.author, style = MaterialTheme.typography.bodyMedium, color = ReferenceSecondaryText)
            }
            // Source identity remains internal for routing/deduplication; cards never expose raw source ids.
            if (story.description.isNotBlank()) {
                Text(story.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, color = ReferenceSecondaryText)
            }
        }
    }
}

@Composable
fun LoadingRow(label: String = "Đang tải...") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(label)
    }
}
