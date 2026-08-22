package vn.nghetruyen.app.freesound

import vn.nghetruyen.app.ai.XpkVoiceCastSplitter
import vn.nghetruyen.app.audio.AudioAssetKind

/**
 * Adds story evidence to Mode-3 requirements after AI has already made every playback decision.
 *
 * This binder never changes kind/query/importance, timeline boundaries, repeat/cadence/looping or
 * layer count. Its output is consumed only by the on-device library matcher to choose a concrete
 * file. The original short English query remains the sole query used by the Freesound pipeline.
 */
internal object Mode3LocalContextBinder {
    fun attach(
        requirements: List<FreesoundAutoRequirement>,
        units: List<XpkVoiceCastSplitter.Unit>,
    ): List<FreesoundAutoRequirement> {
        if (requirements.isEmpty() || units.isEmpty()) return requirements
        val order = units.withIndex().associate { it.value.id to it.index }
        return requirements.map { requirement ->
            val selected = when (requirement.kind) {
                AudioAssetKind.MUSIC, AudioAssetKind.AMBIENCE -> {
                    val start = requirement.startUnitId?.let(order::get)
                    val end = requirement.endUnitId?.let(order::get)
                    if (start == null || end == null || end < start) emptyList()
                    else units.subList(start, end + 1)
                }
                AudioAssetKind.SFX -> {
                    val index = requirement.unitId?.let(order::get)
                    index?.let { listOf(units[it]) }.orEmpty()
                }
            }
            val localContext = selected.asSequence()
                .map(XpkVoiceCastSplitter.Unit::text)
                .map(String::trim)
                .filter(String::isNotBlank)
                .joinToString(" ")
                .replace(WHITESPACE, " ")
                .trim()
                .let(::boundedContext)
            if (localContext.isBlank()) requirement else requirement.copy(localContext = localContext)
        }
    }

    private fun boundedContext(value: String): String {
        if (value.length <= MAX_CONTEXT_CHARS) return value
        val side = (MAX_CONTEXT_CHARS - ELLIPSIS.length) / 2
        return value.take(side).trimEnd() + ELLIPSIS + value.takeLast(side).trimStart()
    }

    private const val MAX_CONTEXT_CHARS = 6_000
    private const val ELLIPSIS = " … "
    private val WHITESPACE = Regex("\\s+")
}
