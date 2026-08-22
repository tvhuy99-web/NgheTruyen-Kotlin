package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromiumVBookPreludeDomCompatibilityTest {
    @Test
    fun elementSelectIncludesTheCurrentElementWhenItMatchesLikeJsoup() {
        val program = ChromiumVBookPrelude.build(
            bridgeToken = "test-token",
            entryPath = "src/test.js",
            inputJson = "{}",
        )

        assertTrue(program.contains("var selectorText=String(selector||'');"))
        assertTrue(program.contains("var selected=Array.from(node.querySelectorAll(selectorText));"))
        assertTrue(program.contains("if(typeof node.matches==='function'&&node.matches(selectorText)) selected.unshift(node);"))
        assertTrue(program.contains("return __nativeElements(selected,baseUrl);"))
        assertFalse(program.contains("out.select=function(selector){return __nativeElements(node.querySelectorAll(String(selector||'')),baseUrl);};"))
    }

    @Test
    fun documentSelectStillUsesDescendantQuerySemantics() {
        val program = ChromiumVBookPrelude.build(
            bridgeToken = "test-token",
            entryPath = "src/test.js",
            inputJson = "{}",
        )

        assertTrue(program.contains("out.select=function(selector){return __nativeElements(doc.querySelectorAll(String(selector||'')),baseUrl);};"))
    }
}
