package vn.nghetruyen.source.vbook

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
