#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def p(path): return ROOT / path
def read(path): return p(path).read_text(encoding='utf-8')
def write(path, text):
    p(path).parent.mkdir(parents=True, exist_ok=True)
    p(path).write_text(text, encoding='utf-8')
def rep(text, old, new, label):
    if old not in text: raise SystemExit(f'missing marker: {label}')
    return text.replace(old, new, 1)
def insert_before(text, marker, content, label):
    if marker not in text: raise SystemExit(f'missing marker: {label}')
    return text.replace(marker, content + marker, 1)
def replace_function(text, signature, replacement):
    start = text.find(signature)
    if start < 0: raise SystemExit(f'missing function: {signature}')
    brace = text.find('{', start)
    if brace < 0: raise SystemExit(f'missing brace: {signature}')
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == '{': depth += 1
        elif text[i] == '}':
            depth -= 1
            if depth == 0:
                return text[:start] + replacement + text[i+1:]
    raise SystemExit(f'unterminated function: {signature}')

# 1) Source pack-defined UI actions, backed by the existing sandbox runtime.
path = 'source-api/src/main/kotlin/vn/nghetruyen/source/api/SourceManifest.kt'
t = read(path)
t = rep(t,
'''enum class SourceActionName {
    HOME, GENRE, SEARCH, DETAIL, LATEST_CHAPTER, TOC_PAGES, TOC, CHAPTER, COMMENTS, SUGGESTIONS, LOGIN;

    val manifestKey: String get() = name.lowercase().replace("toc_pages", "tocPages")
}
''',
'''enum class SourceActionName {
    HOME, GENRE, SEARCH, DETAIL, LATEST_CHAPTER, TOC_PAGES, TOC, CHAPTER, COMMENTS, SUGGESTIONS, LOGIN, UI_ACTION;

    val manifestKey: String get() = when (this) {
        TOC_PAGES -> "tocPages"
        UI_ACTION -> "uiAction"
        else -> name.lowercase()
    }
}

enum class SourceUiActionContext { EXPLORE, STORY, READER }

data class SourceUiActionSpec(
    val id: String,
    val label: String,
    val contexts: Set<SourceUiActionContext>,
    val group: String = "",
    val order: Int = 0,
)
''', 'source action enum')
t = rep(t,
'''    val actions: Map<SourceActionName, SourceActionSpec>,
    val privacy: SourcePrivacyDisclosure = SourcePrivacyDisclosure(),
''',
'''    val actions: Map<SourceActionName, SourceActionSpec>,
    val uiActions: List<SourceUiActionSpec> = emptyList(),
    val privacy: SourcePrivacyDisclosure = SourcePrivacyDisclosure(),
''', 'manifest uiActions field')
t = rep(t,
'''        actions.values.forEach { action ->
            requireSafeRelativePath(action.entry)
            require(action.maxOutputBytes in 1024..4 * 1024 * 1024) { "SOURCE_ACTION_OUTPUT_LIMIT_INVALID" }
            action.timeoutMs?.let { require(it in 500..120_000) { "SOURCE_ACTION_TIMEOUT_INVALID" } }
        }
''',
'''        actions.values.forEach { action ->
            requireSafeRelativePath(action.entry)
            require(action.maxOutputBytes in 1024..4 * 1024 * 1024) { "SOURCE_ACTION_OUTPUT_LIMIT_INVALID" }
            action.timeoutMs?.let { require(it in 500..120_000) { "SOURCE_ACTION_TIMEOUT_INVALID" } }
        }
        require(uiActions.size <= 24) { "SOURCE_UI_ACTIONS_TOO_MANY" }
        require(uiActions.map { it.id }.distinct().size == uiActions.size) { "SOURCE_UI_ACTION_ID_DUPLICATE" }
        uiActions.forEach { item ->
            require(UI_ACTION_ID.matches(item.id)) { "SOURCE_UI_ACTION_ID_INVALID" }
            require(item.label.isNotBlank() && item.label.length <= 80) { "SOURCE_UI_ACTION_LABEL_INVALID" }
            require(item.contexts.isNotEmpty()) { "SOURCE_UI_ACTION_CONTEXT_REQUIRED" }
            require(item.group.length <= 40) { "SOURCE_UI_ACTION_GROUP_INVALID" }
            require(item.order in -1000..1000) { "SOURCE_UI_ACTION_ORDER_INVALID" }
        }
        require(uiActions.isEmpty() || SourceActionName.UI_ACTION in actions) { "SOURCE_UI_ACTION_HANDLER_MISSING" }
''', 'manifest validation')
t = rep(t,
'''        private val LOCALE_PATTERN = Regex("^[a-z]{2,3}(?:-[A-Z]{2})?$")
        private val REQUIRED_ACTIONS''',
'''        private val LOCALE_PATTERN = Regex("^[a-z]{2,3}(?:-[A-Z]{2})?$")
        private val UI_ACTION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val REQUIRED_ACTIONS''', 'ui action id regex')
write(path, t)

path = 'source-package/src/main/kotlin/vn/nghetruyen/source/packagekit/SourceManifestParser.kt'
t = read(path)
t = rep(t, 'import vn.nghetruyen.source.api.SourceRuntimePolicy\n', 'import vn.nghetruyen.source.api.SourceRuntimePolicy\nimport vn.nghetruyen.source.api.SourceUiActionContext\nimport vn.nghetruyen.source.api.SourceUiActionSpec\n', 'parser imports')
t = rep(t,
'''            actions = parseActions(root.requiredObj("actions")),
            privacy = parsePrivacy(root.obj("privacy")),
''',
'''            actions = parseActions(root.requiredObj("actions")),
            uiActions = parseUiActions(root.array("uiActions")),
            privacy = parsePrivacy(root.obj("privacy")),
''', 'parser ui actions call')
t = insert_before(t,
'''    private fun parsePrivacy(value: JsonValue.Obj?): SourcePrivacyDisclosure {''',
'''    private fun parseUiActions(value: JsonValue.Arr?): List<SourceUiActionSpec> = value?.values.orEmpty().map { item ->
        val obj = item as? JsonValue.Obj ?: error("SOURCE_UI_ACTION_INVALID")
        obj.requireOnly(UI_ACTION_KEYS, "uiActions")
        SourceUiActionSpec(
            id = obj.requiredString("id"),
            label = obj.requiredString("label"),
            contexts = obj.stringList("contexts").map { enumValue<SourceUiActionContext>(it, "uiActions.contexts") }.toSet(),
            group = obj.string("group").orEmpty(),
            order = obj.int("order") ?: 0,
        )
    }

''', 'parser ui action function')
t = rep(t,
'''        "urlPatterns", "origins", "redirectOrigins", "capabilities", "actions", "privacy", "fixtures",
''',
'''        "urlPatterns", "origins", "redirectOrigins", "capabilities", "actions", "uiActions", "privacy", "fixtures",
''', 'parser root keys')
t = rep(t,
'''    private val ACTION_KEYS = setOf("entry", "timeoutMs", "maxOutputBytes")
''',
'''    private val ACTION_KEYS = setOf("entry", "timeoutMs", "maxOutputBytes")
    private val UI_ACTION_KEYS = setOf("id", "label", "contexts", "group", "order")
''', 'parser ui keys')
write(path, t)

