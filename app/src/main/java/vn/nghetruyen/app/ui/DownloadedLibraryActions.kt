package vn.nghetruyen.app.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.DownloadSelectionMode
import vn.nghetruyen.app.core.model.DownloadState
import vn.nghetruyen.app.data.local.ChapterEntity
import vn.nghetruyen.app.data.local.StoryEntity
import vn.nghetruyen.app.downloads.DownloadRequest
import vn.nghetruyen.app.downloads.DownloadStorageGuard
import vn.nghetruyen.app.downloads.StoryDownloadPlanner
import java.util.concurrent.atomic.AtomicReference

/** Keeps the main UI wiring explicit while downloaded-library behavior lives beside its workflow. */
object DownloadedLibraryCallbacks {
    private val selectedChapter = AtomicReference<ChapterEntity?>(null)

    fun open(viewModel: AppViewModel, story: StoryEntity) {
        viewModel.openDownloadedStoryFromLibrary(story)
    }

    fun update(viewModel: AppViewModel, story: StoryEntity) {
        viewModel.updateDownloadedStoryFromLibrary(story)
    }

    /** Returns only chapter bodies that still exist locally, in canonical chapter order. */
    suspend fun chapters(app: NgheTruyenApplication, story: StoryEntity): List<ChapterEntity> =
        app.container.libraryRepository.listExportableChapters(story.id)
            .filter { chapter ->
                !chapter.content.isNullOrBlank() && (story.sourceId == "offline" || chapter.downloadedAt != null)
            }
            .sortedWith(compareBy<ChapterEntity> { it.chapterIndex }.thenBy { it.title })

    /** Hands one selected downloaded chapter to the existing story-open callback exactly once. */
    fun selectChapter(chapter: ChapterEntity) {
        selectedChapter.set(chapter)
    }

    internal fun consumeSelectedChapter(storyId: String): ChapterEntity? =
        selectedChapter.getAndSet(null)?.takeIf { it.storyId == storyId }
}

/** XPK-style entry point for a story on the Downloaded shelf. */
fun AppViewModel.openDownloadedStoryFromLibrary(entity: StoryEntity) {
    val chapter = DownloadedLibraryCallbacks.consumeSelectedChapter(entity.id)
    openLibraryStory(entity)
    if (!entity.isOffline) return
    viewModelScope.launch {
        state.filter { snapshot ->
            snapshot.destination == Destination.Story && snapshot.storyDetail?.story?.id == entity.id
        }.first()
        if (chapter != null) {
            openChapter(
                ChapterSummary(
                    id = chapter.id,
                    storyId = chapter.storyId,
                    index = chapter.chapterIndex,
                    title = chapter.title,
                    url = chapter.remoteUrl,
                ),
            )
        } else {
            setStoryDetailTab("chapters")
        }
    }
}

/**
 * Continue a remote download from the first chapter after the highest downloaded
 * chapter. This mirrors the reference tool's CẬP NHẬT / TẢI TIẾP action while
 * still using the Kotlin worker's resumable range download pipeline.
 */
fun AppViewModel.updateDownloadedStoryFromLibrary(entity: StoryEntity) {
    if (entity.sourceId == "offline") {
        readerActionMessage("Truyện nhập từ tệp không có nguồn trực tuyến để cập nhật.")
        return
    }
    val app = getApplication<NgheTruyenApplication>()
    val container = app.container
    val source = container.sourceRegistry.get(entity.sourceId)
    if (source == null) {
        readerActionMessage("Nguồn dùng để tải truyện này hiện không khả dụng.")
        return
    }

    viewModelScope.launch {
        val detail = when (val result = source.story(entity.remoteUrl.ifBlank { entity.id })) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> {
                readerActionMessage(result.message)
                return@launch
            }
        }
        val chapters = when (val plan = StoryDownloadPlanner().collectChapters(source, detail)) {
            is AppResult.Success -> plan.value
            is AppResult.Failure -> {
                readerActionMessage(plan.message)
                return@launch
            }
        }
        val downloaded = container.libraryRepository.listExportableChapters(entity.id)
            .filter { it.downloadedAt != null && !it.content.isNullOrBlank() }
        val nextIndex = (downloaded.maxOfOrNull { it.chapterIndex } ?: -1) + 1
        if (nextIndex >= chapters.size) {
            readerActionMessage("Không có chương tiếp theo.")
            return@launch
        }

        val remaining = chapters.size - nextIndex
        val storage = state.value.storageUsage
        val estimate = DownloadStorageGuard.estimate(
            availableBytes = DownloadStorageGuard.availableBytes(app),
            chapterCount = remaining.coerceAtLeast(1),
            knownDownloadedBytes = storage.downloadedBytes,
            knownDownloadedChapters = storage.downloadedChapters,
        )
        if (!estimate.hasEnoughSpace) {
            readerActionMessage("Không đủ dung lượng an toàn để tải tiếp truyện.")
            return@launch
        }

        val request = DownloadRequest.create(
            sourceId = entity.sourceId,
            storyId = entity.id,
            selectionMode = DownloadSelectionMode.RANGE,
            startChapterIndex = nextIndex,
            endChapterIndex = chapters.lastIndex,
        )
        container.libraryRepository.updateDownloadJob(
            id = request.jobId,
            storyId = request.storyId,
            sourceId = request.sourceId,
            state = DownloadState.QUEUED,
            completedChapters = 0,
            totalChapters = remaining,
            selectionMode = request.selectionMode,
            startChapterIndex = request.startChapterIndex,
            endChapterIndex = request.endChapterIndex,
            wifiOnly = request.wifiOnly,
            chargingOnly = request.chargingOnly,
            currentChapterIndex = -1,
            currentChapterTitle = "",
            retryCount = 0,
        )
        container.libraryRepository.clearDownloadFailures(request.jobId)
        container.downloadScheduler.resume(request)
        readerActionMessage("Đã thêm $remaining chương tiếp theo vào hàng đợi tải.")
    }
}
