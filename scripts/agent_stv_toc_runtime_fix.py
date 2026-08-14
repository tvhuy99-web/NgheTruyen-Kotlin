from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


adapter = Path("source-lua/src/main/resources/vn/nghetruyen/source/lua/native_v2_adapter.lua")
replace_once(
    adapter,
    'var WARNED=Object.create(null),WARNING_COUNT=0,MAX_WARNINGS=64;\n',
    '''var WARNED=Object.create(null),WARNING_COUNT=0,MAX_WARNINGS=64;\nvar REGEX_CACHE=Object.create(null),REGEX_CACHE_ORDER=[],MAX_REGEX_CACHE=128;\nfunction cachedRegExp(pattern,flags){\n pattern=String(pattern==null?"":pattern);flags=String(flags||"");\n var key=flags+"\\u0000"+pattern,re=REGEX_CACHE[key];\n if(re){re.lastIndex=0;return re}\n re=new RegExp(pattern,flags);\n if(REGEX_CACHE_ORDER.length>=MAX_REGEX_CACHE){var old=REGEX_CACHE_ORDER.shift();delete REGEX_CACHE[old]}\n REGEX_CACHE_ORDER.push(key);REGEX_CACHE[key]=re;return re;\n}\nfunction regexMatchText(value,pattern,flags){var re=cachedRegExp(pattern,flags);re.lastIndex=0;var result=String(value==null?"":value).match(re);re.lastIndex=0;return result}\nfunction regexTestText(value,pattern,flags){var re=cachedRegExp(pattern,flags);re.lastIndex=0;var result=re.test(String(value==null?"":value));re.lastIndex=0;return result}\nfunction regexReplaceText(value,pattern,flags,replacement){var re=cachedRegExp(pattern,flags);re.lastIndex=0;var result=String(value==null?"":value).replace(re,replacement);re.lastIndex=0;return result}\n''',
    "bounded regex cache",
)
replace_once(
    adapter,
    'out=out.replace(new RegExp(pattern,String(rule.flags||"g")),replacement)',
    'out=regexReplaceText(out,pattern,String(rule.flags||"g"),replacement)',
    "applyReplace regex cache",
)
replace_once(
    adapter,
    'var m=String(out).match(new RegExp(String(desc.regex),String(desc.flags||"")))',
    'var m=regexMatchText(out,String(desc.regex),String(desc.flags||""))',
    "extract regex cache",
)
replace_once(
    adapter,
    'out=new RegExp(String(desc.matches),String(desc.flags||"i")).test(String(out==null?"":out))',
    'out=regexTestText(out,String(desc.matches),String(desc.flags||"i"))',
    "matches regex cache",
)
replace_once(
    adapter,
    'if(op==="matches"||op==="regex")try{return new RegExp(rs,String(condition.flags||"")).test(ls)}catch(e)',
    'if(op==="matches"||op==="regex")try{return regexTestText(ls,rs,String(condition.flags||""))}catch(e)',
    "condition regex cache",
)
replace_once(
    adapter,
    'return opSpec.plain===true?String(value==null?"":value).split(pattern).join(rep):String(value==null?"":value).replace(new RegExp(pattern,String(opSpec.flags||"g")),rep)',
    'return opSpec.plain===true?String(value==null?"":value).split(pattern).join(rep):regexReplaceText(value,pattern,String(opSpec.flags||"g"),rep)',
    "transform replace regex cache",
)
replace_once(
    adapter,
    'if(op==="regex_replace"){try{return String(value==null?"":value).replace(new RegExp(String(opSpec.pattern||""),String(opSpec.flags||"g")),String(resolveDynamic(ctx,opSpec.replacement,value)||""))}catch(e)',
    'if(op==="regex_replace"){try{return regexReplaceText(value,String(opSpec.pattern||""),String(opSpec.flags||"g"),String(resolveDynamic(ctx,opSpec.replacement,value)||""))}catch(e)',
    "transform regex_replace cache",
)
replace_once(
    adapter,
    'function utf8ByteLength(value){value=String(value==null?"":value);try{return unescape(encodeURIComponent(value)).length}catch(e){return value.length}}\n',
    '''function utf8ByteLength(value){value=String(value==null?"":value);try{return unescape(encodeURIComponent(value)).length}catch(e){return value.length}}\nfunction transformValueMeta(value){if(Array.isArray(value))return "arrayLength="+value.length;if(typeof value==="string")return "stringBytes="+utf8ByteLength(value);if(value&&typeof value==="object")return "objectKeys="+Object.keys(value).length;return "type="+typeof value}\n''',
    "transform metadata helper",
)
replace_once(
    adapter,
    'ops=Array.isArray(ops)?ops:[ops];for(var i=0;i<ops.length;i++)value=applyTransformOperation(value,ops[i],ctx,stage+".op"+(i+1));\n if(spec.into)ctx.vars[String(spec.into)]=value;ctx.last=value;return value;\n',
    '''ops=Array.isArray(ops)?ops:[ops];log("TRANSFORM_START",stage,"ops="+ops.length,transformValueMeta(value));\n for(var i=0;i<ops.length;i++){var rawOp=ops[i],opName=String((typeof rawOp==="string"?rawOp:(rawOp&& (rawOp.op||rawOp.operation)))||"");log("TRANSFORM_OP",stage,"index="+(i+1),"op="+opName,transformValueMeta(value));value=applyTransformOperation(value,rawOp,ctx,stage+".op"+(i+1))}\n log("TRANSFORM_DONE",stage,transformValueMeta(value));\n if(spec.into)ctx.vars[String(spec.into)]=value;ctx.last=value;return value;\n''',
    "transform checkpoints",
)

