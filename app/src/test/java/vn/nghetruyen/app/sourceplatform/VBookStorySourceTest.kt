package vn.nghetruyen.app.sourceplatform

import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceArtifactState
import com.nghetruyen.source.platform.SourceCompatibilityProfile
import com.nghetruyen.source.platform.SourceEcosystem
import com.nghetruyen.source.platform.SourceTrustState
import com.nghetruyen.source.store.SourceArtifactLifecycle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VBookStorySourceTest {
    @Test
    fun novelAdapterPreservesOpaquePagingAndNormalizesReaderData() = runTest {
        val zip = packageZip()
        val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, "fixture-repo", "fixture/plugin.zip")
        val artifact = SourceArtifactLifecycle.candidate(
            artifactId = "fixture-artifact",
            identity = identity,
            version = "1",
            bytes = zip,
            profile = SourceCompatibilityProfile(SourceEcosystem.VBOOK, "current-js"),
            trust = SourceTrustState.REPOSITORY_TRUSTED,
            installedAtEpochMs = 1,
        ).copy(state = SourceArtifactState.ACTIVE, activatedAtEpochMs = 2)
        val source = VBookStorySource(artifact, zip, SourceCapabilityBrokers())
        assertTrue(source.descriptor.supportsComments)
        assertTrue(source.descriptor.supportsSuggestions)

        val page1 = source.search("needle", 1) as AppResult.Success
        val page2 = source.search("needle", 2) as AppResult.Success
        assertEquals("needle@", page1.value.single().title)
        assertEquals("needle@cursor-1", page2.value.single().title)
        assertEquals("https://x.example/story/needle", page1.value.single().url)

        val home = source.home(1) as AppResult.Success
        assertEquals("home@server-a", home.value.single().title)

        val detail = source.story("https://x.example/story/needle") as AppResult.Success
        assertEquals("Fixture needle", detail.value.story.title)
        assertEquals(listOf("Test"), detail.value.genres)
        assertEquals(listOf("Chapter 1", "Chapter 2"), detail.value.chapters.map { it.title })

        val chapter = source.chapter("https://x.example/story/needle/chapter-1") as AppResult.Success
        assertEquals(listOf("First paragraph", "Second paragraph"), chapter.value.paragraphs)
        assertTrue(chapter.value.nextChapterUrl.orEmpty().endsWith("/chapter-2"))

        val suggestions = source.suggestions("needle") as AppResult.Success
        assertEquals(listOf("Related needle"), suggestions.value)

        val comments1 = source.commentsPage("https://x.example/story/needle") as AppResult.Success
        assertEquals("Reader", comments1.value.comments.single().user)
        assertEquals("comment@", comments1.value.comments.single().text)
        assertEquals("now", comments1.value.comments.single().time)
        val comments2 = source.commentsPage(requireNotNull(comments1.value.nextPageUrl)) as AppResult.Success
        assertEquals("comment@cursor-2", comments2.value.comments.single().text)
        assertEquals(null, comments2.value.nextPageUrl)
    }

    private fun packageZip(): ByteArray {
        val files = linkedMapOf(
            "plugin.json" to """
                {
                  "metadata":{"name":"Fixture","author":"A","version":1,"source":"https://x.example","description":"","locale":"vi","regexp":"x","type":"novel","nsfw":false},
                  "script":{"explore":"explore.js","search":"search.js","detail":"detail.js","toc":"toc.js","chap":"chap.js","comment":"comment.js","suggest":"suggest.js"},
                  "config":{}
                }
            """.trimIndent(),
            "src/explore.js" to """
                function execute(){
                  return Response.success([{title:'Home',type:'list',items:[],action:{type:'list',script:'list.js',input:'/home',data:'server-a'}}]);
                }
            """.trimIndent(),
            "src/list.js" to """
                function execute(input,data){
                  return Response.success([{name:'home@'+data,link:'/story/home',host:'https://x.example'}],'');
                }
            """.trimIndent(),
            "src/search.js" to """
                function execute(query,page){
                  var suffix=String(page||'');
                  return Response.success([{name:query+'@'+suffix,link:'/story/'+query,host:'https://x.example'}],suffix?'cursor-2':'cursor-1');
                }
            """.trimIndent(),
            "src/detail.js" to """
                function execute(url){
                  return Response.success({name:'Fixture needle',author:'Author',description:'Desc',url:url,host:'https://x.example',ongoing:true,genres:[{title:'Test'}]});
                }
            """.trimIndent(),
            "src/toc.js" to """
                function execute(url){
                  return Response.success([
                    {name:'Chapter 1',url:url+'/chapter-1'},
                    {name:'Chapter 2',url:url+'/chapter-2'}
                  ]);
                }
            """.trimIndent(),
            "src/chap.js" to """
                function execute(url){
                  return Response.success({
                    title:'Chapter 1',
                    content:'<script>bad()</script><p>First paragraph</p><p>Second paragraph</p>',
                    nextChapterUrl:'https://x.example/story/needle/chapter-2'
                  });
                }
            """.trimIndent(),
            "src/comment.js" to """
                function execute(input,next){
                  var cursor=String(next||'');
                  return Response.success([{name:'Reader',content:'comment@'+cursor,description:'now'}],cursor?'':'cursor-2');
                }
            """.trimIndent(),
            "src/suggest.js" to """
                function execute(input){
                  return Response.success([{name:'Related '+input,link:'/story/related',host:'https://x.example'}]);
                }
            """.trimIndent(),
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, source) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(source.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
