package vn.nghetruyen.app.ui.components

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import vn.nghetruyen.app.audio.AudioDirectionPreferences

/** Independent opt-in switches for the new AI sound layers. Both are OFF by default. */
@Composable
fun AudioDirectionLayerSwitches(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember(context) { AudioDirectionPreferences(context) }
    var snapshot by remember(preferences) { mutableStateOf(preferences.snapshot()) }

    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            snapshot = preferences.snapshot()
        }
        preferences.addChangeListener(listener)
        onDispose { preferences.removeChangeListener(listener) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AudioLayerSwitchRow(
            title = "Âm thanh môi trường AI",
            description = "Ambience kéo dài theo khoảng UNIT. Mặc định tắt.",
            checked = snapshot.ambienceEnabled,
            onCheckedChange = preferences::setAmbienceEnabled,
        )
        AudioLayerSwitchRow(
            title = "Hiệu ứng âm thanh AI",
            description = "SFX one-shot tại UNIT do AI chọn. Mặc định tắt.",
            checked = snapshot.soundEffectsEnabled,
            onCheckedChange = preferences::setSoundEffectsEnabled,
        )
        Text(
            text = "Phân loại tệp trong thư viện nhạc bằng tag type:ambience hoặc type:sfx. Tệp không có marker vẫn là MUSIC.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun AudioLayerSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