importer = Path("source-lua/src/main/kotlin/vn/nghetruyen/source/lua/NativeLuaSourceImporter.kt")
replace_once(
    importer,
    '            memoryBudgetBytes = 32 * 1024 * 1024,\n            actionTimeoutMs = 50_000,\n',
    '            memoryBudgetBytes = 64 * 1024 * 1024,\n            actionTimeoutMs = 50_000,\n',
    "native lua imported memory headroom",
)

runtime = Path("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt")
replace_once(
    runtime,
    '                    "instructionBudget" to manifest.runtime.instructionBudget.toString(),\n                    "memoryBudgetBytes" to manifest.runtime.memoryBudgetBytes.toString(),\n',
    '''                    "instructionBudget" to manifest.runtime.instructionBudget.toString(),\n                    "memoryBudgetBytes" to manifest.runtime.memoryBudgetBytes.toString(),\n                    "effectiveMemoryBudgetBytes" to (if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) maxOf(manifest.runtime.memoryBudgetBytes, 64 * 1024 * 1024) else manifest.runtime.memoryBudgetBytes).toString(),\n                    "hardInstructionLimit" to (manifest.runtime.instructionBudget.toLong() * if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) 64L else 16L).toString(),\n''',
    "runtime budget diagnostics",
)
replace_once(
    runtime,
    '            maxHeapGrowthBytes = manifest.runtime.memoryBudgetBytes.toLong(),\n            maxResultUnits = manifest.runtime.memoryBudgetBytes.coerceAtLeast(1),\n            maxCollectionItems = 20_000,\n            maxValueDepth = 96,\n            languageVersion = Context.VERSION_ES6,\n',
    '''            maxHeapGrowthBytes = (if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) maxOf(manifest.runtime.memoryBudgetBytes, 64 * 1024 * 1024) else manifest.runtime.memoryBudgetBytes).toLong(),\n            maxResultUnits = (if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) maxOf(manifest.runtime.memoryBudgetBytes, 64 * 1024 * 1024) else manifest.runtime.memoryBudgetBytes).coerceAtLeast(1),\n            maxCollectionItems = 20_000,\n            maxValueDepth = 96,\n            languageVersion = Context.VERSION_ES6,\n            hardInstructionMultiplier = if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) 64 else 16,\n''',
    "native lua Rhino headroom",
)

