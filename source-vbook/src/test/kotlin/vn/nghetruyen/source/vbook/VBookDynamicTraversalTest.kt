package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonValue

class VBookDynamicTraversalTest {
    @Test
    fun findsDescriptorsAcrossNestedDetailAndHomeShapes() {
        val root = JsonValue.Obj(linkedMapOf(
            "items" to JsonValue.Arr(listOf(
                JsonValue.Obj(linkedMapOf(
                    "title" to JsonValue.Str("Home"),
                    "action" to JsonValue.Obj(linkedMapOf(
                        "title" to JsonValue.Str("Open"),
                        "input" to JsonValue.Str("/open"),
                        "script" to JsonValue.Str("listing.js"),
                    )),
                )),
            )),
            "reviews" to JsonValue.Arr(listOf(
                JsonValue.Obj(linkedMapOf(
                    "title" to JsonValue.Str("Reviews"),
                    "input" to JsonValue.Str("book-1"),
                    "script" to JsonValue.Str("reviews.js"),
                )),
            )),
        ))

        val actions = VBookDynamicActionCollector.collect(root)
        assertEquals(setOf("src/listing.js", "src/reviews.js"), actions.mapTo(linkedSetOf(), VBookDynamicAction::scriptPath))
    }

    @Test
    fun traversalStopsOnMaliciousDepth() {
        var value: JsonValue = JsonValue.Str("x")
        repeat(10) { value = JsonValue.Arr(listOf(value)) }
        val failure = runCatching { VBookDynamicActionCollector.collect(value, maxDepth = 4) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("DEPTH_LIMIT"))
    }
}