path = 'source-package/src/main/kotlin/vn/nghetruyen/source/packagekit/SourceManifestWriter.kt'
t = read(path)
t = rep(t,
'''        "privacy" to JsonValue.Obj(linkedMapOf(
''',
'''        "uiActions" to JsonValue.Arr(manifest.uiActions.map { action -> JsonValue.Obj(linkedMapOf(
            "id" to JsonValue.Str(action.id),
            "label" to JsonValue.Str(action.label),
            "contexts" to strings(action.contexts.map { it.name }.sorted()),
            "group" to JsonValue.Str(action.group),
            "order" to num(action.order),
        )) }),
        "privacy" to JsonValue.Obj(linkedMapOf(
''', 'writer ui actions')
write(path, t)

path = 'source-package/src/test/kotlin/vn/nghetruyen/source/packagekit/SourceManifestParserTest.kt'
t = read(path)
t = rep(t, 'import org.junit.Test\n', 'import org.junit.Test\nimport vn.nghetruyen.source.api.SourceUiActionContext\n', 'test import')
t = insert_before(t, '\n    @Test fun rejectsUnknownFields()', '''
    @Test fun parsesPluginUiActions() {
        val withUi = valid
            .replace("\\\"actions\\\":{", "\\\"uiActions\\\":[{\\\"id\\\":\\\"refresh-index\\\",\\\"label\\\":\\\"LÀM MỚI NGUỒN\\\",\\\"contexts\\\":[\\\"EXPLORE\\\",\\\"STORY\\\"],\\\"order\\\":20}],\\\"actions\\\":{\\\"uiAction\\\":{\\\"entry\\\":\\\"actions/ui-action.json\\\"},")
        val parsed = SourceManifestParser.parse(withUi.toByteArray())
        assertEquals("refresh-index", parsed.uiActions.single().id)
        assertEquals(setOf(SourceUiActionContext.EXPLORE, SourceUiActionContext.STORY), parsed.uiActions.single().contexts)
        val roundTrip = SourceManifestParser.parse(SourceManifestWriter.write(parsed))
        assertEquals(parsed.uiActions, roundTrip.uiActions)
    }
''', 'test insertion')
write(path, t)

path = 'app/src/main/java/vn/nghetruyen/app/sources/StorySource.kt'
t = read(path)
t = rep(t,
'''enum class SourceImplementationKind {
    BUILT_IN,
    HYBRID_PACK,
    SOURCE_PACK,
    VBOOK,
    NATIVE_LUA,
    PLACEHOLDER,
}
''',
'''enum class SourceImplementationKind {
    BUILT_IN,
    HYBRID_PACK,
    SOURCE_PACK,
    VBOOK,
    NATIVE_LUA,
    PLACEHOLDER,
}

enum class SourceUiSurface { EXPLORE, STORY, READER }

data class SourceUiActionDescriptor(
    val id: String,
    val label: String,
    val surfaces: Set<SourceUiSurface>,
    val group: String = "",
    val order: Int = 0,
)

data class SourceUiActionResult(
    val message: String = "",
    val openUrl: String? = null,
    val refresh: Boolean = false,
)
''', 'app ui action models')
t = rep(t,
'''    val supportsSuggestions: Boolean = false,
    val implementationKind: SourceImplementationKind = SourceImplementationKind.BUILT_IN,
''',
'''    val supportsSuggestions: Boolean = false,
    val implementationKind: SourceImplementationKind = SourceImplementationKind.BUILT_IN,
    val uiActions: List<SourceUiActionDescriptor> = emptyList(),
''', 'descriptor ui actions')
t = insert_before(t,
'''    suspend fun latestChapter(url: String): AppResult<ChapterSummary?>''',
'''    suspend fun runUiAction(
        actionId: String,
        surface: SourceUiSurface,
        currentUrl: String? = null,
        storyId: String? = null,
        chapterId: String? = null,
    ): AppResult<SourceUiActionResult> = AppResult.Failure(
        code = "SOURCE_UI_ACTION_UNSUPPORTED",
        message = "Nguồn này không có action giao diện tương ứng.",
    )

''', 'story source action method')
write(path, t)

path = 'app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePackStorySource.kt'
t = read(path)
t = rep(t, 'import vn.nghetruyen.app.sources.SourceImplementationKind\n', 'import vn.nghetruyen.app.sources.SourceImplementationKind\nimport vn.nghetruyen.app.sources.SourceUiActionDescriptor\nimport vn.nghetruyen.app.sources.SourceUiActionResult\nimport vn.nghetruyen.app.sources.SourceUiSurface\n', 'source pack app imports')
t = rep(t,
'''        implementationKind = if (bridgeActive) {
            SourceImplementationKind.HYBRID_PACK
        } else when (pack.manifest.runtime.mode) {
            SourceRuntimeMode.DECLARATIVE -> SourceImplementationKind.SOURCE_PACK
            SourceRuntimeMode.VBOOK_JS_COMPAT -> SourceImplementationKind.VBOOK
            SourceRuntimeMode.NATIVE_LUA_COMPAT -> SourceImplementationKind.NATIVE_LUA
        },
    )
''',
'''        implementationKind = if (bridgeActive) {
            SourceImplementationKind.HYBRID_PACK
        } else when (pack.manifest.runtime.mode) {
            SourceRuntimeMode.DECLARATIVE -> SourceImplementationKind.SOURCE_PACK
            SourceRuntimeMode.VBOOK_JS_COMPAT -> SourceImplementationKind.VBOOK
            SourceRuntimeMode.NATIVE_LUA_COMPAT -> SourceImplementationKind.NATIVE_LUA
        },
        uiActions = (builtInDelegate?.descriptor?.uiActions.orEmpty() + pack.manifest.uiActions.map { action ->
            SourceUiActionDescriptor(
                id = action.id,
                label = action.label,
                surfaces = action.contexts.map { SourceUiSurface.valueOf(it.name) }.toSet(),
                group = action.group,
                order = action.order,
            )
        }).distinctBy { it.id },
    )
''', 'pack descriptor ui actions')
t = insert_before(t,
'''    override suspend fun search(query: String, page: Int): AppResult<List<StorySummary>> {''',
'''    override suspend fun runUiAction(
        actionId: String,
        surface: SourceUiSurface,
        currentUrl: String?,
        storyId: String?,
        chapterId: String?,
    ): AppResult<SourceUiActionResult> = guarded {
        val action = descriptor.uiActions.firstOrNull { it.id == actionId && surface in it.surfaces }
            ?: return@guarded AppResult.Failure("SOURCE_UI_ACTION_NOT_FOUND", "Action giao diện không tồn tại ở màn hình này.")
        val value = execute(
            SourceActionName.UI_ACTION,
            JsonValue.Obj(linkedMapOf(
                "id" to JsonValue.Str(action.id),
                "context" to JsonValue.Str(surface.name),
                "url" to JsonValue.Str(currentUrl.orEmpty()),
                "storyId" to JsonValue.Str(storyId.orEmpty()),
                "chapterId" to JsonValue.Str(chapterId.orEmpty()),
            )),
        ) ?: return@guarded AppResult.Failure("SOURCE_UI_ACTION_HANDLER_MISSING", "Gói nguồn thiếu handler uiAction.")
        val outcome = when (value) {
            is JsonValue.Str -> SourceUiActionResult(message = value.value.take(1000))
            is JsonValue.Obj -> SourceUiActionResult(
                message = value.string("message").orEmpty().take(1000),
                openUrl = value.string("openUrl")?.take(4096),
                refresh = (value.values["refresh"] as? JsonValue.Bool)?.value ?: false,
            )
            JsonValue.Null -> SourceUiActionResult()
            else -> return@guarded typeFailure("Kết quả uiAction phải là string, object hoặc null.")
        }
        AppResult.Success(outcome)
    }

''', 'pack run ui action')
write(path, t)