test = Path("source-lua/src/test/kotlin/vn/nghetruyen/source/lua/SangTacVietTocBudgetRegressionTest.kt")
if test.exists():
    raise SystemExit(f"test already exists: {test}")
test.write_text(r'''package vn.nghetruyen.source.lua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceNetworkBroker
import vn.nghetruyen.source.api.SourceNetworkResponse
import vn.nghetruyen.source.api.SourceNetworkTiming
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.runtime.MapSourceResourceProvider
import vn.nghetruyen.source.vbook.VBookJsRuntime
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class SangTacVietTocBudgetRegressionTest {
    @Test
    fun realisticTocPayloadCompletesThroughExactNativeSourceFixture() {
        val sourceBytes = requireNotNull(javaClass.getResourceAsStream("/xpk-defaults/nguon_sangtacviet_native.lua.gz"))
            .use { compressed -> GZIPInputStream(compressed).use { it.readBytes() } }
        val (pack, _) = NativeLuaArchiveImporter.import(ByteArrayInputStream(sourceBytes))

        // New imports get explicit Native-Lua headroom. Runtime also applies the same floor to old
        // installed manifests, which may still contain the historical 32 MiB value.
        assertEquals(64 * 1024 * 1024, pack.manifest.runtime.memoryBudgetBytes)
        val oldInstalledManifest = pack.manifest.copy(
            runtime = pack.manifest.runtime.copy(memoryBudgetBytes = 32 * 1024 * 1024),
        )

        val responseBody = realisticChapterApiResponse()
        val responseBytes = responseBody.toByteArray(Charsets.UTF_8)
        assertTrue("fixture should resemble the 55-69 KiB device response: ${responseBytes.size}", responseBytes.size in 50 * 1024..90 * 1024)

        val network = SourceNetworkBroker { _, request ->
            SourcePlatformResult.Success(SourceNetworkResponse(
                statusCode = 200,
                finalUrl = request.url,
                headers = mapOf("content-type" to listOf("application/json; charset=utf-8")),
                body = responseBytes,
                charsetName = "UTF-8",
                timing = SourceNetworkTiming(0L, 1L),
                traceId = request.traceId,
                requestUrl = request.url,
            ))
        }
        val runtime = VBookJsRuntime(
            brokers = SourceCapabilityBrokers(
                network = network,
                nativeHooks = LuaNativeHookBroker(),
            ),
        )
        val storyUrl = "https://sangtacviet.com/truyen/fanqie/1/7636340618855189528/"
        val request = SourceActionRequest(
            sourceId = oldInstalledManifest.id,
            action = SourceActionName.TOC,
            input = JsonValue.Obj(linkedMapOf("url" to JsonValue.Str(storyUrl))),
            traceId = "stv-toc-budget-regression",
        )

        val result = runtime.execute(oldInstalledManifest, MapSourceResourceProvider(pack.entries), request)
        assertTrue(
            when (result) {
                is SourcePlatformResult.Success -> "success"
                is SourcePlatformResult.Failure -> "${result.error.code}: ${result.error.message}"
            },
            result is SourcePlatformResult.Success,
        )
        val response = (result as SourcePlatformResult.Success).value
        val encoded = JsonCodec.stringify(response.value)
        assertTrue("expected first chapter in normalized output", encoded.contains("Chương 1"))
        assertTrue("expected last chapter in normalized output", encoded.contains("Chương 100"))
        assertTrue("expected chapter URL rooted at the story", encoded.contains(storyUrl))
    }

    private fun realisticChapterApiResponse(): String {
        val padding = "x".repeat(250)
        val records = (1..100).joinToString("-//-") { index ->
            "$padding-/-$index-/-Chương $index-/-$padding"
        }
        return JsonCodec.stringify(JsonValue.Obj(linkedMapOf(
            "data" to JsonValue.Str(records),
        )))
    }
}
''', encoding="utf-8")

print("Applied STV TOC runtime patch and regression test")
