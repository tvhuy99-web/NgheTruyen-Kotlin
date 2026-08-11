package vn.nghetruyen.app.ai

import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.app.sourceplatform.SourceDiagnosticRuntime

/** Production-facing online AI surface for translation and VietPhrase. */
class OnlineTextAiServices(
    settingsRepository: SettingsRepository,
    credentialStore: AiCredentialStore,
    requestGovernor: AiRequestGovernor,
    libraryRepository: LibraryRepository,
    diagnostics: SourceDiagnosticRuntime? = null,
) : TranslationEngine, VietPhraseImprovementEngine {
    private val delegate = OnlineAiServices(
        settingsRepository = settingsRepository,
        credentialStore = credentialStore,
        requestGovernor = requestGovernor,
        libraryRepository = libraryRepository,
        diagnostics = diagnostics,
    )

    override suspend fun translate(request: TranslationRequest): AppResult<String> = delegate.translate(request)

    override suspend fun improveVietPhrase(
        request: VietPhraseImprovementRequest,
    ): AppResult<List<VietPhraseReplacementSuggestion>> = delegate.improveVietPhrase(request)

    suspend fun listModels(
        provider: AiProvider,
        endpoint: String,
        apiKeyOverride: String? = null,
    ): AppResult<List<String>> = delegate.listModels(provider, endpoint, apiKeyOverride)

    suspend fun listGeminiModels(): AppResult<List<String>> = delegate.listGeminiModels()
}