# 2) Real global/common voice profiles with the seven XPK defaults and runtime fallback.
path = 'app/src/main/java/vn/nghetruyen/app/core/model/Models.kt'
t = read(path)
t = rep(t, '    val aliases: String = "",\n', '    val aliases: String = "",\n    val description: String = "",\n', 'voice draft description')
write(path, t)

write('app/src/main/java/vn/nghetruyen/app/core/model/GlobalVoiceProfiles.kt', '''package vn.nghetruyen.app.core.model

const val GLOBAL_VOICE_PROFILE_STORY_ID = "__global_voice_profiles__"

data class GlobalVoiceProfileSeed(
    val name: String,
    val description: String,
    val narrator: Boolean = false,
)

val DEFAULT_GLOBAL_VOICE_PROFILES: List<GlobalVoiceProfileSeed> = listOf(
    GlobalVoiceProfileSeed(
        name = "Người kể chuyện",
        description = "Dùng cho lời dẫn, miêu tả, chuyển cảnh, nội tâm, lời tự nhủ không phát thành tiếng, thông báo hệ thống và phần không xác định chắc chắn người nói.",
        narrator = true,
    ),
    GlobalVoiceProfileSeed("Nam thiếu niên", "Dùng cho lời thoại của nhân vật nam trẻ tuổi, thiếu niên, học sinh hoặc người có cách nói trẻ trung."),
    GlobalVoiceProfileSeed("Nữ thiếu niên", "Dùng cho lời thoại của nhân vật nữ trẻ tuổi, thiếu niên, học sinh hoặc người có cách nói trẻ trung."),
    GlobalVoiceProfileSeed("Nam trung niên", "Dùng cho lời thoại của nhân vật nam trưởng thành, trung niên hoặc người có cách nói chín chắn."),
    GlobalVoiceProfileSeed("Nữ trung niên", "Dùng cho lời thoại của nhân vật nữ trưởng thành, trung niên hoặc người có cách nói chín chắn."),
    GlobalVoiceProfileSeed("Nam cao tuổi", "Dùng cho lời thoại của nhân vật nam lớn tuổi, trưởng bối, ông lão hoặc người có cách nói già dặn."),
    GlobalVoiceProfileSeed("Nữ cao tuổi", "Dùng cho lời thoại của nhân vật nữ lớn tuổi, trưởng bối, bà lão hoặc người có cách nói già dặn."),
)
''')

path = 'app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt'
t = read(path)
t = rep(t, '    val aliasesCsv: String,\n    val enginePackage:', '    val aliasesCsv: String,\n    @ColumnInfo(defaultValue = "\'\'") val description: String = "",\n    val enginePackage:', 'entity description')
t = rep(t, '    version = 18,\n', '    version = 19,\n', 'db version')
t = insert_before(t,
'''        fun create(context: Context): AppDatabase = Room.databaseBuilder(''',
'''        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE voice_roles ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            }
        }

''', 'migration 18 19')
t = rep(t, '            MIGRATION_17_18,\n', '            MIGRATION_17_18,\n            MIGRATION_18_19,\n', 'register migration')
write(path, t)

