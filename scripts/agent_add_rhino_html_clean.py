from pathlib import Path

ROOT = Path('.')

runtime = ROOT / 'source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt'
text = runtime.read_text(encoding='utf-8')
old = '''        val html = cx.newObject(scope).also { obj ->\n            ScriptableObject.putProperty(obj, "parse", hostFunction { args ->\n                val content = Context.toString(args.getOrNull(0) ?: "")\n                val baseUrl = Context.toString(args.getOrNull(1) ?: request.input.string("url").orEmpty())\n                JsoupDocumentObject(Jsoup.parse(content, baseUrl), scope)\n            })\n        }\n'''
new = '''        val html = cx.newObject(scope).also { obj ->\n            ScriptableObject.putProperty(obj, "parse", hostFunction { args ->\n                val content = Context.toString(args.getOrNull(0) ?: "")\n                val baseUrl = Context.toString(args.getOrNull(1) ?: request.input.string("url").orEmpty())\n                JsoupDocumentObject(Jsoup.parse(content, baseUrl), scope)\n            })\n            ScriptableObject.putProperty(obj, "clean", hostFunction { args ->\n                val content = Context.toString(args.getOrNull(0) ?: "")\n                val allowed = (args.getOrNull(1) as? NativeArray)?.ids.orEmpty().map { index ->\n                    Context.toString(ScriptableObject.getProperty(args[1] as NativeArray, index.toString()))\n                }\n                VBookHtmlCleaner.clean(content, allowed)\n            })\n        }\n'''
if old not in text:
    raise SystemExit('Html host block not found')
runtime.write_text(text.replace(old, new, 1), encoding='utf-8')

cleaner = ROOT / 'source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookHtmlCleaner.kt'
cleaner.write_text(r'''package vn.nghetruyen.source.vbook

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist

/** Shared legacy Html.clean implementation for non-DOM runtimes such as Rhino. */
internal object VBookHtmlCleaner {
    fun clean(content: String, allowedTags: List<String>): String {
        val safeTags = allowedTags.asSequence()
            .map(String::trim)
            .map(String::lowercase)
            .filter { it.matches(Regex("^[a-z][a-z0-9:-]{0,63}$")) }
            .distinct()
            .take(MAX_ALLOWED_TAGS)
            .toList()
        val safelist = Safelist.none().apply {
            if (safeTags.isNotEmpty()) addTags(*safeTags.toTypedArray())
        }
        val settings = Document.OutputSettings().prettyPrint(false)
        return Jsoup.clean(content, "", safelist, settings)
    }

    private const val MAX_ALLOWED_TAGS = 128
}
''', encoding='utf-8')

test = ROOT / 'source-vbook/src/test/kotlin/vn/nghetruyen/source/vbook/VBookHtmlCleanerTest.kt'
test.write_text(r'''package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookHtmlCleanerTest {
    @Test
    fun preservesOnlyAllowedTagsAndReadableText() {
        val cleaned = VBookHtmlCleaner.clean(
            "<div>Hello <b class='x'>World</b><i> italic</i><script>alert(1)</script></div>",
            listOf("b"),
        )
        assertTrue(cleaned.contains("Hello"))
        assertTrue(cleaned.contains("<b>World</b>"))
        assertTrue(cleaned.contains("italic"))
        assertFalse(cleaned.contains("<div"))
        assertFalse(cleaned.contains("<i"))
        assertFalse(cleaned.contains("script", ignoreCase = true))
        assertFalse(cleaned.contains("alert(1)"))
    }

    @Test
    fun emptyAllowListReturnsTextWithoutMarkup() {
        assertEquals("A B", VBookHtmlCleaner.clean("<p>A <em>B</em></p>", emptyList()).trim())
    }

    @Test
    fun invalidAndDuplicateTagsAreIgnoredSafely() {
        val cleaned = VBookHtmlCleaner.clean("<b>x</b><u>y</u>", listOf("B", "b", "<script>", "u"))
        assertEquals("<b>x</b><u>y</u>", cleaned)
    }
}
''', encoding='utf-8')

print('Rhino Html.clean support staged')
