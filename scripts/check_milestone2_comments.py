#!/usr/bin/env python3
"""Offline executable gate for native story comments in Milestone 2."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")


def static_gate() -> None:
    models = (ROOT / "app/src/main/java/vn/nghetruyen/app/core/model/Models.kt").read_text(encoding="utf-8")
    contract = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/StorySource.kt").read_text(encoding="utf-8")
    adapter = (ROOT / "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePackStorySource.kt").read_text(encoding="utf-8")
    parser = (ROOT / "app/src/main/java/vn/nghetruyen/app/sourceplatform/StoryCommentPayloadParser.kt").read_text(encoding="utf-8")
    cache = (ROOT / "app/src/main/java/vn/nghetruyen/app/sourceplatform/StoryCommentCache.kt").read_text(encoding="utf-8")
    vm = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt").read_text(encoding="utf-8")
    ui = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt").read_text(encoding="utf-8")
    wiring = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt").read_text(encoding="utf-8")

    for token in ("data class StoryComment", "data class StoryCommentPage", "val nextPageUrl: String?"):
        assert token in models, token
    for token in ("val supportsComments: Boolean", "suspend fun comments(url: String)", "suspend fun commentsPage(url: String)"):
        assert token in contract, token
    for token in (
        "SourceActionName.COMMENTS in pack.manifest.actions || genericCommentLoader != null",
        "StoryCommentPayloadParser.parsePage",
        "override suspend fun commentsPage",
        "SourceActionName.COMMENTS",
    ):
        assert token in adapter, token
    for token in ("MAX_COMMENTS = 100", "MAX_COMMENT_TEXT = 20_000", "isISOControl", "nextPageUrl", "cursor"):
        assert token in parser, token
    for token in ("ttlMillis", "maxEntries", "MAX_CACHED_COMMENTS = 500", "fun merge", "@Synchronized"):
        assert token in cache, token
    for token in (
        "private var commentsLoadJob",
        "fun loadStoryComments(force: Boolean = false)",
        "fun loadMoreStoryComments()",
        "storyCommentsNextPageUrl",
        "StoryCommentCache.merge",
        "!snapshot.storyCommentsRefreshable",
        "current.storyDetail?.story?.id != detail.story.id",
        "commentsLoadJob?.cancel()",
        "storyCommentsFromCache",
    ):
        assert token in vm, token
    for token in (
        "BÌNH LUẬN",
        "BẢN NHÚNG",
        "state.storyComments",
        "onLoadComments(false)",
        "TẢI THÊM BÌNH LUẬN",
        "onLoadMoreComments",
    ):
        assert token in ui, token
    assert "onLoadComments = viewModel::loadStoryComments" in wiring
    assert "onLoadMoreComments = viewModel::loadMoreStoryComments" in wiring


def executable_gate() -> None:
    if not KOTLINC:
        print("M2_COMMENTS_EXECUTABLE_SKIPPED: kotlinc not found")
        return
    with tempfile.TemporaryDirectory(prefix="nghe_m2_comments_") as temp_name:
        temp = Path(temp_name)
        smoke = temp / "Smoke.kt"
        smoke.write_text(
            r'''package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.JsonValue

private fun comment(vararg pairs: Pair<String, String>): JsonValue.Obj = JsonValue.Obj(
    linkedMapOf(*pairs.map { (key, value) -> key to JsonValue.Str(value) }.toTypedArray()),
)

fun main() {
    val items = mutableListOf<JsonValue>()
    items += comment("name" to "  Độc giả\u0000 A  ", "date" to " hôm nay ", "content" to " Dòng 1   chữ\n\n\nDòng 2 ")
    items += comment("user" to "Bỏ qua", "text" to "   ")
    repeat(105) { index -> items += comment("user" to "U$index", "text" to "Nội dung $index") }
    val parsed = StoryCommentPayloadParser.parse(JsonValue.Obj(linkedMapOf("comments" to JsonValue.Arr(items))))
    check(parsed.size == 100)
    check(parsed.first().user == "Độc giả A")
    check(parsed.first().time == "hôm nay")
    check(parsed.first().text == "Dòng 1 chữ\n\nDòng 2")
    check(parsed.none { it.text.isBlank() })

    val page = StoryCommentPayloadParser.parsePage(
        JsonValue.Obj(linkedMapOf(
            "items" to JsonValue.Arr(listOf(comment("user" to "A", "text" to "Một"))),
            "next" to JsonValue.Str("https://example.test/comments?page=2"),
        )),
    )
    check(page.comments.single().text == "Một")
    check(page.nextPageUrl == "https://example.test/comments?page=2")

    var now = 1_000L
    val cache = StoryCommentCache(ttlMillis = 100L, maxEntries = 2, clock = { now })
    val key = StoryCommentCache.Key("source", "story")
    cache.put(key, page)
    check(cache.get(key)?.comments?.size == 1)
    check(StoryCommentCache.merge(page.comments, page.comments).size == 1)
    now += 101L
    check(cache.get(key) == null)

    val longText = "x".repeat(25_000)
    val longMeta = "u".repeat(500)
    val limited = StoryCommentPayloadParser.parse(comment("author" to longMeta, "description" to longText)).single()
    check(limited.user.length == 200)
    check(limited.text.length == 20_000)
    println("M2_COMMENTS_EXECUTABLE_OK")
}
''',
            encoding="utf-8",
        )
        jar = temp / "comments.jar"
        files = [
            ROOT / "source-api/src/main/kotlin/vn/nghetruyen/source/api/JsonValue.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/core/model/Models.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/sourceplatform/StoryCommentPayloadParser.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/sourceplatform/StoryCommentCache.kt",
            smoke,
        ]
        compile_result = subprocess.run(
            [KOTLINC, *(str(path) for path in files), "-include-runtime", "-d", str(jar)],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        if compile_result.returncode:
            print(compile_result.stdout)
            print(compile_result.stderr)
            raise SystemExit(compile_result.returncode)
        run_result = subprocess.run(["java", "-jar", str(jar)], cwd=ROOT, text=True, capture_output=True)
        print(run_result.stdout, end="")
        if run_result.returncode:
            print(run_result.stderr)
            raise SystemExit(run_result.returncode)


def main() -> None:
    static_gate()
    executable_gate()
    print("M2_COMMENTS_GATE_OK")


if __name__ == "__main__":
    main()
