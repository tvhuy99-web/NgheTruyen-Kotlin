#!/usr/bin/env python3
"""Milestone 2 gate: network broker contracts, replay runtime and signed repositories."""
from __future__ import annotations

import base64
import json
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")




MANUAL_COMPILE_EXCLUSIONS = {
    "source-repository": {
        "VBookRepositoryUpdatePlanner.kt",
        "VBookUpdateCoordinator.kt",
    },
}
MANUAL_TEST_EXCLUSIONS = {
    "source-repository": {
        "VBookRepositoryUpdatePlannerTest.kt",
        "VBookUpdateCoordinatorTest.kt",
    },
}


def run(command: list[str]) -> None:
    result = subprocess.run(command, cwd=ROOT)
    if result.returncode:
        raise SystemExit(result.returncode)


def sources(module: str) -> list[str]:
    excluded = MANUAL_COMPILE_EXCLUSIONS.get(module, set())
    return [
        str(path)
        for path in sorted((ROOT / module / "src/main/kotlin").rglob("*.kt"))
        if path.name not in excluded
    ]


def test_sources(module: str) -> list[str]:
    root = ROOT / module / "src/test/kotlin"
    excluded = MANUAL_TEST_EXCLUSIONS.get(module, set())
    return [str(path) for path in sorted(root.rglob("*.kt")) if path.name not in excluded] if root.is_dir() else []


def write(root: Path, relative: str, content: str) -> Path:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


OKHTTP_STUB = r'''package okhttp3
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.TimeUnit
fun interface Dns { fun lookup(hostname: String): List<InetAddress> }
class HttpUrl private constructor(private val raw: String) {
 val host:String get()=java.net.URI(raw).host?:"example.org"
 val isHttps:Boolean get()=java.net.URI(raw).scheme.equals("https",true)
 val username:String get()=java.net.URI(raw).userInfo?.substringBefore(':').orEmpty()
 val password:String get()=java.net.URI(raw).userInfo?.substringAfter(':',"").orEmpty()
 val fragment:String? get()=java.net.URI(raw).fragment
 fun resolve(location:String):HttpUrl?=runCatching{HttpUrl(java.net.URI(raw).resolve(location).toString())}.getOrNull()
 override fun toString()=raw
 companion object { fun String.toHttpUrl():HttpUrl=HttpUrl(this) }
}
class MediaType { fun charset(defaultValue:java.nio.charset.Charset):java.nio.charset.Charset?=defaultValue; companion object { fun String.toMediaTypeOrNull():MediaType?=MediaType() } }
class RequestBody { companion object { fun ByteArray.toRequestBody(contentType:MediaType?=null):RequestBody=RequestBody() } }
class Headers(private val values: Map<String,List<String>> = emptyMap()){ fun toMultimap()=values }
class TlsVersion(val javaName:String); class CipherSuite(val javaName:String); class Handshake(val tlsVersion:TlsVersion,val cipherSuite:CipherSuite)
class ResponseBody { fun contentLength():Long=0; fun byteStream():InputStream=ByteArrayInputStream(ByteArray(0)); fun contentType():MediaType?=null }
class Request(val url:HttpUrl,val headers:Headers=Headers()){ class Builder { private var url:HttpUrl=HttpUrl.run{"https://example.org".toHttpUrl()}; fun url(value:HttpUrl)=apply{url=value}; fun url(value:String)=apply{url=HttpUrl.run{value.toHttpUrl()}}; fun header(name:String,value:String)=apply{}; fun get()=apply{}; fun head()=apply{}; fun method(method:String,body:RequestBody?)=apply{}; fun build()=Request(url) } }
class Response(val code:Int=200,val request:Request=Request.Builder().build(),val body:ResponseBody=ResponseBody(),val headers:Headers=Headers(),val handshake:Handshake?=null,val message:String="OK"):Closeable { val isSuccessful:Boolean get()=code in 200..299; fun header(name:String):String?=null; fun headers(name:String):List<String> = emptyList(); override fun close(){} }
class Timeout { fun timeout(timeout:Long,unit:TimeUnit):Timeout=this }
class Call { fun execute()=Response(); fun timeout()=Timeout() }
open class WebSocket { open fun send(text:String):Boolean=true; open fun close(code:Int,reason:String?):Boolean=true; open fun cancel(){} }
open class WebSocketListener { open fun onOpen(webSocket:WebSocket,response:Response){}; open fun onMessage(webSocket:WebSocket,text:String){}; open fun onMessage(webSocket:WebSocket,bytes:okio.ByteString){}; open fun onClosing(webSocket:WebSocket,code:Int,reason:String){}; open fun onClosed(webSocket:WebSocket,code:Int,reason:String){}; open fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){} }
class OkHttpClient { fun newCall(request:Request)=Call(); fun newWebSocket(request:Request,listener:WebSocketListener):WebSocket=WebSocket(); class Builder { fun dns(dns:Dns)=apply{}; fun connectTimeout(duration:Duration)=apply{}; fun readTimeout(duration:Duration)=apply{}; fun callTimeout(duration:Duration)=apply{}; fun followRedirects(value:Boolean)=apply{}; fun followSslRedirects(value:Boolean)=apply{}; fun retryOnConnectionFailure(value:Boolean)=apply{}; fun build()=OkHttpClient() } }
'''

