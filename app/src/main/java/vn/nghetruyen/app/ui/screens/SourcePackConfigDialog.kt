package vn.nghetruyen.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import vn.nghetruyen.app.sourceplatform.SourcePackUiInfo

/** Compact native editor for vBook descriptor/legacy config. Secret values never enter UI state. */
@Composable
internal fun SourcePackConfigDialog(
    pack: SourcePackUiInfo,
    onSave: (Map<String, String>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var values by remember(pack.id, pack.configFields) {
        mutableStateOf(pack.configFields.associate { it.key to it.value })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CẤU HÌNH · ${pack.name}") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                pack.configFields.forEach { field ->
                    if (field.mode == "TOGGLE") {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(field.title)
                                if (field.subtitle.isNotBlank()) Text(field.subtitle)
                            }
                            Switch(
                                checked = values[field.key].orEmpty().toBoolean(),
                                onCheckedChange = { checked -> values = values + (field.key to checked.toString()) },
                            )
                        }
                    } else {
                        val helper = buildList {
                            if (field.subtitle.isNotBlank()) add(field.subtitle)
                            if (field.options.isNotEmpty()) add("Lựa chọn: ${field.options.joinToString()}")
                            if (field.sensitive && field.configured) add("Đã lưu an toàn; để trống nếu không muốn thay đổi.")
                            else if (field.sensitive) add("Giá trị này sẽ được mã hóa và không đưa vào bản sao lưu.")
                        }.joinToString(" · ")
                        OutlinedTextField(
                            value = values[field.key].orEmpty(),
                            onValueChange = { value -> values = values + (field.key to value.take(64 * 1024)) },
                            label = { Text(field.title) },
                            supportingText = helper.takeIf(String::isNotBlank)?.let { text -> ({ Text(text) }) },
                            placeholder = if (field.sensitive && field.configured) ({ Text("Đã lưu") }) else null,
                            visualTransformation = if (field.sensitive) PasswordVisualTransformation() else VisualTransformation.None,
                            singleLine = field.format != "MULTILINE",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                }
                TextButton(
                    onClick = { onReset(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("KHÔI PHỤC CẤU HÌNH MẶC ĐỊNH") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val changes = pack.configFields.mapNotNull { field ->
                    val value = values[field.key].orEmpty()
                    if (field.sensitive && value.isBlank()) null else field.key to value
                }.toMap(LinkedHashMap())
                onSave(changes)
                onDismiss()
            }) { Text("LƯU") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("HỦY") } },
    )
}
