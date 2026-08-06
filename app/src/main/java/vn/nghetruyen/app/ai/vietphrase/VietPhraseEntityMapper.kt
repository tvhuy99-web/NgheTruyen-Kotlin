package vn.nghetruyen.app.ai.vietphrase

import vn.nghetruyen.app.data.local.VietPhraseEntity

fun VietPhraseEntity.toVietPhraseRule(): VietPhraseRule = VietPhraseRule(
    id = "db:$id",
    source = source,
    target = target,
    kind = runCatching { VietPhraseDictionaryKind.valueOf(kind) }.getOrDefault(VietPhraseDictionaryKind.VIET_PHRASE),
    priority = priority,
    enabled = enabled,
    scope = runCatching { VietPhraseScope.valueOf(scope) }.getOrDefault(VietPhraseScope.GLOBAL),
    storyId = storyId.ifBlank { null },
    matchMode = runCatching { VietPhraseMatchMode.valueOf(matchMode) }.getOrDefault(VietPhraseMatchMode.LITERAL),
    ignoreCase = ignoreCase,
    updatedAt = updatedAt,
)

fun VietPhraseRule.toEntity(now: Long = System.currentTimeMillis(), existingId: Long = 0): VietPhraseEntity = VietPhraseEntity(
    id = existingId,
    source = source,
    target = target,
    priority = priority,
    enabled = enabled,
    kind = kind.name,
    scope = scope.name,
    storyId = storyId.orEmpty(),
    matchMode = matchMode.name,
    ignoreCase = ignoreCase,
    createdAt = now,
    updatedAt = if (updatedAt > 0) updatedAt else now,
)
