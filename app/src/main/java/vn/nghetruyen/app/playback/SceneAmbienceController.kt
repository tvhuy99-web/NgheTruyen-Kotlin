package vn.nghetruyen.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import vn.nghetruyen.app.audio.AudioDirectionAsset
import vn.nghetruyen.app.audio.AudioDirectionLimits
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlin.random.Random

/**
 * Voice-first ambience bus with at most two logical layers.
 *
 * Each logical layer owns its own loop clock. Short ambience clips are never hard-looped: a second
 * MediaPlayer is started before the current clip ends and the two overlap/crossfade. Independent
 * overlap jitter plus phase offsets prevent two ambience layers from exposing the same repeating
 * seam. Explicitly numbered variants (forest_01/forest_02/...) are shuffled at loop boundaries and
 * the current variant is excluded whenever another candidate exists. Scene changes are reconciled
 * by logical asset id, so a still-valid layer keeps playing while only the changed layer fades.
 *
 * Player preparation is asynchronous and generation guarded so document/cloud URIs never block the
 * main thread and a stale prepare cannot resurrect audio after pause/stop or a newer scene request.
 */
class SceneAmbienceController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class Slot(
        val asset: AudioDirectionAsset,
        val player: MediaPlayer,
        var fade: Float,
    )

    // Identity semantics are intentional: Layer contains mutable playback state and is also kept in
    // fadingLayers. A data-class hash would change while fade/current/jobs mutate and corrupt Set removal.
    private class Layer(
        var anchorAsset: AudioDirectionAsset,
        var variants: List<AudioDirectionAsset>,
        var masterVolume: Float,
        var mixScale: Float,
        var current: Slot,
        var overlapMinMillis: Int,
        var overlapMaxMillis: Int,
        var loopJob: Job? = null,
        var fadeJob: Job? = null,
    )

    private val layers = linkedMapOf<String, Layer>()
    private val fadingLayers = linkedSetOf<Layer>()
    private val commandGeneration = AtomicLong(0L)
    @Volatile private var activeIdsSnapshot: List<String> = emptyList()
    @Volatile private var pausedSnapshot: Boolean = false
    @Volatile private var sfxDuckMultiplier: Float = 1f
    private var reconcileJob: Job? = null
    private var sfxDuckJob: Job? = null

    fun play(
        assets: List<AudioDirectionAsset>,
        variantsByAssetId: Map<String, List<AudioDirectionAsset>> = emptyMap(),
        masterVolume: Float,
        crossfadeMillis: Int,
        overlapMinMillis: Int,
        overlapMaxMillis: Int,
    ) {
        val requested = assets
            .asSequence()
            .filter { it.id.isNotBlank() && it.uri.isNotBlank() }
            .distinctBy(AudioDirectionAsset::id)
            .take(AudioDirectionLimits.MAX_CONCURRENT_AMBIENCE)
            .toList()
        val safeCrossfade = crossfadeMillis.coerceIn(500, 3_000)
        val safeOverlapMin = overlapMinMillis.coerceIn(350, 3_000)
        val safeOverlapMax = overlapMaxMillis.coerceIn(safeOverlapMin, 4_000)
        pausedSnapshot = false
        val generation = commandGeneration.incrementAndGet()
        reconcileJob?.cancel()
        reconcileJob = scope.launch {
            if (generation != commandGeneration.get() || pausedSnapshot) return@launch
            reconcile(
                requested = requested,
                variantsByAssetId = variantsByAssetId,
                masterVolume = masterVolume.coerceIn(0f, 1f),
                crossfadeMillis = safeCrossfade,
                overlapMinMillis = safeOverlapMin,
                overlapMaxMillis = safeOverlapMax,
                generation = generation,
            )
        }
    }

    /** Brief priority duck for an AI-selected one-shot SFX. */
    fun duckForImportantSfx(factor: Float = SFX_DUCK_FACTOR, holdMillis: Long = SFX_DUCK_HOLD_MS) {
        val target = factor.coerceIn(0.45f, 1f)
        sfxDuckJob?.cancel()
        sfxDuckJob = scope.launch {
            animateBusDuck(target, SFX_DUCK_ATTACK_MS)
            delay(holdMillis.coerceIn(120L, 3_000L))
            animateBusDuck(1f, SFX_DUCK_RELEASE_MS)
        }
    }

    fun pause() {
        pausedSnapshot = true
        commandGeneration.incrementAndGet()
        reconcileJob?.cancel()
        reconcileJob = null
        scope.launch {
            layers.values.forEach { layer ->
                layer.loopJob?.cancel()
                layer.loopJob = null
                layer.fadeJob?.cancel()
                layer.fadeJob = null
                layer.current.fade = 1f
                applyLevel(layer, layer.current)
                runCatching { if (layer.current.player.isPlaying) layer.current.player.pause() }
            }
            releaseFadingLayersImmediately()
        }
    }

    fun resume() {
        pausedSnapshot = false
        scope.launch {
            layers.values.forEach { layer ->
                runCatching {
                    layer.current.fade = 1f
                    applyLevel(layer, layer.current)
                    if (!layer.current.player.isPlaying) layer.current.player.start()
                }
                scheduleLoop(layer, layer.overlapMinMillis, layer.overlapMaxMillis)
            }
        }
    }

    fun stop() {
        commandGeneration.incrementAndGet()
        reconcileJob?.cancel()
        reconcileJob = null
        pausedSnapshot = false
        activeIdsSnapshot = emptyList()
        sfxDuckJob?.cancel()
        sfxDuckJob = null
        sfxDuckMultiplier = 1f
        scope.launch {
            val current = layers.values.toList()
            layers.clear()
            current.forEach(::releaseLayer)
            releaseFadingLayersImmediately()
        }
    }

    fun activeId(): String? = activeIdsSnapshot.firstOrNull()

    fun activeIds(): List<String> = activeIdsSnapshot

    private suspend fun reconcile(
        requested: List<AudioDirectionAsset>,
        variantsByAssetId: Map<String, List<AudioDirectionAsset>>,
        masterVolume: Float,
        crossfadeMillis: Int,
        overlapMinMillis: Int,
        overlapMaxMillis: Int,
        generation: Long,
    ) {
        if (generation != commandGeneration.get() || pausedSnapshot) return
        val requestedById = requested.associateBy(AudioDirectionAsset::id)
        val targetIds = requested.map(AudioDirectionAsset::id)
        activeIdsSnapshot = targetIds
        val mixScale = if (requested.size > 1) DUAL_LAYER_SCALE else 1f

        layers.keys.toList().forEach { id ->
            val current = layers[id] ?: return@forEach
            val replacement = requestedById[id]
            if (replacement == null || replacement.uri != current.anchorAsset.uri) {
                layers.remove(id)
                fadeOutAndRelease(current, crossfadeMillis)
            }
        }

        requested.forEachIndexed { index, asset ->
            if (generation != commandGeneration.get() || pausedSnapshot) return
            val candidates = normalizedVariants(asset, variantsByAssetId[asset.id].orEmpty())
            val existing = layers[asset.id]
            if (existing != null) {
                existing.anchorAsset = asset
                existing.variants = candidates
                existing.masterVolume = masterVolume
                existing.mixScale = mixScale
                existing.overlapMinMillis = overlapMinMillis
                existing.overlapMaxMillis = overlapMaxMillis
                applyLevel(existing, existing.current)
                val playing = runCatching { existing.current.player.isPlaying }.getOrDefault(false)
                if (!playing) runCatching { existing.current.player.start() }
                if (existing.loopJob?.isActive != true) {
                    scheduleLoop(existing, overlapMinMillis, overlapMaxMillis)
                }
                return@forEachIndexed
            }

            val firstAsset = chooseInitialVariant(asset, candidates)
            val player = createPlayer(firstAsset) ?: return@forEachIndexed
            if (generation != commandGeneration.get() || pausedSnapshot) {
                releasePlayer(player)
                return
            }
            val phase = initialPhaseMillis(player.duration, asset.id, index)
            if (phase > 0) runCatching { player.seekTo(phase) }
            val layer = Layer(
                anchorAsset = asset,
                variants = candidates,
                masterVolume = masterVolume,
                mixScale = mixScale,
                current = Slot(firstAsset, player, fade = 0f),
                overlapMinMillis = overlapMinMillis,
                overlapMaxMillis = overlapMaxMillis,
            )
            layers[asset.id] = layer
            applyLevel(layer, layer.current)
            val started = runCatching { player.start() }.isSuccess
            if (!started) {
                layers.remove(asset.id)
                releasePlayer(player)
                return@forEachIndexed
            }
            startFadeIn(layer, crossfadeMillis)
            scheduleLoop(layer, overlapMinMillis, overlapMaxMillis)
        }
    }

    private fun startFadeIn(layer: Layer, durationMillis: Int) {
        layer.fadeJob?.cancel()
        layer.fadeJob = scope.launch {
            try {
                animateFade(layer, layer.current, from = layer.current.fade, to = 1f, durationMillis = durationMillis)
            } finally {
                if (layers[layer.anchorAsset.id] === layer && !pausedSnapshot) {
                    layer.current.fade = 1f
                    applyLevel(layer, layer.current)
                }
                layer.fadeJob = null
            }
        }
    }

    private fun scheduleLoop(layer: Layer, overlapMinMillis: Int, overlapMaxMillis: Int) {
        layer.loopJob?.cancel()
        layer.overlapMinMillis = overlapMinMillis
        layer.overlapMaxMillis = overlapMaxMillis
        if (pausedSnapshot || layers[layer.anchorAsset.id] !== layer) return
        val current = layer.current
        val duration = runCatching { current.player.duration }.getOrDefault(0)
        val position = runCatching { current.player.currentPosition }.getOrDefault(0)
        if (duration <= 0) return
        val remaining = (duration - position).coerceAtLeast(1)
        val maximumUsefulOverlap = (duration / 3).coerceAtLeast(250)
        val minOverlap = overlapMinMillis.coerceAtMost(maximumUsefulOverlap).coerceAtLeast(200)
        val maxOverlap = overlapMaxMillis.coerceAtMost(maximumUsefulOverlap).coerceAtLeast(minOverlap)
        val overlap = if (maxOverlap == minOverlap) minOverlap else Random.nextInt(minOverlap, maxOverlap + 1)
        val waitMillis = (remaining - overlap).coerceAtLeast(120)

        layer.loopJob = scope.launch {
            delay(waitMillis.toLong())
            if (pausedSnapshot || layers[layer.anchorAsset.id] !== layer || layer.current !== current) return@launch
            val nextAsset = chooseNextVariant(layer.variants, current.asset.id)
            val nextPlayer = createPlayer(nextAsset) ?: run {
                scheduleLoop(layer, layer.overlapMinMillis, layer.overlapMaxMillis)
                return@launch
            }
            if (pausedSnapshot || layers[layer.anchorAsset.id] !== layer || layer.current !== current) {
                releasePlayer(nextPlayer)
                return@launch
            }
            val jitter = loopStartJitterMillis(nextPlayer.duration)
            if (jitter > 0) runCatching { nextPlayer.seekTo(jitter) }
            val next = Slot(nextAsset, nextPlayer, fade = 0f)
            applyLevel(layer, next)
            val started = runCatching { nextPlayer.start() }.isSuccess
            if (!started) {
                releasePlayer(nextPlayer)
                scheduleLoop(layer, layer.overlapMinMillis, layer.overlapMaxMillis)
                return@launch
            }
            try {
                crossfadeSlots(layer, current, next, overlap)
                if (pausedSnapshot || layers[layer.anchorAsset.id] !== layer) {
                    releasePlayer(nextPlayer)
                    return@launch
                }
                releasePlayer(current.player)
                layer.current = next.apply { fade = 1f }
                applyLevel(layer, layer.current)
                scheduleLoop(layer, layer.overlapMinMillis, layer.overlapMaxMillis)
            } catch (cancelled: CancellationException) {
                releasePlayer(nextPlayer)
                throw cancelled
            }
        }
    }

    private suspend fun crossfadeSlots(layer: Layer, old: Slot, next: Slot, durationMillis: Int) {
        val steps = (durationMillis / 40).coerceIn(5, 75)
        repeat(steps + 1) { index ->
            if (layers[layer.anchorAsset.id] !== layer || pausedSnapshot) return
            val fraction = index.toFloat() / steps.toFloat()
            old.fade = 1f - fraction
            next.fade = fraction
            applyLevel(layer, old)
            applyLevel(layer, next)
            if (index < steps) delay((durationMillis / steps).coerceAtLeast(1).toLong())
        }
    }

    private suspend fun animateFade(
        layer: Layer,
        slot: Slot,
        from: Float,
        to: Float,
        durationMillis: Int,
    ) {
        val steps = (durationMillis / 40).coerceIn(5, 75)
        repeat(steps + 1) { index ->
            if (layers[layer.anchorAsset.id] !== layer && to > from) return
            if (pausedSnapshot && to > from) return
            val fraction = index.toFloat() / steps.toFloat()
            slot.fade = from + (to - from) * fraction
            applyLevel(layer, slot)
            if (index < steps) delay((durationMillis / steps).coerceAtLeast(1).toLong())
        }
    }

    private fun fadeOutAndRelease(layer: Layer, durationMillis: Int) {
        layer.loopJob?.cancel()
        layer.loopJob = null
        layer.fadeJob?.cancel()
        fadingLayers += layer
        layer.fadeJob = scope.launch {
            try {
                val slot = layer.current
                val start = slot.fade
                val steps = (durationMillis / 40).coerceIn(5, 75)
                repeat(steps + 1) { index ->
                    val fraction = index.toFloat() / steps.toFloat()
                    slot.fade = start * (1f - fraction)
                    applyLevel(layer, slot)
                    if (index < steps) delay((durationMillis / steps).coerceAtLeast(1).toLong())
                }
            } finally {
                releasePlayer(layer.current.player)
                fadingLayers.remove(layer)
                layer.fadeJob = null
            }
        }
    }

    private suspend fun animateBusDuck(target: Float, durationMillis: Int) {
        val start = sfxDuckMultiplier
        if (durationMillis <= 0) {
            sfxDuckMultiplier = target
            applyAllLevels()
            return
        }
        val steps = (durationMillis / 30).coerceIn(3, 30)
        repeat(steps + 1) { index ->
            val fraction = index.toFloat() / steps.toFloat()
            sfxDuckMultiplier = start + (target - start) * fraction
            applyAllLevels()
            if (index < steps) delay((durationMillis / steps).coerceAtLeast(1).toLong())
        }
    }

    private fun applyAllLevels() {
        layers.values.forEach { layer -> applyLevel(layer, layer.current) }
        fadingLayers.forEach { layer -> applyLevel(layer, layer.current) }
    }

    private suspend fun createPlayer(asset: AudioDirectionAsset): MediaPlayer? = suspendCancellableCoroutine { continuation ->
        val player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(appContext, Uri.parse(asset.uri))
                isLooping = false
            }
        }.getOrElse {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        continuation.invokeOnCancellation { releasePlayer(player) }
        player.setOnPreparedListener { prepared ->
            prepared.setOnPreparedListener(null)
            prepared.setOnErrorListener(null)
            if (continuation.isActive) continuation.resume(prepared) else releasePlayer(prepared)
        }
        player.setOnErrorListener { failed, _, _ ->
            failed.setOnPreparedListener(null)
            failed.setOnErrorListener(null)
            releasePlayer(failed)
            if (continuation.isActive) continuation.resume(null)
            true
        }
        runCatching { player.prepareAsync() }.onFailure {
            releasePlayer(player)
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun applyLevel(layer: Layer, slot: Slot) {
        val level = effectiveVolume(slot.asset, layer.masterVolume) *
            layer.mixScale *
            sfxDuckMultiplier *
            slot.fade.coerceIn(0f, 1f)
        runCatching { slot.player.setVolume(level.coerceIn(0f, 1f), level.coerceIn(0f, 1f)) }
    }

    private fun normalizedVariants(
        anchor: AudioDirectionAsset,
        candidates: List<AudioDirectionAsset>,
    ): List<AudioDirectionAsset> = (listOf(anchor) + candidates)
        .filter { it.kind == anchor.kind && it.uri.isNotBlank() }
        .distinctBy(AudioDirectionAsset::id)
        .take(MAX_VARIANTS_PER_FAMILY)

    private fun chooseInitialVariant(anchor: AudioDirectionAsset, variants: List<AudioDirectionAsset>): AudioDirectionAsset {
        if (variants.size <= 1) return anchor
        return variants.random()
    }

    private fun chooseNextVariant(variants: List<AudioDirectionAsset>, currentId: String): AudioDirectionAsset {
        if (variants.isEmpty()) error("Ambience variant list cannot be empty")
        val alternatives = variants.filterNot { it.id == currentId }
        return if (alternatives.isEmpty()) variants.first() else alternatives.random()
    }

    private fun releaseFadingLayersImmediately() {
        if (fadingLayers.isEmpty()) return
        val outgoing = fadingLayers.toList()
        fadingLayers.clear()
        outgoing.forEach(::releaseLayer)
    }

    private fun releaseLayer(layer: Layer) {
        layer.loopJob?.cancel()
        layer.loopJob = null
        layer.fadeJob?.cancel()
        layer.fadeJob = null
        releasePlayer(layer.current.player)
    }

    private fun releasePlayer(player: MediaPlayer) {
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    private fun initialPhaseMillis(duration: Int, assetId: String, layerIndex: Int): Int {
        if (duration < 2_500) return 0
        val ceiling = minOf(duration / 3, 4_000).coerceAtLeast(1)
        val seed = (31L * assetId.hashCode().toLong() + layerIndex * 997L).and(0x7fffffffL)
        return (seed % ceiling.toLong()).toInt()
    }

    private fun loopStartJitterMillis(duration: Int): Int {
        if (duration < 2_000) return 0
        val ceiling = minOf(duration / 12, 450).coerceAtLeast(1)
        return Random.nextInt(0, ceiling + 1)
    }

    private fun effectiveVolume(asset: AudioDirectionAsset, masterVolume: Float): Float {
        val normalization = 10.0.pow(asset.normalizationGainDb.coerceIn(-18f, 12f) / 20.0).toFloat()
        return (asset.volume * masterVolume.coerceIn(0f, 1f) * normalization * VOICE_PRIORITY_DUCK)
            .coerceIn(0f, MAX_AMBIENCE_VOLUME)
    }

    companion object {
        private const val VOICE_PRIORITY_DUCK = 0.58f
        private const val MAX_AMBIENCE_VOLUME = 0.34f
        private const val DUAL_LAYER_SCALE = 0.78f
        private const val MAX_VARIANTS_PER_FAMILY = 12
        private const val SFX_DUCK_FACTOR = 0.72f
        private const val SFX_DUCK_HOLD_MS = 650L
        private const val SFX_DUCK_ATTACK_MS = 120
        private const val SFX_DUCK_RELEASE_MS = 360
    }
}
