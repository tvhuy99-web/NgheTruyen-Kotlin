from pathlib import Path

runtime = Path("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt")
text = runtime.read_text(encoding="utf-8")

anchor = "import kotlin.math.max\n\nclass VBookJsRuntime("
helper = r'''import kotlin.math.max

internal fun normalizeVBookChapterParagraphs(html: String): List<String> {
    if (html.isBlank()) return emptyList()
    val body = Jsoup.parseBodyFragment(html).body()
    body.select("script,style,noscript,iframe,template,[hidden],[aria-hidden=true]").remove()
    body.select("[style]").forEach { element ->
        val style = element.attr("style").lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        val zeroFontSize = Regex("(?:^|;)font-size:0(?:px)?(?:;|$)").containsMatchIn(style)
        if ("display:none" in style || "visibility:hidden" in style || zeroFontSize) {
            element.remove()
        }
    }
    body.select("br").forEach { it.after("\n") }
    body.select("p,div,section,article,blockquote,li,h1,h2,h3,h4,h5,h6").forEach { element ->
        element.before("\n")
        element.after("\n")
    }
    return body.wholeText()
        .replace('\r', '\n')
        .split(Regex("\\n+"))
        .map { line -> line.replace(Regex("[ \\t]+"), " ").trim() }
        .filter(String::isNotBlank)
        .take(20_000)
}

class VBookJsRuntime('''
if anchor not in text:
    raise SystemExit("helper insertion anchor not found")
text = text.replace(anchor, helper, 1)

old = r'''    private fun normalizeChapterContent(value: JsonValue, url: String): JsonValue {
        val obj = value as? JsonValue.Obj
        val html = when (value) { is JsonValue.Str -> value.value; is JsonValue.Obj -> value.string("content") ?: value.string("html").orEmpty(); else -> "" }
        val paragraphs = Jsoup.parseBodyFragment(html).select("p,div,br").mapNotNull { it.text().trim().takeIf(String::isNotBlank) }.ifEmpty {
            Jsoup.parseBodyFragment(html).text().split(Regex("\\n+|(?<=[.!?])\\s+(?=[A-ZÀ-Ỹ])")).map(String::trim).filter(String::isNotBlank)
        }.distinct()
        val title = obj?.string("title") ?: obj?.string("name") ?: "Chương"
        return JsonValue.Obj(linkedMapOf(
            "id" to JsonValue.Str(stableId(url)), "storyId" to JsonValue.Str(""),
            "index" to JsonValue.Num(0.0, "0"), "title" to JsonValue.Str(title), "url" to JsonValue.Str(url),
            "paragraphs" to JsonValue.Arr(paragraphs.map(JsonValue::Str)),
            "previousChapterUrl" to (obj?.string("previousChapterUrl")?.let(JsonValue::Str) ?: JsonValue.Null),
            "nextChapterUrl" to (obj?.string("nextChapterUrl")?.let(JsonValue::Str) ?: JsonValue.Null),
        ))
    }
'''
new = r'''    private fun normalizeChapterContent(value: JsonValue, url: String): JsonValue {
        val obj = value as? JsonValue.Obj
        val explicitParagraphs = obj?.array("paragraphs")?.values.orEmpty().mapNotNull { item ->
            (item as? JsonValue.Str)?.value?.trim()?.takeIf(String::isNotBlank)
        }
        val html = when (value) {
            is JsonValue.Str -> value.value
            is JsonValue.Obj -> value.string("content") ?: value.string("html").orEmpty()
            else -> ""
        }
        val paragraphs = explicitParagraphs.ifEmpty { normalizeVBookChapterParagraphs(html) }
        val title = obj?.string("title") ?: obj?.string("name") ?: "Chương"
        val previousChapterUrl = (obj?.string("previousChapterUrl") ?: obj?.string("prev") ?: obj?.string("previous"))
            ?.trim()?.takeIf { it.isNotBlank() && !it.equals("NO_PREV", ignoreCase = true) }
        val nextChapterUrl = (obj?.string("nextChapterUrl") ?: obj?.string("next"))
            ?.trim()?.takeIf { it.isNotBlank() && !it.equals("NO_NEXT", ignoreCase = true) }
        return JsonValue.Obj(linkedMapOf(
            "id" to JsonValue.Str(stableId(url)), "storyId" to JsonValue.Str(""),
            "index" to JsonValue.Num(0.0, "0"), "title" to JsonValue.Str(title), "url" to JsonValue.Str(url),
            "paragraphs" to JsonValue.Arr(paragraphs.map(JsonValue::Str)),
            "previousChapterUrl" to previousChapterUrl?.let(JsonValue::Str).orNull(),
            "nextChapterUrl" to nextChapterUrl?.let(JsonValue::Str).orNull(),
        ))
    }
'''
if old not in text:
    raise SystemExit("normalizeChapterContent block not found")
text = text.replace(old, new, 1)
runtime.write_text(text, encoding="utf-8")

test = Path("source-vbook/src/test/kotlin/vn/nghetruyen/source/vbook/VBookChapterContentNormalizationRegressionTest.kt")
test.write_text(r'''package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VBookChapterContentNormalizationRegressionTest {
    @Test
    fun directTextSeparatedByBreaksSurvivesWhenHiddenParagraphsExist() {
        val html = """
            <div id="ads-chapter-top"></div>
            Đoạn thật thứ nhất.
            <br><br>
            Đoạn thật thứ hai.
            <p style="display: none;visibility: hidden;height: 0;">truyen full, truyenfull, truyenfullvn, truyenfulllive</p>
            <p style="display: none;visibility: hidden;height: 0;">,</p>
        """.trimIndent()

        val paragraphs = normalizeVBookChapterParagraphs(html)

        assertEquals(listOf("Đoạn thật thứ nhất.", "Đoạn thật thứ hai."), paragraphs)
        assertFalse(paragraphs.any { "truyenfull" in it.lowercase() })
    }

    @Test
    fun normalParagraphMarkupStillProducesOneEntryPerParagraph() {
        val html = "<p>Đoạn một.</p><p>Đoạn hai.</p>"
        assertEquals(listOf("Đoạn một.", "Đoạn hai."), normalizeVBookChapterParagraphs(html))
    }
}
''', encoding="utf-8")
print("Applied native/VBook chapter normalization regression fix")
