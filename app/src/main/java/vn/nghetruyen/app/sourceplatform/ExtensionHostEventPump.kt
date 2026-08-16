package vn.nghetruyen.app.sourceplatform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import vn.nghetruyen.app.MainActivity
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







object ExtensionHostEventPump {
    private val jobs = WeakHashMap<AppViewModel, Job>()
    private val activeHost = AtomicReference<WeakReference<AppViewModel>?>(null)
    private val lifecycleRegistered = AtomicBoolean(false)

    fun install(viewModel: AppViewModel) {
        activeHost.set(WeakReference(viewModel))
        registerActivityLifecycle(viewModel.getApplication())
        synchronized(jobs) {
            if (jobs[viewModel]?.isActive == true) return
            val job = viewModel.viewModelScope.launch {
                var previous: Snapshot? = null
                viewModel.state.collect { state ->
                    val current = Snapshot.from(state)
                    if (previous == null) {
                        emit(current.sourceId, "app.start", JsonValue.Obj())
                    } else {
                        emitTransitions(previous!!, current, state)
                    }
                    previous = current
                }
            }
            job.invokeOnCompletion {
                synchronized(jobs) {
                    if (jobs[viewModel] === job) jobs.remove(viewModel)
                }
            }
            jobs[viewModel] = job
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
        emit(Snapshot.sourceId(state), name, JsonValue.Obj())
    }

    private fun emitTransitions(previous: Snapshot, current: Snapshot, state: MainUiState) {
        if (previous.destination != current.destination || previous.sourceId != current.sourceId) {
            if (previous.destination == Destination.Reader) {
                emit(previous.sourceId, "reader.leave", JsonValue.Obj(linkedMapOf(
                    "chapterId" to JsonValue.Str(previous.chapterId),
                )))
            }
            when (current.destination) {
                Destination.Root -> if (state.rootTab == RootTab.EXPLORE) {
                    emit(current.sourceId, "explore.enter", JsonValue.Obj())
                }
                Destination.Story -> emit(current.sourceId, "story.enter", JsonValue.Obj(linkedMapOf(
                    "storyId" to JsonValue.Str(state.storyDetail?.story?.id.orEmpty()),
                )))
                Destination.Reader -> emit(current.sourceId, "reader.enter", JsonValue.Obj(linkedMapOf(
                    "chapterId" to JsonValue.Str(current.chapterId),
                    "paragraphIndex" to jsonNumber(current.paragraphIndex),
                )))
            }
        }

        if (current.chapterId.isNotBlank() && current.chapterId != previous.chapterId) {
            emit(current.sourceId, "reader.chapterChanged", JsonValue.Obj(linkedMapOf(
                "chapterId" to JsonValue.Str(current.chapterId),
                "previousChapterId" to JsonValue.Str(previous.chapterId),
            )))
        }

        if (current.playbackFingerprint != previous.playbackFingerprint) {
            emit(current.sourceId, "playback.changed", JsonValue.Obj(linkedMapOf(
                "chapterId" to JsonValue.Str(state.playback.chapterId),
                "paragraphIndex" to jsonNumber(state.playback.paragraphIndex),
            )))
        }

        if (current.libraryFingerprint != previous.libraryFingerprint) {
            emit(current.sourceId, "library.changed", JsonValue.Obj(linkedMapOf(
                "reading" to jsonNumber(state.readingStories.size),
                "downloaded" to jsonNumber(state.downloadedStories.size),
                "bookmarks" to jsonNumber(state.bookmarks.size),
                "notes" to jsonNumber(state.notes.size),
                "following" to jsonNumber(state.following.size),
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

    private data class Snapshot(
        val sourceId: String,
        val destination: Destination,
        val chapterId: String,
        val paragraphIndex: Int,
        val playbackFingerprint: Int,
        val libraryFingerprint: Int,
    ) {
        companion object {
            fun from(state: MainUiState): Snapshot = Snapshot(
                sourceId = sourceId(state),
                destination = state.destination,
                chapterId = state.chapterContent?.chapter?.id.orEmpty(),
                paragraphIndex = state.playback.paragraphIndex,
                playbackFingerprint = state.playback.hashCode(),
                libraryFingerprint = listOf(
                    state.readingStories,
                    state.downloadedStories,
                    state.bookmarks,
                    state.notes,
                    state.following,
                ).hashCode(),
            )

            fun sourceId(state: MainUiState): String =
                state.storyDetail?.story?.sourceId?.takeIf(String::isNotBlank) ?: state.selectedSourceId
        }
    }
}