path = 'app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt'
t = read(path)
t = rep(t, 'import vn.nghetruyen.app.core.model.ImportedBook\n', 'import vn.nghetruyen.app.core.model.ImportedBook\nimport vn.nghetruyen.app.core.model.DEFAULT_GLOBAL_VOICE_PROFILES\nimport vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID\n', 'repo global imports')
t = rep(t,
'''    suspend fun listVoiceRoles(storyId: String): List<VoiceRoleEntity> =
        db.voiceRoleDao().listForStory(storyId)

''',
'''    suspend fun listVoiceRoles(storyId: String): List<VoiceRoleEntity> =
        db.voiceRoleDao().listForStory(storyId)

    suspend fun listEffectiveVoiceRoles(storyId: String, includeGlobal: Boolean = true): List<VoiceRoleEntity> {
        val local = db.voiceRoleDao().listForStory(storyId).filter(VoiceRoleEntity::enabled)
        if (!includeGlobal || storyId == GLOBAL_VOICE_PROFILE_STORY_ID) return local
        val global = db.voiceRoleDao().listForStory(GLOBAL_VOICE_PROFILE_STORY_ID).filter(VoiceRoleEntity::enabled)
        if (local.isEmpty()) return global
        val overridden = local.map { it.roleName.trim().lowercase(Locale.ROOT) }.toSet()
        return local + global.filter { it.roleName.trim().lowercase(Locale.ROOT) !in overridden }
    }

    suspend fun ensureGlobalVoiceProfiles() {
        if (db.voiceRoleDao().listForStory(GLOBAL_VOICE_PROFILE_STORY_ID).isEmpty()) restoreGlobalVoiceProfiles()
    }

    suspend fun restoreGlobalVoiceProfiles(): List<VoiceRoleEntity> = db.withTransaction {
        val existing = db.voiceRoleDao().listForStory(GLOBAL_VOICE_PROFILE_STORY_ID)
        val byName = existing.associateBy { it.roleName.trim().lowercase(Locale.ROOT) }
        val defaultNames = DEFAULT_GLOBAL_VOICE_PROFILES.map { it.name.lowercase(Locale.ROOT) }.toSet()
        val now = System.currentTimeMillis()
        val defaults = DEFAULT_GLOBAL_VOICE_PROFILES.map { seed ->
            val old = byName[seed.name.lowercase(Locale.ROOT)]
            VoiceRoleEntity(
                id = old?.id ?: UUID.nameUUIDFromBytes("$GLOBAL_VOICE_PROFILE_STORY_ID\\u0000${seed.name.lowercase(Locale.ROOT)}".toByteArray()).toString(),
                storyId = GLOBAL_VOICE_PROFILE_STORY_ID,
                roleName = seed.name,
                aliasesCsv = old?.aliasesCsv.orEmpty(),
                description = seed.description,
                enginePackage = old?.enginePackage,
                voiceName = old?.voiceName,
                languageTag = old?.languageTag?.ifBlank { "vi-VN" } ?: "vi-VN",
                rate = old?.rate ?: 1f,
                pitch = old?.pitch ?: 1f,
                volume = old?.volume ?: 1f,
                expression = old?.expression ?: "NEUTRAL",
                expressionStrength = old?.expressionStrength ?: 0.5f,
                sonicSpeed = old?.sonicSpeed ?: 1f,
                sonicPitch = old?.sonicPitch ?: 1f,
                isNarrator = seed.narrator,
                enabled = true,
                updatedAt = now,
            )
        }
        val custom = existing.filter { it.roleName.trim().lowercase(Locale.ROOT) !in defaultNames }.take((10 - defaults.size).coerceAtLeast(0))
        db.voiceRoleDao().deleteForStory(GLOBAL_VOICE_PROFILE_STORY_ID)
        db.voiceRoleDao().upsertAll(defaults + custom)
        defaults + custom
    }

''', 'effective global roles')
t = rep(t,
'''        enabled: Boolean = true,
    ): Result<String> = runCatching {
''',
'''        enabled: Boolean = true,
        description: String = "",
    ): Result<String> = runCatching {
''', 'save role description param')
t = rep(t, '                aliasesCsv = aliasesCsv.trim().take(500),\n                enginePackage', '                aliasesCsv = aliasesCsv.trim().take(500),\n                description = description.trim().take(1000),\n                enginePackage', 'save entity description')
write(path, t)

path = 'app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt'
t = read(path)
t = rep(t, '            name("aliasesCsv").value(item.aliasesCsv)\n', '            name("aliasesCsv").value(item.aliasesCsv)\n            name("description").value(item.description)\n', 'backup voice description write')
t = rep(t, '            var id = ""; var storyId = ""; var roleName = ""; var aliases = ""\n', '            var id = ""; var storyId = ""; var roleName = ""; var aliases = ""; var description = ""\n', 'backup voice description var')
t = rep(t, '                "aliasesCsv" -> aliases = nextStringSafe("")\n', '                "aliasesCsv" -> aliases = nextStringSafe("")\n                "description" -> description = nextStringSafe("").take(1000)\n', 'backup voice description read')
t = rep(t, '                id = id, storyId = storyId, roleName = roleName, aliasesCsv = aliases,\n', '                id = id, storyId = storyId, roleName = roleName, aliasesCsv = aliases, description = description,\n', 'backup entity description')
write(path, t)

path = 'app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt'
t = read(path)
t = rep(t,
'''        val existing = library.listVoiceRoles(content.chapter.storyId)
            .associateBy { it.roleName.trim().lowercase(Locale.ROOT) }
''',
'''        val existing = library.listEffectiveVoiceRoles(content.chapter.storyId, appSettings.autoVoiceCastEnabled)
            .associateBy { it.roleName.trim().lowercase(Locale.ROOT) }
''', 'narration effective roles')
write(path, t)

path = 'app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt'
t = read(path)
t = t.replace('libraryRepository.listVoiceRoles(storyId)\n            .filter { it.enabled }', 'libraryRepository.listEffectiveVoiceRoles(storyId, settingsRepository.snapshot().autoVoiceCastEnabled)', 1)
t = t.replace('libraryRepository.listVoiceRoles(request.storyId)\n            .filter { it.enabled }', 'libraryRepository.listEffectiveVoiceRoles(request.storyId, settingsRepository.snapshot().autoVoiceCastEnabled)', 1)
t = t.replace('"ROLE_EXISTING|${role.roleName.take(80)}|${role.aliasesCsv.take(400)}|${role.expression}"', '"ROLE_EXISTING|${role.roleName.take(80)}|${role.aliasesCsv.take(400)}|${role.description.take(600)}|${role.expression}"')
write(path, t)

path = 'app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt'
t = read(path)
t = rep(t, '            container.libraryRepository.listVoiceRoles(storyId)\n', '            container.libraryRepository.listEffectiveVoiceRoles(storyId, settings.autoVoiceCastEnabled)\n', 'playback effective roles')
write(path, t)

path = 'app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt'
t = read(path)
t = rep(t,
'''            val rules = container.libraryRepository.listEnabledPronunciations()
            val roles = container.libraryRepository.listVoiceRoles(job.storyId).filter(VoiceRoleEntity::enabled)
''',
'''            val rules = container.libraryRepository.listEnabledPronunciations()
            val settings = container.settingsRepository.snapshot()
            val roles = container.libraryRepository.listEffectiveVoiceRoles(job.storyId, settings.autoVoiceCastEnabled)
''', 'export effective roles')
t = rep(t, '            val settings = container.settingsRepository.snapshot()\n            val profile = container.libraryRepository.getStoryTtsProfile(job.storyId)\n', '            val profile = container.libraryRepository.getStoryTtsProfile(job.storyId)\n', 'remove duplicate export settings')
write(path, t)

