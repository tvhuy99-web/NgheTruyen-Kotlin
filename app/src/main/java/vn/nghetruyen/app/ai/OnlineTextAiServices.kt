package vn.nghetruyen.app.ai

import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.data.settings.SettingsRepository

/**
 * Production-facing online AI surface for translation and VietPhrase only.
 *
 * [OnlineAiServices] still contains deprecated paragraph-era narration methods for source/binary
 * compatibility, but they are deliberately hidden behind this private delegate. All live narration
 * is routed through [XpkNarrationAiServices] and [NarrationPlanCoordinator].
 */
class OnlineTextAiServices(
    settingsRepository: SettingsRepository,
    credentialStore: AiCredentialStore,
    requestGovernor: AiRequestGovernor,
    libraryRepository: LibraryRepository,
) : TranslationEngine, VietPhraseImprovementEngine {
    private val delegate = OnlineAiServices(
        settingsRepository = settingsRepository,
        credentialStore = credentialStore,
        requestGovernor = requestGovernor,
        libraryRepository = libraryRepository,
    )

    override suspend fun translate(request: TranslationRequest): AppResult<String> =
        delegate.translate(request)

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
