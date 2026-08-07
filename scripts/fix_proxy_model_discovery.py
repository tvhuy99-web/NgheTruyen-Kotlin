from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch anchor in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# OpenAI-compatible model discovery must match the XPK behavior: API key is optional.
replace_once(
    "app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt",
    '''        val apiKey = apiKeyOverride?.trim()?.takeIf(String::isNotBlank)\n            ?: credentialStore.apiKey(provider)\n            ?: return@withContext failure("AI_KEY_MISSING", "Chưa lưu API key cho ${providerLabel(provider)}.")\n        val request = when (provider) {\n            AiProvider.GEMINI -> Request.Builder()\n                .url("$GEMINI_API_BASE/models?pageSize=100")\n                .header("Accept", "application/json")\n                .header("x-goog-api-key", apiKey)\n                .get()\n                .build()\n''',
    '''        val apiKey = apiKeyOverride?.trim()?.takeIf(String::isNotBlank)\n            ?: credentialStore.apiKey(provider)?.trim()?.takeIf(String::isNotBlank)\n        val request = when (provider) {\n            AiProvider.GEMINI -> {\n                val geminiKey = apiKey\n                    ?: return@withContext failure("AI_KEY_MISSING", "Chưa lưu API key cho ${providerLabel(provider)}.")\n                Request.Builder()\n                    .url("$GEMINI_API_BASE/models?pageSize=100")\n                    .header("Accept", "application/json")\n                    .header("x-goog-api-key", geminiKey)\n                    .get()\n                    .build()\n            }\n''',
)
replace_once(
    "app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt",
    '''                Request.Builder()\n                    .url("$base/models")\n                    .header("Accept", "application/json")\n                    .header("Authorization", "Bearer $apiKey")\n                    .get()\n                    .build()\n''',
    '''                Request.Builder()\n                    .url("$base/models")\n                    .header("Accept", "application/json")\n                    .apply {\n                        apiKey?.let { header("Authorization", "Bearer $it") }\n                    }\n                    .get()\n                    .build()\n''',
)

# Keep discovery outcome in UI state so a modal dialog can show it even when the global snackbar clears message.
replace_once(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    '''    val aiAvailableModels: List<String> = emptyList(),\n    val aiModelDiscoveryBusy: Boolean = false,\n    val readerCacheLimitMiB: Int = 64,\n''',
    '''    val aiAvailableModels: List<String> = emptyList(),\n    val aiModelDiscoveryBusy: Boolean = false,\n    val aiModelDiscoveryStatus: String = "",\n    val readerCacheLimitMiB: Int = 64,\n''',
)

vm = Path("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt")
text = vm.read_text(encoding="utf-8")
old = 'mutableState.update { it.copy(aiModelDiscoveryBusy = true, aiAvailableModels = emptyList(), message = "Đang tải danh sách model…") }'
new = 'mutableState.update { it.copy(aiModelDiscoveryBusy = true, aiAvailableModels = emptyList(), aiModelDiscoveryStatus = "Đang tải danh sách model…", message = "Đang tải danh sách model…") }'
if old not in text:
    raise SystemExit("missing discovery start state")
text = text.replace(old, new, 1)
old_failure = 'it.copy(aiModelDiscoveryBusy = false, aiAvailableModels = emptyList(), message = result.message)'
if old_failure not in text:
    raise SystemExit("missing discovery failure state")
text = text.replace(old_failure, 'it.copy(aiModelDiscoveryBusy = false, aiAvailableModels = emptyList(), aiModelDiscoveryStatus = result.message, message = result.message)', 1)
old_success = '''                    it.copy(\n                        aiModelDiscoveryBusy = false,\n                        aiAvailableModels = result.value,\n                        message = "Đã tải ${result.value.size} model.",\n                    )'''
new_success = '''                    it.copy(\n                        aiModelDiscoveryBusy = false,\n                        aiAvailableModels = result.value,\n                        aiModelDiscoveryStatus = "Đã tải ${result.value.size} model.",\n                        message = "Đã tải ${result.value.size} model.",\n                    )'''
if old_success not in text:
    raise SystemExit("missing discovery success state")
text = text.replace(old_success, new_success, 1)
vm.write_text(text, encoding="utf-8")

# Make the dialog consume both success and failure results.
replace_once(
    "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt",
    '''    LaunchedEffect(state.aiModelDiscoveryBusy, state.aiAvailableModels, modelPickerRequested) {\n        if (modelPickerRequested && !state.aiModelDiscoveryBusy && state.aiAvailableModels.isNotEmpty()) {\n            modelPickerRequested = false\n            modelPickerOpen = true\n        }\n    }\n''',
    '''    LaunchedEffect(\n        state.aiModelDiscoveryBusy,\n        state.aiAvailableModels,\n        state.aiModelDiscoveryStatus,\n        modelPickerRequested,\n    ) {\n        if (modelPickerRequested && !state.aiModelDiscoveryBusy) {\n            modelPickerRequested = false\n            if (state.aiAvailableModels.isNotEmpty()) {\n                validationMessage = ""\n                modelPickerOpen = true\n            } else if (state.aiModelDiscoveryStatus.isNotBlank()) {\n                validationMessage = state.aiModelDiscoveryStatus\n            }\n        }\n    }\n''',
)

# Bump debug versionCode so the stable-signed APK can update v30.
replace_once(
    "app/build.gradle.kts",
    "        versionCode = 30\n",
    "        versionCode = 31\n",
)

print("proxy model discovery parity fix applied")