# 3) Persistent backup/restore history.
write('app/src/main/java/vn/nghetruyen/app/transfer/BackupHistoryStore.kt', '''package vn.nghetruyen.app.transfer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class BackupHistoryEntry(
    val id: String,
    val timestampEpochMs: Long,
    val operation: String,
    val success: Boolean,
    val summary: String,
    val errorCode: String? = null,
    val components: List<String> = emptyList(),
)

class BackupHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("backup_history_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun entries(): List<BackupHistoryEntry> = decode(preferences.getString(KEY, null))

    @Synchronized
    fun record(
        operation: String,
        success: Boolean,
        summary: String,
        errorCode: String? = null,
        components: Collection<String> = emptyList(),
    ): BackupHistoryEntry {
        val entry = BackupHistoryEntry(
            id = UUID.randomUUID().toString(),
            timestampEpochMs = System.currentTimeMillis(),
            operation = operation.take(20),
            success = success,
            summary = summary.trim().take(1200),
            errorCode = errorCode?.trim()?.take(120),
            components = components.map(String::trim).filter(String::isNotBlank).distinct().take(20),
        )
        val updated = (listOf(entry) + entries()).distinctBy(BackupHistoryEntry::id).take(MAX_ENTRIES)
        preferences.edit().putString(KEY, encode(updated)).apply()
        return entry
    }

    @Synchronized
    fun clear() { preferences.edit().remove(KEY).apply() }

    private fun encode(items: List<BackupHistoryEntry>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("id", item.id)
                put("timestampEpochMs", item.timestampEpochMs)
                put("operation", item.operation)
                put("success", item.success)
                put("summary", item.summary)
                put("errorCode", item.errorCode ?: JSONObject.NULL)
                put("components", JSONArray(item.components))
            })
        }
    }.toString()

    private fun decode(raw: String?): List<BackupHistoryEntry> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildList {
            for (index in 0 until array.length().coerceAtMost(MAX_ENTRIES)) {
                val obj = array.optJSONObject(index) ?: continue
                val id = obj.optString("id").takeIf(String::isNotBlank) ?: continue
                add(BackupHistoryEntry(
                    id = id,
                    timestampEpochMs = obj.optLong("timestampEpochMs", 0L),
                    operation = obj.optString("operation").take(20),
                    success = obj.optBoolean("success", false),
                    summary = obj.optString("summary").take(1200),
                    errorCode = obj.optString("errorCode").takeIf { it.isNotBlank() && it != "null" }?.take(120),
                    components = obj.optJSONArray("components")?.let { values ->
                        buildList { for (i in 0 until values.length()) values.optString(i).takeIf(String::isNotBlank)?.let(::add) }
                    }.orEmpty(),
                ))
            }
        }.sortedByDescending(BackupHistoryEntry::timestampEpochMs)
    }.getOrDefault(emptyList())

    companion object {
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 100
    }
}
''')

path = 'app/src/main/java/vn/nghetruyen/app/AppContainer.kt'
t = read(path)
t = rep(t, 'import vn.nghetruyen.app.transfer.BackupTransferManager\n', 'import vn.nghetruyen.app.transfer.BackupTransferManager\nimport vn.nghetruyen.app.transfer.BackupHistoryStore\n', 'container backup history import')
t = rep(t,
'''    val backupTransferManager: BackupTransferManager by lazy {
        BackupTransferManager(appContext, database, settingsRepository)
    }
''',
'''    val backupTransferManager: BackupTransferManager by lazy {
        BackupTransferManager(appContext, database, settingsRepository)
    }
    val backupHistoryStore: BackupHistoryStore by lazy { BackupHistoryStore(appContext) }
''', 'container backup history')
write(path, t)

# AppViewModel: state, global profiles, backup history, source UI action execution.
path = 'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt'
t = read(path)
t = rep(t, 'import vn.nghetruyen.app.core.model.VoiceRoleDraft\n', 'import vn.nghetruyen.app.core.model.VoiceRoleDraft\nimport vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID\n', 'vm global role import')
t = rep(t, 'import vn.nghetruyen.app.sources.StorySearch\n', 'import vn.nghetruyen.app.sources.StorySearch\nimport vn.nghetruyen.app.sources.SourceUiSurface\n', 'vm source ui import')
t = rep(t, 'import vn.nghetruyen.app.transfer.BackupComponent\n', 'import vn.nghetruyen.app.transfer.BackupComponent\nimport vn.nghetruyen.app.transfer.BackupHistoryEntry\n', 'vm backup history import')
t = rep(t, '    val backupComponents: Set<BackupComponent> = BackupComponent.entries.toSet(),\n', '    val backupComponents: Set<BackupComponent> = BackupComponent.entries.toSet(),\n    val backupHistory: List<BackupHistoryEntry> = emptyList(),\n', 'state backup history')
t = rep(t, '            sourceTraces = container.sourcePlatformManager.diagnosticTraces(),\n', '            sourceTraces = container.sourcePlatformManager.diagnosticTraces(),\n            backupHistory = container.backupHistoryStore.entries(),\n', 'initial backup history')
t = rep(t, '        refreshAiCredentialState()\n        search("")\n', '        refreshAiCredentialState()\n        viewModelScope.launch { container.libraryRepository.ensureGlobalVoiceProfiles() }\n        search("")\n', 'ensure global roles init')

# Add source UI action executor before openExternalUrl.
t = insert_before(t, '    fun openExternalUrl(url: String) {', '''    fun runSourceUiAction(sourceId: String, actionId: String, surface: SourceUiSurface) {
        val source = container.sourceRegistry.get(sourceId) ?: run {
            showMessage("Không tìm thấy nguồn cho action này.")
            return
        }
        val snapshot = state.value
        val currentUrl = when (surface) {
            SourceUiSurface.EXPLORE -> source.descriptor.baseUrl
            SourceUiSurface.STORY -> snapshot.storyDetail?.story?.url
            SourceUiSurface.READER -> snapshot.chapterContent?.chapter?.url
        }
        val storyId = snapshot.storyDetail?.story?.id ?: snapshot.chapterContent?.chapter?.storyId
        val chapterId = snapshot.chapterContent?.chapter?.id
        viewModelScope.launch {
            when (val result = source.runUiAction(actionId, surface, currentUrl, storyId, chapterId)) {
                is AppResult.Failure -> showMessage(result.message)
                is AppResult.Success -> {
                    result.value.openUrl?.takeIf(String::isNotBlank)?.let(::openExternalUrl)
                    result.value.message.takeIf(String::isNotBlank)?.let(::showMessage)
                    if (result.value.refresh) {
                        when (surface) {
                            SourceUiSurface.EXPLORE -> when (state.value.exploreMode) {
                                ExploreMode.HOME -> browseHome()
                                ExploreMode.CATEGORY -> state.value.activeCategory?.let(::browseCategory) ?: browseHome()
                                ExploreMode.SEARCH -> search()
                            }
                            SourceUiSurface.STORY -> state.value.storyDetail?.story?.let(::openStory)
                            SourceUiSurface.READER -> state.value.chapterContent?.chapter?.let(::openChapter)
                        }
                    }
                }
            }
        }
    }

''', 'vm source ui action')

# Replace backup functions with persistent logging.
t = replace_function(t, '    fun exportBackup(destination: Uri)', '''    fun exportBackup(destination: Uri) {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = "Đang tạo bản sao lưu…") }
            val selected = state.value.backupComponents
            when (val result = container.backupTransferManager.exportTo(destination, selected)) {
                is AppResult.Success -> {
                    val message = "Đã sao lưu ${result.value.components.size} nhóm dữ liệu, ${result.value.stories} truyện và ${result.value.chapters} chương."
                    container.backupHistoryStore.record("BACKUP", true, message, components = result.value.components.map { it.name })
                    mutableState.update { it.copy(loading = false, message = message, backupHistory = container.backupHistoryStore.entries()) }
                }
                is AppResult.Failure -> {
                    container.backupHistoryStore.record("BACKUP", false, result.message, result.code, selected.map { it.name })
                    mutableState.update { it.copy(loading = false, message = result.message, backupHistory = container.backupHistoryStore.entries()) }
                }
            }
        }
    }''')
