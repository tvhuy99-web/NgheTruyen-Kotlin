package vn.nghetruyen.app.sourceplatform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import vn.nghetruyen.app.MainActivity
import vn.nghetruyen.app.playback.PlaybackQueueStore
import vn.nghetruyen.app.ui.AppViewModel
import vn.nghetruyen.app.ui.Destination
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.ui.RootTab
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceHostEventBus
import vn.nghetruyen.source.api.SourceHostKernelContract
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Converts app state transitions into lightweight extension host events. */
object ExtensionHostEventPump {
    private val jobs = WeakHashMap<AppViewModel, List<Job>>()
    private val activeHost = AtomicReference<WeakReference<AppViewModel>?>(null)
    private val lifecycleRegistered = AtomicBoolean(false)

    fun install(viewModel: AppViewModel) {
        activeHost.set(WeakReference(viewModel))
        registerActivityLifecycle(viewModel.getApplication())
        synchronized(jobs) {
            if (jobs[viewModel]?.any(Job::isActive) == true) return

            var previousNavigation: NavigationSnapshot? = null
            val navigationJob = viewModel.viewModelScope.launch {
                viewModel.state
                    .map(NavigationSnapshot::from)
                    .distinctUntilChanged()
                    .collect { current ->
                        val previous = previousNavigation
                        if (previous == null) {
                            emit(current.sourceId, "app.start", JsonValue.Obj())
                        } else {
                            emitNavigationTransitions(previous, current)
                        }
                        previousNavigation = current
                    }
            }

            var previousPlayback: PlaybackSignal? = null
            val playbackJob = viewModel.viewModelScope.launch {
                PlaybackQueueStore.state
                    .map { playback -> PlaybackSignal(
                        sourceId = playback.sourceId,
                        chapterId = playback.chapterId,
                        paragraphIndex = playback.paragraphIndex,
                        speechChunkIndex = playback.speechChunkIndex,
                        isPlaying = playback.isPlaying,
                        rate = playback.rate,
                        pitch = playback.pitch,
                        volume = playback.volume,
                        preparationState = playback.preparationState.name,
                        narrationStage = playback.narrationStage.name,
                    ) }
                    .distinctUntilChanged()
                    .collect { current ->
                        if (previousPlayback != null) {
                            val sourceId = current.sourceId.ifBlank { activeHost.get()?.get()?.state?.value?.selectedSourceId.orEmpty() }
                            emit(sourceId, "playback.changed", JsonValue.Obj(linkedMapOf(
                                "chapterId" to JsonValue.Str(current.chapterId),
                                "paragraphIndex" to jsonNumber(current.paragraphIndex),
                            )))
                        }
                        previousPlayback = current
                    }
            }

            var firstLibraryEmission = true
            val libraryJob = viewModel.viewModelScope.launch {
                viewModel.libraryRevision.collect {
                    if (firstLibraryEmission) {
                        firstLibraryEmission = false
                        return@collect
                    }
                    val state = viewModel.state.value
                    emit(NavigationSnapshot.sourceId(state), "library.changed", JsonValue.Obj(linkedMapOf(
                        "reading" to jsonNumber(state.readingStories.size),
                        "downloaded" to jsonNumber(state.downloadedStories.size),
                        "bookmarks" to jsonNumber(state.bookmarks.size),
                        "notes" to jsonNumber(state.notes.size),
                        "following" to jsonNumber(state.following.size),
                    )))
                }
            }

            val installed = listOf(navigationJob, playbackJob, libraryJob)
            installed.forEach { job ->
                job.invokeOnCompletion {
                    synchronized(jobs) {
                        val current = jobs[viewModel].orEmpty().filter(Job::isActive)
                        if (current.isEmpty()) jobs.remove(viewModel) else jobs[viewModel] = current
                    }
                }
            }
            jobs[viewModel] = installed
        }
    }

    private fun registerActivityLifecycle(application: Application) {
        if (!lifecycleRegistered.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) emitForActiveHost("app.resume")
            }

            override fun onActivityPaused(activity: Activity) {
                if (activity is MainActivity) emitForActiveHost("app.pause")
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun emitForActiveHost(name: String) {
        val host = activeHost.get()?.get() ?: return
        val state = host.state.value
        emit(NavigationSnapshot.sourceId(state), name, JsonValue.Obj())
    }

    private fun emitNavigationTransitions(previous: NavigationSnapshot, current: NavigationSnapshot) {
        if (previous.destination != current.destination || previous.sourceId != current.sourceId) {
            if (previous.destination == Destination.Reader) {
                emit(previous.sourceId, "reader.leave", JsonValue.Obj(linkedMapOf(
                    "chapterId" to JsonValue.Str(previous.chapterId),
                )))
            }
            when (current.destination) {
                Destination.Root -> if (current.rootTab == RootTab.EXPLORE) {
                    emit(current.sourceId, "explore.enter", JsonValue.Obj())
                }
                Destination.Story -> emit(current.sourceId, "story.enter", JsonValue.Obj(linkedMapOf(
                    "storyId" to JsonValue.Str(current.storyId),
                )))
                Destination.Reader -> emit(current.sourceId, "reader.enter", JsonValue.Obj(linkedMapOf(
                    "chapterId" to JsonValue.Str(current.chapterId),
                    "paragraphIndex" to jsonNumber(PlaybackQueueStore.state.value.paragraphIndex),
                )))
            }
        }

        if (current.chapterId.isNotBlank() && current.chapterId != previous.chapterId) {
            emit(current.sourceId, "reader.chapterChanged", JsonValue.Obj(linkedMapOf(
                "chapterId" to JsonValue.Str(current.chapterId),
                "previousChapterId" to JsonValue.Str(previous.chapterId),
            )))
        }
    }

    private fun emit(sourceId: String, name: String, payload: JsonValue.Obj) {
        if (sourceId.isBlank()) return
        SourceHostEventBus.emit(
            sourceId = sourceId,
            event = SourceHostKernelContract.event(name, payload),
            traceId = "host-event-${System.nanoTime()}",
        )
    }

    private fun jsonNumber(value: Int): JsonValue.Num = JsonValue.Num(value.toDouble(), value.toString())

    private data class NavigationSnapshot(
        val sourceId: String,
        val destination: Destination,
        val rootTab: RootTab,
        val storyId: String,
        val chapterId: String,
    ) {
        companion object {
            fun from(state: MainUiState): NavigationSnapshot = NavigationSnapshot(
                sourceId = sourceId(state),
                destination = state.destination,
                rootTab = state.rootTab,
                storyId = state.storyDetail?.story?.id.orEmpty(),
                chapterId = state.chapterContent?.chapter?.id.orEmpty(),
            )

            fun sourceId(state: MainUiState): String =
                state.storyDetail?.story?.sourceId?.takeIf(String::isNotBlank) ?: state.selectedSourceId
        }
    }

    private data class PlaybackSignal(
        val sourceId: String,
        val chapterId: String,
        val paragraphIndex: Int,
        val speechChunkIndex: Int,
        val isPlaying: Boolean,
        val rate: Float,
        val pitch: Float,
        val volume: Float,
        val preparationState: String,
        val narrationStage: String,
    )
}
