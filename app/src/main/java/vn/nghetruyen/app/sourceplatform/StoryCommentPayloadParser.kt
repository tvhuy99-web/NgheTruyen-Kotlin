package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.app.core.model.StoryComment
import vn.nghetruyen.app.core.model.StoryCommentPage
import vn.nghetruyen.source.api.JsonValue

internal object StoryCommentPayloadParser {
    private const val MAX_COMMENTS = 100
    private const val MAX_COMMENT_TEXT = 20_000
    private const val MAX_COMMENT_META = 200

    fun parse(value: JsonValue): List<StoryComment> = parsePage(value).comments

    fun parsePage(value: JsonValue): StoryCommentPage {
        val root = value as? JsonValue.Obj
        val values = when (value) {
            is JsonValue.Arr -> value.values
            is JsonValue.Obj -> value.array("items")?.values
                ?: value.array("comments")?.values
                ?: value.array("results")?.values
                ?: value.obj("data")?.array("items")?.values
                ?: value.obj("data")?.array("comments")?.values
                ?: value.array("data")?.values
                ?: listOf(value)
            else -> emptyList()
        }
        val comments = values.asSequence().mapNotNull(::parseComment).take(MAX_COMMENTS).toList()
        val next = root?.let {
            it.string("nextPageUrl")
                ?: it.string("nextUrl")
                ?: it.string("next")
                ?: it.string("cursor")
                ?: it.obj("paging")?.string("next")
                ?: it.obj("pagination")?.string("nextUrl")
                ?: it.obj("data")?.string("nextPageUrl")
        }?.trim()?.takeIf { it.isNotBlank() && !it.equals("NO_NEXT", ignoreCase = true) }
        return StoryCommentPage(comments = comments, nextPageUrl = next?.take(MAX_COMMENT_TEXT))
    }

    private fun parseComment(value: JsonValue): StoryComment? {
        val obj = value as? JsonValue.Obj ?: return null
        val text = clean(
            obj.string("text") ?: obj.string("content") ?: obj.string("message")
                ?: obj.string("body") ?: obj.string("description").orEmpty(),
            MAX_COMMENT_TEXT,
        )
        if (text.isBlank()) return null
        return StoryComment(
            user = clean(
                obj.string("user") ?: obj.string("name") ?: obj.string("author")
                    ?: obj.string("username") ?: obj.string("displayName") ?: obj.string("display_name")
                    ?: obj.string("nickname") ?: obj.obj("user")?.string("name") ?: "Người đọc",
                MAX_COMMENT_META,
            ).ifBlank { "Người đọc" },
            time = clean(
                obj.string("time") ?: obj.string("date") ?: obj.string("createdAt")
                    ?: obj.string("created_at") ?: obj.string("publishedAt") ?: obj.string("published_at")
                    ?: obj.string("description").orEmpty(),
                MAX_COMMENT_META,
            ),
            text = text,
        )
    }

    private fun clean(raw: String, maxLength: Int): String = raw
        .filter { it == '\n' || it == '\t' || !it.isISOControl() }
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        .take(maxLength)
}