t = replace_function(t, '    fun restoreBackup(source: Uri)', '''    fun restoreBackup(source: Uri) {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = "Đang kiểm tra và khôi phục…") }
            val selected = state.value.backupComponents
            when (val result = container.backupTransferManager.restoreFrom(source, selected)) {
                is AppResult.Success -> {
                    val message = "Đã khôi phục ${result.value.components.size} nhóm dữ liệu, ${result.value.stories} truyện và ${result.value.chapters} chương."
                    container.backupHistoryStore.record("RESTORE", true, message, components = result.value.components.map { it.name })
                    mutableState.update { it.copy(loading = false, message = message, backupHistory = container.backupHistoryStore.entries()) }
                }
                is AppResult.Failure -> {
                    container.backupHistoryStore.record("RESTORE", false, result.message, result.code, selected.map { it.name })
                    mutableState.update { it.copy(loading = false, message = result.message, backupHistory = container.backupHistoryStore.entries()) }
                }
            }
        }
    }''')

# Add description to story save call by replacing first matching enabled argument within function area.
idx = t.find('    fun saveVoiceRoleForCurrentStory(draft: VoiceRoleDraft)')
if idx < 0: raise SystemExit('missing saveVoiceRoleForCurrentStory')
end = t.find('\n    fun ', idx + 20)
chunk = t[idx:end if end > 0 else len(t)]
chunk2 = rep(chunk, '                enabled = draft.enabled,\n', '                enabled = draft.enabled,\n                description = draft.description,\n', 'story role description')
t = t[:idx] + chunk2 + t[(end if end > 0 else len(t)):]

insert_marker = '    fun saveVoiceRoleForCurrentStory(draft: VoiceRoleDraft) {'
t = insert_before(t, insert_marker, '''    fun saveGlobalVoiceRole(draft: VoiceRoleDraft) {
        viewModelScope.launch {
            container.libraryRepository.saveVoiceRole(
                storyId = GLOBAL_VOICE_PROFILE_STORY_ID,
                roleName = draft.roleName,
                aliasesCsv = draft.aliases,
                voiceName = draft.voiceName,
                languageTag = draft.languageTag,
                rate = draft.rate,
                pitch = draft.pitch,
                volume = draft.volume,
                isNarrator = draft.isNarrator,
                enginePackage = draft.enginePackage,
                expression = draft.expression.name,
                expressionStrength = draft.expressionStrength,
                sonicSpeed = draft.sonicSpeed,
                sonicPitch = draft.sonicPitch,
                enabled = draft.enabled,
                description = draft.description,
            ).onSuccess { savedId ->
                draft.originalRoleId?.takeIf { it != savedId }?.let { container.libraryRepository.deleteVoiceRole(it) }
                ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
                showMessage("Đã lưu hồ sơ giọng chung ${draft.roleName}.")
            }.onFailure { showMessage(it.message ?: "Không lưu được hồ sơ giọng chung.") }
        }
    }

    fun setGlobalVoiceRoleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            container.libraryRepository.setVoiceRoleEnabled(id, enabled)
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
        }
    }

    fun deleteGlobalVoiceRole(id: String) {
        viewModelScope.launch {
            val role = state.value.voiceRoles.firstOrNull { it.id == id && it.storyId == GLOBAL_VOICE_PROFILE_STORY_ID } ?: return@launch
            if (role.isNarrator) {
                showMessage("Người kể chuyện là hồ sơ bắt buộc và không thể xóa.")
                return@launch
            }
            container.libraryRepository.deleteVoiceRole(id)
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
            showMessage("Đã xóa hồ sơ giọng chung ${role.roleName}.")
        }
    }

    fun restoreGlobalVoiceProfiles() {
        viewModelScope.launch {
            val roles = container.libraryRepository.restoreGlobalVoiceProfiles()
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
            showMessage("Đã khôi phục 7 hồ sơ mẫu; hiện có ${roles.size} hồ sơ giọng chung.")
        }
    }

''', 'global voice vm methods')
write(path, t)

# Wire app callbacks.
path = 'app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt'
t = read(path)
t = rep(t, 'import vn.nghetruyen.app.ui.components.ReferenceScreenBackground\n', 'import vn.nghetruyen.app.ui.components.ReferenceScreenBackground\nimport vn.nghetruyen.app.sources.SourceUiSurface\n', 'app source surface import')
t = rep(t, '                        onCheckSource = viewModel::checkSource,\n                    )\n                    RootTab.LIBRARY', '                        onCheckSource = viewModel::checkSource,\n                        onSourceUiAction = { sourceId, actionId -> viewModel.runSourceUiAction(sourceId, actionId, SourceUiSurface.EXPLORE) },\n                    )\n                    RootTab.LIBRARY', 'wire explore ui action')
t = rep(t, '                        onAutoVoiceCastChange = viewModel::setAutoVoiceCastEnabled,\n', '                        onAutoVoiceCastChange = viewModel::setAutoVoiceCastEnabled,\n                        onSaveGlobalVoiceRole = viewModel::saveGlobalVoiceRole,\n                        onGlobalVoiceRoleEnabledChange = viewModel::setGlobalVoiceRoleEnabled,\n                        onDeleteGlobalVoiceRole = viewModel::deleteGlobalVoiceRole,\n                        onRestoreGlobalVoiceProfiles = viewModel::restoreGlobalVoiceProfiles,\n                        onPreviewGlobalVoiceRole = viewModel::previewVoiceRole,\n', 'wire global voice')
t = rep(t, '                    onOpenSourceLogin = viewModel::openSourceLogin,\n                )\n                Destination.Reader', '                    onOpenSourceLogin = viewModel::openSourceLogin,\n                    onSourceUiAction = { sourceId, actionId -> viewModel.runSourceUiAction(sourceId, actionId, SourceUiSurface.STORY) },\n                )\n                Destination.Reader', 'wire story ui action')
# Reader callback occurs later in file, insert before onMessage if exact.
t = rep(t, '                    onCheckSource = viewModel::checkSource,\n                    onMessage = viewModel::showMessage,\n', '                    onCheckSource = viewModel::checkSource,\n                    onSourceUiAction = { sourceId, actionId -> viewModel.runSourceUiAction(sourceId, actionId, SourceUiSurface.READER) },\n                    onMessage = viewModel::showMessage,\n', 'wire reader ui action')
write(path, t)