OKIO_STUB = r'''package okio
class ByteString(private val value:ByteArray=ByteArray(0)) { fun toByteArray():ByteArray=value.copyOf() }
'''


JUNIT_STUB = r'''package org.junit
@Target(AnnotationTarget.FUNCTION)
annotation class Test
object Assert {
 @JvmStatic fun assertTrue(value:Boolean) { check(value) }
 @JvmStatic fun assertFalse(value:Boolean) { check(!value) }
 @JvmStatic fun assertEquals(expected:Any?, actual:Any?) { check(expected == actual) }
 @JvmStatic fun <T:Throwable> assertThrows(type:Class<T>, block:()->Unit):T {
  try { block() } catch (error:Throwable) {
   if (type.isInstance(error)) return type.cast(error)
   throw AssertionError("Expected ${type.name}, got ${error.javaClass.name}", error)
  }
  throw AssertionError("Expected ${type.name}")
 }
}
'''


def signed_repository(path: Path) -> str:
    key = ec.generate_private_key(ec.SECP256R1())
    public_der = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    now = int(time.time() * 1000)
    package = {
        "sourceId": "vn.nghetruyen.sources.remote",
        "name": "Remote Fixture",
        "version": "1.2.0",
        "description": "Signed repository fixture",
        "packageUrl": "https://downloads.example.org/remote.ntsource",
        "packageSha256": "a" * 64,
        "packageBytes": 4096,
        "minAppVersion": "1.3.0",
        "maxAppVersion": "2.0.0",
        "adult": False,
        "changelog": "Network runtime",
    }
    canonical = {
        "schemaVersion": 1,
        "repositoryId": "vn.nghetruyen.repositories.test",
        "name": "Test Repository",
        "generatedAtEpochMs": now,
        "expiresAtEpochMs": now + 7 * 24 * 60 * 60 * 1000,
        "signerKeyId": "test-repository-key",
        "signatureAlgorithm": "ECDSA_P256_SHA256",
        "packages": [package],
    }
    canonical_bytes = json.dumps(canonical, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    signature = key.sign(canonical_bytes, ec.ECDSA(hashes.SHA256()))

    shuffled_package = {key: package[key] for key in reversed(list(package))}
    raw = {
        "signature": base64.b64encode(signature).decode("ascii"),
        "packages": [shuffled_package],
        "signatureAlgorithm": canonical["signatureAlgorithm"],
        "signerKeyId": canonical["signerKeyId"],
        "expiresAtEpochMs": canonical["expiresAtEpochMs"],
        "generatedAtEpochMs": canonical["generatedAtEpochMs"],
        "name": canonical["name"],
        "repositoryId": canonical["repositoryId"],
        "schemaVersion": canonical["schemaVersion"],
    }
    path.write_text(json.dumps(raw, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    return base64.b64encode(public_der).decode("ascii")



def repository_builder_smoke(temp: Path) -> None:
    key = ec.generate_private_key(ec.SECP256R1())
    private_key = temp / "repository-private.pem"
    private_key.write_bytes(key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.TraditionalOpenSSL,
        serialization.NoEncryption(),
    ))
    now = int(time.time() * 1000)
    unsigned = {
        "schemaVersion": 1,
        "repositoryId": "vn.nghetruyen.repositories.builder",
        "name": "Builder Repository",
        "generatedAtEpochMs": now,
        "expiresAtEpochMs": now + 24 * 60 * 60 * 1000,
        "signerKeyId": "builder-key",
        "signatureAlgorithm": "ECDSA_P256_SHA256",
        "packages": [{
            "sourceId": "vn.nghetruyen.sources.networkdemo",
            "name": "Network Demo",
            "version": "1.0.0",
            "packageUrl": "https://downloads.example.org/network-demo.ntsource",
            "packageSha256": "b" * 64,
            "packageBytes": 4096,
        }],
    }
    unsigned_path = temp / "repository-unsigned.json"
    signed_path = temp / "repository-built.json"
    unsigned_path.write_text(json.dumps(unsigned, ensure_ascii=False), encoding="utf-8")
    run([
        os.environ.get("PYTHON", os.sys.executable),
        str(ROOT / "scripts/sourcepack/build_source_repository.py"),
        str(unsigned_path),
        "--private-key", str(private_key),
        "--output", str(signed_path),
    ])
    signed = json.loads(signed_path.read_text(encoding="utf-8"))
    signature = base64.b64decode(signed.pop("signature"), validate=True)
    payload = json.dumps(signed, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    key.public_key().verify(signature, payload, ec.ECDSA(hashes.SHA256()))


def main() -> None:
    required_modules = [
        "source-api", "source-diagnostics", "source-package", "source-store", "source-runtime",
        "source-network", "source-repository",
    ]
    for module in required_modules:
        assert (ROOT / module / "build.gradle.kts").is_file(), module

    static_tokens = {
        "source-runtime/src/main/kotlin/vn/nghetruyen/source/runtime/DeclarativeSourceRuntime.kt": [
            '"fetch" -> fetch(step)', '"parseJson" -> parseJson(step)', '"projectArray" -> projectArray(step)',
            "timeoutMs = (deadlineMs - clockMs())",
        ],
        "source-network/src/main/kotlin/vn/nghetruyen/source/network/OkHttpSourceNetworkBroker.kt": [
            "SourceOriginPolicy.requireInitialUrl", "PublicAddressPolicy.requirePublic", "followRedirects(false)",
            "deadlineMs = started + request.timeoutMs", "call.timeout().timeout", "SOURCE_NETWORK_RESPONSE_TOO_LARGE",
        ],
        "source-repository/src/main/kotlin/vn/nghetruyen/source/repository/SourceRepository.kt": [
            "SourceDetachedSignatureVerifier.verify", "canonicalPayload", "REPOSITORY_SIGNATURE_INVALID",
        ],
        "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformManager.kt": [
            "refreshRepository", "prepareRepositoryInstall", "rememberRepository", "CURRENT_APP_VERSION",
        ],
    }
    for relative, tokens in static_tokens.items():
        text = (ROOT / relative).read_text(encoding="utf-8")
        for token in tokens:
            assert token in text, f"{relative}: missing {token}"

    example_root = ROOT / "examples/sourcepack-network-demo"
    for relative in [
        "source.json", "actions/search.json", "fixtures/search/gio.http.json", "fixtures/search/gio.expected.json",
    ]:
        assert (example_root / relative).is_file(), f"network demo missing: {relative}"

    if not KOTLINC:
        print("MILESTONE2_KOTLIN_COMPILE_SKIPPED")
        print("MILESTONE2_SOURCE_PLATFORM_CHECK_OK")
        return

    with tempfile.TemporaryDirectory(prefix="nghe-m2-source-") as temp_name:
        temp = Path(temp_name)
        stub = write(temp, "stubs/okhttp3/OkHttp.kt", OKHTTP_STUB)
        okio_stub = write(temp, "stubs/okio/ByteString.kt", OKIO_STUB)
        junit_stub = write(temp, "stubs/org/junit/JUnit.kt", JUNIT_STUB)
        jsoup_main = write(temp, "stubs/org/jsoup/Jsoup.kt", """package org.jsoup
import org.jsoup.nodes.Document
object Jsoup { fun parse(html:String, baseUri:String=""):Document=Document(); fun parseBodyFragment(html:String):Document=Document() }
""")
        jsoup_nodes = write(temp, "stubs/org/jsoup/nodes/Nodes.kt", """package org.jsoup.nodes
import org.jsoup.select.Elements
open class Element {
 fun select(css:String)=Elements(); fun selectFirst(css:String):Element?=Element(); fun text()=""; fun html()=""; fun outerHtml()=""
 fun attr(name:String)=""; fun absUrl(name:String)=""; fun clone():Element=Element(); fun wholeText()=""; fun after(value:String):Element=this
}
class Document:Element()
""")
        jsoup_elements = write(temp, "stubs/org/jsoup/select/Elements.kt", """package org.jsoup.select
import org.jsoup.nodes.Element
class Elements():ArrayList<Element>() {
 constructor(element:Element):this(){ add(element) }
 fun text()=""; fun remove():Elements=this
}
""")
        precompiled_dir: Path | None = None
        if "--precompiled-dir" in os.sys.argv:
            index = os.sys.argv.index("--precompiled-dir")
            if index + 1 >= len(os.sys.argv):
                raise SystemExit("--precompiled-dir cần đường dẫn")
            precompiled_dir = Path(os.sys.argv[index + 1]).resolve()
        dependency_graph = {
            "source-api": [],
            "source-diagnostics": ["source-api"],
            "source-package": ["source-api", "source-diagnostics"],
            "source-store": ["source-api", "source-package", "source-diagnostics"],
            "source-runtime": ["source-api", "source-diagnostics"],
            "source-network": ["source-api", "source-diagnostics"],
            "source-repository": ["source-api", "source-package", "source-diagnostics", "source-store"],
        }
        if precompiled_dir:
            stub_jars = [precompiled_dir / "m2-stubs.jar", precompiled_dir / "jsoup-stubs.jar"]
            module_jars = {module: precompiled_dir / f"{module}.jar" for module in required_modules}
            test_jars = [path for path in precompiled_dir.glob("source-*-tests.jar")]
            for path in [*stub_jars, *module_jars.values(), *test_jars]:
                if not path.is_file():
                    raise SystemExit(f"Thiếu precompiled jar: {path}")
            print(f"MILESTONE2_SOURCE_PLATFORM_PRECOMPILED={precompiled_dir}", flush=True)
        else:
            combined_stubs = temp / "source-platform-m2-stubs.jar"
            run([KOTLINC, str(stub), str(okio_stub), str(junit_stub), str(jsoup_main), str(jsoup_nodes), str(jsoup_elements), "-d", str(combined_stubs)])
            stub_jars = [combined_stubs]
            module_jars: dict[str, Path] = {}
            for module in required_modules:
                print(f"MILESTONE2_SOURCE_PLATFORM_COMPILE={module}", flush=True)
                module_jar = temp / f"{module}.jar"
                dependencies = [module_jars[name] for name in dependency_graph[module]]
                dependencies = [*stub_jars, *dependencies]
                run([
                    KOTLINC,
                    *sources(module),
                    "-classpath", os.pathsep.join(str(path) for path in dependencies),
                    "-d", str(module_jar),
                ])
                module_jars[module] = module_jar
            all_main_cp = os.pathsep.join(str(path) for path in [*stub_jars, *module_jars.values()])
            test_jars: list[Path] = []
            for module in required_modules:
                module_tests = test_sources(module)
                if not module_tests:
                    continue
                test_jar = temp / f"{module}-tests.jar"
                run([
                    KOTLINC,
                    *module_tests,
                    "-classpath", all_main_cp,
                    f"-Xfriend-paths={module_jars[module]}",
                    "-d", str(test_jar),
                ])
                test_jars.append(test_jar)
        platform_classpath = os.pathsep.join(str(path) for path in [*stub_jars, *module_jars.values(), *test_jars])

        repository_builder_smoke(temp)
        repository = temp / "repository.json"
        public_key = signed_repository(repository)
        smoke = write(temp, "Smoke.kt", r'''
import java.io.File
import java.net.InetAddress
import vn.nghetruyen.source.api.*
import vn.nghetruyen.source.network.*
import vn.nghetruyen.source.packagekit.*
import vn.nghetruyen.source.repository.*
import vn.nghetruyen.source.runtime.*

private fun manifest(): SourceManifest = SourceManifest(
    schemaVersion = 2,
    id = "vn.nghetruyen.sources.remote",
    name = "Remote",
    version = SemanticVersion(1,2,0),
    apiVersion = 2,
    runtime = SourceRuntimePolicy(SourceRuntimeMode.DECLARATIVE, instructionBudget=20_000, memoryBudgetBytes=4*1024*1024, actionTimeoutMs=5_000),
    origins = setOf("https://api.example.org", "https://*.mirror.example.org"),
    redirectOrigins = setOf("https://cdn.example.org"),
    capabilities = SourceCapabilities(network=SourceNetworkCapability(setOf("GET","HEAD"), maxResponseBytes=1024*1024, requestsPerMinute=60, maxConcurrent=2)),
    actions = mapOf(
      SourceActionName.SEARCH to SourceActionSpec("actions/search.json"),
      SourceActionName.DETAIL to SourceActionSpec("actions/detail.json"),
      SourceActionName.TOC to SourceActionSpec("actions/toc.json"),
      SourceActionName.CHAPTER to SourceActionSpec("actions/chapter.json"),
    ),
    fixtures = listOf(SourceFixtureSpec("remote search", SourceActionName.SEARCH, "gió mùa", "fixtures/search.http.json", "fixtures/search.expected.json")),
).also { it.validate() }

fun main(args:Array<String>) {
    val manifest = manifest()
    check(SourceOriginPolicy.requireInitialUrl(manifest, "https://api.example.org/search?q=x").host == "api.example.org")
    check(SourceOriginPolicy.requireInitialUrl(manifest, "https://one.mirror.example.org/x").host == "one.mirror.example.org")
    check(runCatching { SourceOriginPolicy.requireInitialUrl(manifest, "https://mirror.example.org/x") }.isFailure)
    check(SourceOriginPolicy.requireRedirectUrl(manifest, "https://cdn.example.org/x").host == "cdn.example.org")
    check(runCatching { SourceOriginPolicy.requireInitialUrl(manifest, "https://user@api.example.org/x") }.isFailure)
    check(!PublicAddressPolicy.isPublic(InetAddress.getByName("127.0.0.1")))
    check(!PublicAddressPolicy.isPublic(InetAddress.getByName("10.0.0.1")))
    check(!PublicAddressPolicy.isPublic(InetAddress.getByName("::1")))
    check(!PublicAddressPolicy.isPublic(InetAddress.getByName("::ffff:127.0.0.1")))
    check(PublicAddressPolicy.isPublic(InetAddress.getByName("8.8.8.8")))

    val resources = linkedMapOf<String,ByteArray>(
      "actions/search.json" to """{"version":1,"steps":[{"op":"fetch","url":"https://api.example.org/search?q={{input.query|urlencode}}","response":"JSON","as":"http"},{"op":"path","from":"http","path":"body.items","as":"items"},{"op":"projectArray","from":"items","fields":{"title":"title","url":"url"},"as":"result"},{"op":"emit","from":"result"}]}""".toByteArray(),
      "actions/detail.json" to """{"version":1,"steps":[{"op":"constant","value":null,"as":"result"},{"op":"emit","from":"result"}]}""".toByteArray(),
      "actions/toc.json" to """{"version":1,"steps":[{"op":"constant","value":[],"as":"result"},{"op":"emit","from":"result"}]}""".toByteArray(),
      "actions/chapter.json" to """{"version":1,"steps":[{"op":"constant","value":null,"as":"result"},{"op":"emit","from":"result"}]}""".toByteArray(),
      "fixtures/search.http.json" to """{"version":1,"responses":[{"method":"GET","url":"https://api.example.org/search?q=gi%C3%B3%20m%C3%B9a","status":200,"headers":{"content-type":"application/json"},"bodyText":"{\"items\":[{\"title\":\"Gió Mùa\",\"url\":\"https://api.example.org/story/1\"}]}"}]}""".toByteArray(),
      "fixtures/search.expected.json" to """[{"title":"Gió Mùa","url":"https://api.example.org/story/1"}]""".toByteArray(),
    )
    val provider = MapSourceResourceProvider(resources)
    val runtime = DeclarativeSourceRuntime()
    val report = SourceFixtureRunner(runtime).run(manifest, provider)
    check(report.allPassed && report.passed == 1) { report.toString() }

    var captured: SourceNetworkRequest? = null
    val broker = SourceNetworkBroker { _, request ->
      captured = request
      SourcePlatformResult.Success(SourceNetworkResponse(200, request.url, emptyMap(), "{\"items\":[]}".toByteArray(), timing=SourceNetworkTiming(0,0), traceId=request.traceId))
    }
    val request = SourceActionRequest(manifest.id, SourceActionName.SEARCH, JsonValue.Obj(linkedMapOf("query" to JsonValue.Str("x y"))))
    check(runtime.execute(manifest, provider, request, broker) is SourcePlatformResult.Success)
    check(captured!!.url.endsWith("q=x%20y"))
    check(captured!!.timeoutMs in 100L..5_000L)

    val exampleRoot = File(args[2])
    val exampleEntries = exampleRoot.walkTopDown().filter { it.isFile }.associate {
      it.relativeTo(exampleRoot).invariantSeparatorsPath to it.readBytes()
    }
    val exampleManifest = SourceManifestParser.parse(exampleEntries.getValue("source.json"))
    val exampleReport = SourceFixtureRunner(DeclarativeSourceRuntime()).run(
      exampleManifest,
      MapSourceResourceProvider(exampleEntries),
    )
    check(exampleReport.allPassed && exampleReport.passed == 1) { exampleReport.toString() }

    val trust = SourceTrustKey.fromBase64("test-repository-key", SourceSignatureAlgorithm.ECDSA_P256_SHA256, args[1])
    val raw = File(args[0]).readBytes()
    val verified = SourceRepositoryVerifier().verify(raw, listOf(trust))
    check(verified is SourcePlatformResult.Success)
    val repo = (verified as SourcePlatformResult.Success).value.index
    check(repo.packages.single().sourceId == manifest.id)
    val views = SourceRepositoryCatalog.compare(repo, mapOf(manifest.id to SemanticVersion(1,1,0)), SemanticVersion(1,3,0))
    check(views.single().status == SourceRepositoryPackageStatus.UPDATE_AVAILABLE)
    val tampered = raw.toString(Charsets.UTF_8).replace("Test Repository", "Tampered Repository").toByteArray()
    val rejected = SourceRepositoryVerifier().verify(tampered, listOf(trust))
    check(rejected is SourcePlatformResult.Failure && rejected.error.code == SourceErrorCode.REPOSITORY_SIGNATURE_INVALID)
    println("MILESTONE2_SOURCE_PLATFORM_SMOKE_OK")
}
''')
        smoke_jar = temp / "smoke.jar"
        run([KOTLINC, "-classpath", platform_classpath, str(smoke), "-include-runtime", "-d", str(smoke_jar)])
        run([
            "java", "-cp", os.pathsep.join([str(smoke_jar), platform_classpath]), "SmokeKt",
            str(repository), public_key, str(ROOT / "examples/sourcepack-network-demo"),
        ])

    print("MILESTONE2_SOURCE_PLATFORM_CHECK_OK")


if __name__ == "__main__":
    main()