# Explore dynamic source actions.
path = 'app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt'
t = read(path)
t = rep(t, 'import vn.nghetruyen.app.core.model.StorySummary\n', 'import vn.nghetruyen.app.core.model.StorySummary\nimport vn.nghetruyen.app.sources.SourceUiSurface\n', 'explore surface import')
t = rep(t, '    onCheckSource: (String) -> Unit,\n) {', '    onCheckSource: (String) -> Unit,\n    onSourceUiAction: (String, String) -> Unit,\n) {', 'explore callback')
marker = '''            if (!state.searchAllSources && selectedSource != null &&
                (selectedSource.loginUrl != null || selectedSource.health != SourceHealth.READY)
            ) {'''
content = '''            val customExploreActions = selectedSource?.uiActions.orEmpty()
                .filter { SourceUiSurface.EXPLORE in it.surfaces }
                .sortedWith(compareBy({ it.group }, { it.order }, { it.label }))
            if (!state.searchAllSources && selectedSource != null && customExploreActions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    customExploreActions.forEach { action ->
                        ReferenceActionButton(
                            text = action.label,
                            onClick = { onSourceUiAction(selectedSource.id, action.id) },
                            normalColor = ReferenceDivider,
                            normalContentColor = ReferenceText,
                            minHeight = 48.dp,
                            modifier = Modifier.padding(1.dp),
                        )
                    }
                }
            }

'''
t = insert_before(t, marker, content, 'explore custom actions')
write(path, t)

# Story dynamic actions.
path = 'app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt'
t = read(path)
t = rep(t, 'import androidx.compose.foundation.background\n', 'import androidx.compose.foundation.background\nimport androidx.compose.foundation.horizontalScroll\n', 'story hscroll import')
t = rep(t, '    onOpenSourceLogin: (String) -> Unit,\n) {', '    onOpenSourceLogin: (String) -> Unit,\n    onSourceUiAction: (String, String) -> Unit,\n) {', 'story ui callback')
marker = '        if (sourceDescriptor != null && (sourceDescriptor.loginUrl != null || sourceDescriptor.health != vn.nghetruyen.app.core.model.SourceHealth.READY)) {'
content = '''        val customStoryActions = sourceDescriptor?.uiActions.orEmpty()
            .filter { vn.nghetruyen.app.sources.SourceUiSurface.STORY in it.surfaces }
            .sortedWith(compareBy({ it.group }, { it.order }, { it.label }))
        if (sourceDescriptor != null && customStoryActions.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(ReferenceDivider).padding(2.dp)) {
                customStoryActions.forEach { action ->
                    ReferenceActionButton(
                        text = action.label,
                        onClick = { onSourceUiAction(sourceDescriptor.id, action.id) },
                        normalColor = ReferenceGray,
                        minHeight = 48.dp,
                        modifier = Modifier.padding(1.dp),
                    )
                }
            }
        }
'''
t = insert_before(t, marker, content, 'story custom actions')
write(path, t)

# Reader dynamic actions.
path = 'app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt'
t = read(path)
t = rep(t, 'import androidx.compose.foundation.background\n', 'import androidx.compose.foundation.background\nimport androidx.compose.foundation.horizontalScroll\n', 'reader hscroll import')
t = rep(t, '    onCheckSource: (String) -> Unit,\n    onMessage:', '    onCheckSource: (String) -> Unit,\n    onSourceUiAction: (String, String) -> Unit,\n    onMessage:', 'reader ui callback')
marker = '''            if (readerSourceDescriptor != null &&
                (readerSourceDescriptor.loginUrl != null || readerSourceDescriptor.health != vn.nghetruyen.app.core.model.SourceHealth.READY)
            ) {'''
content = '''            val customReaderActions = readerSourceDescriptor?.uiActions.orEmpty()
                .filter { vn.nghetruyen.app.sources.SourceUiSurface.READER in it.surfaces }
                .sortedWith(compareBy({ it.group }, { it.order }, { it.label }))
            if (readerSourceDescriptor != null && customReaderActions.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    customReaderActions.forEach { action ->
                        ReaderButton(
                            action.label,
                            { onSourceUiAction(readerSourceDescriptor.id, action.id) },
                            Modifier.padding(1.dp),
                            normalColor = ReferenceGray,
                        )
                    }
                }
            }

'''
t = insert_before(t, marker, content, 'reader custom actions')
write(path, t)

# Personal screen: real backup history and editable global profile management.
path = 'app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt'
t = read(path)
t = rep(t, 'import vn.nghetruyen.app.core.model.TtsVoiceOption\n', 'import vn.nghetruyen.app.core.model.TtsVoiceOption\nimport vn.nghetruyen.app.core.model.VoiceRoleDraft\nimport vn.nghetruyen.app.core.model.VoiceExpression\nimport vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID\n', 'personal voice imports')
t = rep(t, 'import vn.nghetruyen.app.ui.components.ScreenHeading\n', 'import vn.nghetruyen.app.ui.components.ScreenHeading\nimport java.text.SimpleDateFormat\nimport java.util.Date\nimport java.util.Locale\n', 'personal date imports')
t = rep(t, '    onAutoVoiceCastChange: (Boolean) -> Unit,\n', '    onAutoVoiceCastChange: (Boolean) -> Unit,\n    onSaveGlobalVoiceRole: (VoiceRoleDraft) -> Unit,\n    onGlobalVoiceRoleEnabledChange: (String, Boolean) -> Unit,\n    onDeleteGlobalVoiceRole: (String) -> Unit,\n    onRestoreGlobalVoiceProfiles: () -> Unit,\n    onPreviewGlobalVoiceRole: (VoiceRoleDraft) -> Unit,\n', 'personal global callbacks')
t = rep(t,
'''            ReferenceVoiceCastSettingsCard(
                state = state,
                onAutoVoiceCastChange = onAutoVoiceCastChange,
            )
''',
'''            ReferenceVoiceCastSettingsCard(
                state = state,
                onAutoVoiceCastChange = onAutoVoiceCastChange,
                onSaveGlobalVoiceRole = onSaveGlobalVoiceRole,
                onGlobalVoiceRoleEnabledChange = onGlobalVoiceRoleEnabledChange,
                onDeleteGlobalVoiceRole = onDeleteGlobalVoiceRole,
                onRestoreGlobalVoiceProfiles = onRestoreGlobalVoiceProfiles,
                onPreviewGlobalVoiceRole = onPreviewGlobalVoiceRole,
            )
''', 'personal card callbacks')
t = rep(t,
'''            text = {
                Text("Bản Kotlin hiện chưa lưu lịch sử sao lưu/khôi phục riêng. Các thao tác sao lưu và khôi phục vẫn dùng bộ quản lý dữ liệu hiện có.")
            },
''',
'''            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    if (state.backupHistory.isEmpty()) {
                        Text("Chưa có lần sao lưu hoặc khôi phục nào được ghi nhận.")
                    } else {
                        val formatter = remember { SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()) }
                        state.backupHistory.forEachIndexed { index, entry ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text(
                                "${if (entry.operation == "RESTORE") "KHÔI PHỤC" else "SAO LƯU"} • ${if (entry.success) "THÀNH CÔNG" else "THẤT BẠI"}",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(formatter.format(Date(entry.timestampEpochMs)), style = MaterialTheme.typography.bodySmall)
                            Text(entry.summary, modifier = Modifier.padding(top = 3.dp))
                            if (entry.components.isNotEmpty()) Text("Nhóm: ${entry.components.joinToString()}", style = MaterialTheme.typography.bodySmall)
                            entry.errorCode?.let { Text("Mã lỗi: $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            },
''', 'backup history dialog')

# Replace the global card function completely.
start = t.find('@Composable\nprivate fun ReferenceVoiceCastSettingsCard(')
end = t.find('\n@Composable\nprivate fun PlaybackAutomationCard(', start)
if start < 0 or end < 0: raise SystemExit('global card boundaries missing')
new_card = '''@Composable
private fun ReferenceVoiceCastSettingsCard(
    state: MainUiState,
    onAutoVoiceCastChange: (Boolean) -> Unit,
    onSaveGlobalVoiceRole: (VoiceRoleDraft) -> Unit,
    onGlobalVoiceRoleEnabledChange: (String, Boolean) -> Unit,
    onDeleteGlobalVoiceRole: (String) -> Unit,
    onRestoreGlobalVoiceProfiles: () -> Unit,
    onPreviewGlobalVoiceRole: (VoiceRoleDraft) -> Unit,
) {
    val roles = state.voiceRoles.filter { it.storyId == GLOBAL_VOICE_PROFILE_STORY_ID }.take(10)
    var editDraft by remember { mutableStateOf<VoiceRoleDraft?>(null) }

    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Bật mặc định cho truyện dùng cấu hình chung", Modifier.weight(1f))
                Switch(checked = state.autoVoiceCastEnabled, onCheckedChange = onAutoVoiceCastChange)
            }
            Text(
                "Bộ hồ sơ này được dùng làm fallback cho phát TTS, phân vai AI và xuất âm thanh khi truyện chưa có vai riêng.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
            roles.forEach { role ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (role.isNarrator) "Người kể chuyện" else role.roleName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Switch(
                                checked = role.enabled,
                                onCheckedChange = { onGlobalVoiceRoleEnabledChange(role.id, it) },
                                enabled = !role.isNarrator,
                            )
                        }
                        role.description.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row(Modifier.fillMaxWidth()) {
                            Button(onClick = {
                                editDraft = VoiceRoleDraft(
                                    roleName = role.roleName,
                                    originalRoleId = role.id,
                                    aliases = role.aliasesCsv,
                                    description = role.description,
                                    isNarrator = role.isNarrator,
                                    enginePackage = role.enginePackage,
                                    voiceName = role.voiceName,
                                    languageTag = role.languageTag,
                                    rate = role.rate,
                                    pitch = role.pitch,
                                    volume = role.volume,
                                    expression = runCatching { VoiceExpression.valueOf(role.expression) }.getOrDefault(VoiceExpression.NEUTRAL),
                                    expressionStrength = role.expressionStrength,
                                    sonicSpeed = role.sonicSpeed,
                                    sonicPitch = role.sonicPitch,
                                    enabled = role.enabled,
                                )
                            }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("SỬA") }
                            if (!role.isNarrator) {
                                Button(onClick = { onDeleteGlobalVoiceRole(role.id) }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("XÓA") }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    editDraft = VoiceRoleDraft(
                        roleName = "Giọng mới ${roles.size + 1}",
                        enginePackage = state.selectedTtsEnginePackage,
                        voiceName = state.selectedTtsVoiceName,
                        languageTag = state.selectedTtsLanguageTag,
                        rate = state.playback.rate,
                        pitch = state.playback.pitch,
                        volume = state.ttsVolume,
                    )
                },
                enabled = roles.size < 10,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("THÊM GIỌNG") }
            Button(
                onClick = onRestoreGlobalVoiceProfiles,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("KHÔI PHỤC 7 HỒ SƠ MẪU") }
        }
    }

    editDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { editDraft = null },
            title = { Text(if (draft.originalRoleId == null) "THÊM GIỌNG" else "SỬA HỒ SƠ GIỌNG") },
            text = {
                Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = draft.roleName,
                        onValueChange = { if (!draft.isNarrator) editDraft = draft.copy(roleName = it.take(80)) },
                        label = { Text("Tên hồ sơ") },
                        enabled = !draft.isNarrator,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.description,
                        onValueChange = { editDraft = draft.copy(description = it.take(1000)) },
                        label = { Text("Mô tả để AI nhận diện vai") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = draft.aliases,
                        onValueChange = { editDraft = draft.copy(aliases = it.take(500)) },
                        label = { Text("Bí danh, phân cách bằng dấu phẩy") },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Text("Bộ đọc: ${draft.enginePackage ?: "mặc định"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    Text("Giọng: ${draft.voiceName ?: "mặc định"} • ${draft.languageTag}", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tốc độ ${"%.2f".format(draft.rate)}×", Modifier.weight(1f))
                        TextButton(onClick = { editDraft = draft.copy(rate = (draft.rate - 0.05f).coerceAtLeast(0.5f)) }) { Text("−") }
                        TextButton(onClick = { editDraft = draft.copy(rate = (draft.rate + 0.05f).coerceAtMost(2f)) }) { Text("+") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Cao độ ${"%.2f".format(draft.pitch)}×", Modifier.weight(1f))
                        TextButton(onClick = { editDraft = draft.copy(pitch = (draft.pitch - 0.05f).coerceAtLeast(0.5f)) }) { Text("−") }
                        TextButton(onClick = { editDraft = draft.copy(pitch = (draft.pitch + 0.05f).coerceAtMost(2f)) }) { Text("+") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Âm lượng ${"%.0f".format(draft.volume * 100)}%", Modifier.weight(1f))
                        TextButton(onClick = { editDraft = draft.copy(volume = (draft.volume - 0.05f).coerceAtLeast(0.05f)) }) { Text("−") }
                        TextButton(onClick = { editDraft = draft.copy(volume = (draft.volume + 0.05f).coerceAtMost(1f)) }) { Text("+") }
                    }
                    Button(onClick = { onPreviewGlobalVoiceRole(draft) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("NGHE THỬ") }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = draft.roleName.isNotBlank(),
                    onClick = { onSaveGlobalVoiceRole(draft); editDraft = null },
                ) { Text("LƯU") }
            },
            dismissButton = { TextButton(onClick = { editDraft = null }) { Text("HỦY") } },
        )
    }
}
'''
t = t[:start] + new_card + t[end:]
write(path, t)

# Source package test assertion and runtime references are compile-checked below.
print('three remaining parity backends patched')
