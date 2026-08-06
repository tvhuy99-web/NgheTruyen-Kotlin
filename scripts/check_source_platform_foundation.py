#!/usr/bin/env python3
"""Offline Source Platform 2 foundation gate.

Covers package path safety, signed hash manifest, strict manifest parsing,
atomic source store/rollback, declarative runtime budgets, and diagnostic redaction.
"""
from __future__ import annotations

import base64
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")


def run(command: list[str], cwd: Path = ROOT) -> None:
    completed = subprocess.run(command, cwd=cwd)
    if completed.returncode:
        raise SystemExit(completed.returncode)


def source_files(module: str) -> list[str]:
    return [str(p) for p in sorted((ROOT / module / "src/main/kotlin").rglob("*.kt"))]


def manifest(version: str, browser: bool = False) -> str:
    return f'''{{
      "schemaVersion":2,
      "id":"vn.nghetruyen.sources.foundation",
      "name":"Foundation",
      "version":"{version}",
      "apiVersion":2,
      "runtime":{{"mode":"DECLARATIVE","instructionBudget":10000,"memoryBudgetBytes":4194304,"actionTimeoutMs":5000}},
      "origins":["https://example.org"],
      "capabilities":{{"cookies":"NONE","browser":{{"navigate":{str(browser).lower()}}},"storageBytes":0,"crypto":[]}},
      "actions":{{
        "search":{{"entry":"actions/search.json"}},
        "detail":{{"entry":"actions/detail.json"}},
        "toc":{{"entry":"actions/toc.json"}},
        "chapter":{{"entry":"actions/chapter.json"}}
      }},
      "fixtures":[{{"name":"search fixture","action":"SEARCH","input":"gio","expected":"fixtures/search.expected.json"}}]
    }}'''


def build_pack(path: Path, private_key: ec.EllipticCurvePrivateKey, version: str, browser: bool = False) -> None:
    files: dict[str, bytes] = {
        "source.json": manifest(version, browser).encode(),
        "actions/search.json": b'{"version":1,"steps":[{"op":"resourceJson","path":"data.json","as":"root"},{"op":"path","from":"root","path":"items","as":"items"},{"op":"filterText","from":"items","fields":["title"],"queryInput":"query","as":"filtered"},{"op":"paginate","from":"filtered","pageInput":"page","pageSize":20,"as":"result"},{"op":"emit","from":"result"}]}',
        "actions/detail.json": b'{"version":1,"steps":[{"op":"constant","value":null,"as":"result"},{"op":"emit","from":"result"}]}',
        "actions/toc.json": b'{"version":1,"steps":[{"op":"constant","value":[],"as":"result"},{"op":"emit","from":"result"}]}',
        "actions/chapter.json": b'{"version":1,"steps":[{"op":"constant","value":null,"as":"result"},{"op":"emit","from":"result"}]}',
        "data.json": b'{"items":[{"title":"Mien Gio"},{"title":"Mat Trang"}]}',
        "fixtures/search.expected.json": b'{"items":[{"title":"Mien Gio"}]}',
    }
    hashes_text = "".join(f"{hashlib.sha256(files[name]).hexdigest()}  {name}\n" for name in sorted(files))
    files["FILES.sha256"] = hashes_text.encode("ascii")
    files["SIGNATURE.es256"] = private_key.sign(files["FILES.sha256"], ec.ECDSA(hashes.SHA256()))
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name)
            info.date_time = (2026, 8, 3, 0, 0, 0)
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)


def make_tampered(source: Path, output: Path) -> None:
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            data = src.read(info.filename)
            if info.filename == "data.json":
                data += b" "
            dst.writestr(info, data)


def make_traversal(output: Path) -> None:
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("../escape.txt", b"bad")
        archive.writestr("source.json", b"{}")


def main() -> None:
    required = [
        "source-api", "source-diagnostics", "source-package", "source-store", "source-runtime",
    ]
    for module in required:
        assert (ROOT / module / "build.gradle.kts").is_file(), module
    asset = ROOT / "app/src/main/assets/sourcepacks/demo.ntsource"
    assert asset.is_file() and asset.stat().st_size > 1000
    assert not list(ROOT.rglob("*.pem")), "Private/signing PEM material must never ship in the project"

    wiring = {
        "settings.gradle.kts": [":source-api", ":source-package", ":source-store", ":source-runtime", ":source-diagnostics", ":source-network", ":source-repository"],
        "app/src/main/java/vn/nghetruyen/app/AppContainer.kt": ["SourcePlatformManager", "activeStorySources"],
        "app/src/main/java/vn/nghetruyen/app/sources/SourceRegistry.kt": ["refreshSourcePacks", "distinctBy"],
        "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt": ["prepareSourcePack", "confirmSourcePackInstall", "rollbackSourcePack"],
        "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt": ["CÀI .NTSOURCE / VBOOK / LUA API 2", "THÊM / LÀM MỚI REPOSITORY", "ROLLBACK PHIÊN BẢN NGUỒN"],
    }
    for relative, tokens in wiring.items():
        text = (ROOT / relative).read_text(encoding="utf-8")
        for token in tokens:
            assert token in text, f"{relative}: missing {token}"

    if not KOTLINC:
        print("SOURCE_PLATFORM_KOTLIN_COMPILE_SKIPPED: kotlinc not found")
        print("SOURCE_PLATFORM_FOUNDATION_CHECK_OK")
        return

    with tempfile.TemporaryDirectory(prefix="nghe-source-platform-") as temp_name:
        temp = Path(temp_name)
        stub_root = temp / "stubs"
        jsoup_main = stub_root / "org/jsoup/Jsoup.kt"
        jsoup_nodes = stub_root / "org/jsoup/nodes/Nodes.kt"
        jsoup_elements = stub_root / "org/jsoup/select/Elements.kt"
        for path in (jsoup_main, jsoup_nodes, jsoup_elements):
            path.parent.mkdir(parents=True, exist_ok=True)
        jsoup_main.write_text("""package org.jsoup
import org.jsoup.nodes.Document
object Jsoup { fun parse(html:String, baseUri:String=""):Document=Document(); fun parseBodyFragment(html:String):Document=Document() }
""", encoding="utf-8")
        jsoup_nodes.write_text("""package org.jsoup.nodes
import org.jsoup.select.Elements
open class Element {
 fun select(css:String)=Elements(); fun selectFirst(css:String):Element?=Element(); fun text()=""; fun html()=""; fun outerHtml()=""
 fun attr(name:String)=""; fun absUrl(name:String)=""; fun clone():Element=Element(); fun wholeText()=""; fun after(value:String):Element=this
}
class Document:Element()
""", encoding="utf-8")
        jsoup_elements.write_text("""package org.jsoup.select
import org.jsoup.nodes.Element
class Elements():ArrayList<Element>() {
 constructor(element:Element):this(){ add(element) }
 fun text()=""; fun remove():Elements=this
}
""", encoding="utf-8")
        precompiled_dir: Path | None = None
        if "--precompiled-dir" in sys.argv:
            index = sys.argv.index("--precompiled-dir")
            if index + 1 >= len(sys.argv):
                raise SystemExit("--precompiled-dir cần đường dẫn")
            precompiled_dir = Path(sys.argv[index + 1]).resolve()
        dependency_graph = {
            "source-api": [],
            "source-diagnostics": ["source-api"],
            "source-package": ["source-api", "source-diagnostics"],
            "source-store": ["source-api", "source-package", "source-diagnostics"],
            "source-runtime": ["source-api", "source-diagnostics"],
        }
        if precompiled_dir:
            stubs_jar = precompiled_dir / "stubs.jar"
            module_jars = {module: precompiled_dir / f"{module}.jar" for module in required}
            for path in [stubs_jar, *module_jars.values()]:
                if not path.is_file():
                    raise SystemExit(f"Thiếu precompiled jar: {path}")
            print(f"SOURCE_PLATFORM_FOUNDATION_PRECOMPILED={precompiled_dir}", flush=True)
        else:
            stubs_jar = temp / "source-platform-stubs.jar"
            print("SOURCE_PLATFORM_FOUNDATION_COMPILE=stubs", flush=True)
            run([KOTLINC, str(jsoup_main), str(jsoup_nodes), str(jsoup_elements), "-d", str(stubs_jar)])
            module_jars: dict[str, Path] = {}
            for module in required:
                print(f"SOURCE_PLATFORM_FOUNDATION_COMPILE={module}", flush=True)
                module_jar = temp / f"{module}.jar"
                dependencies = [module_jars[name] for name in dependency_graph[module]]
                if module == "source-runtime":
                    dependencies.insert(0, stubs_jar)
                run([
                    KOTLINC,
                    *source_files(module),
                    *(["-classpath", os.pathsep.join(str(path) for path in dependencies)] if dependencies else []),
                    "-d", str(module_jar),
                ])
                module_jars[module] = module_jar
        classpath = os.pathsep.join(str(path) for path in [stubs_jar, *module_jars.values()])

        key = ec.generate_private_key(ec.SECP256R1())
        public_der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        pub_b64 = base64.b64encode(public_der).decode("ascii")
        v1 = temp / "v1.ntsource"
        v2 = temp / "v2.ntsource"
        tampered = temp / "tampered.ntsource"
        traversal = temp / "traversal.ntsource"
        build_pack(v1, key, "1.0.0", browser=False)
        build_pack(v2, key, "1.1.0", browser=True)
        make_tampered(v1, tampered)
        make_traversal(traversal)

        smoke = temp / "Smoke.kt"
        smoke.write_text(r'''
import java.io.File
import vn.nghetruyen.source.api.*
import vn.nghetruyen.source.packagekit.*
import vn.nghetruyen.source.store.*
import vn.nghetruyen.source.runtime.*
import vn.nghetruyen.source.diagnostics.*

fun main(args:Array<String>) {
    val key = SourceTrustKey.fromBase64("test-key", SourceSignatureAlgorithm.ECDSA_P256_SHA256, args[4])
    val verifier = SourcePackArchiveVerifier()
    fun verify(path:String): SourcePlatformResult<VerifiedSourcePack> = File(path).inputStream().use { verifier.verify(it, listOf(key)) }
    val p1 = (verify(args[0]) as SourcePlatformResult.Success).value
    val p2 = (verify(args[1]) as SourcePlatformResult.Success).value
    check((verify(args[2]) as SourcePlatformResult.Failure).error.code == SourceErrorCode.PACKAGE_HASH_MISMATCH)
    check((verify(args[3]) as SourcePlatformResult.Failure).error.code == SourceErrorCode.PACKAGE_PATH_INVALID)

    val recorder = BoundedDiagnosticRecorder(64, DiagnosticLevel.VERBOSE)
    val root = File(args[5]).also { it.deleteRecursively() }
    val store = SourcePackStore(root, recorder)
    check(store.install(p1) is SourcePlatformResult.Success)
    val diff = store.permissionDiff(p2.manifest)
    check(diff.requiresApproval && "navigate" in diff.browserEscalations)
    check(store.install(p2) is SourcePlatformResult.Success)
    check(store.load(p1.manifest.id)!!.activeVersion.toString() == "1.1.0")
    check(store.rollback(p1.manifest.id) is SourcePlatformResult.Success)
    check(store.load(p1.manifest.id)!!.activeVersion.toString() == "1.0.0")

    val active = store.readActivePack(p1.manifest.id)!!
    val runtime = DeclarativeSourceRuntime(recorder)
    val request = SourceActionRequest(active.manifest.id, SourceActionName.SEARCH, JsonValue.Obj(linkedMapOf(
        "query" to JsonValue.Str("gio"), "page" to JsonValue.Num(1.0, "1")
    )))
    val resources = MapSourceResourceProvider(active.entries)
    val fixtureReport = SourceFixtureRunner(runtime, recorder).run(active.manifest, resources)
    check(fixtureReport.allPassed && fixtureReport.passed == 1)
    val result = runtime.execute(active.manifest, resources, request)
    check(result is SourcePlatformResult.Success)
    val output = result.value.value as JsonValue.Obj
    check(output.array("items")!!.values.size == 1)

    recorder.emit(DiagnosticEvent(1, "trace", p1.manifest.id, category=DiagnosticCategory.SECURITY, name="secret", attributes=mapOf("Authorization" to "Bearer do-not-log")))
    val exported = DiagnosticJsonExporter.export(recorder.snapshot()).toString(Charsets.UTF_8)
    check("do-not-log" !in exported)
    check(recorder.snapshot().isNotEmpty())

    val activeData = File(args[5], "sources/${p1.manifest.id}/versions/1.0.0/data.json")
    activeData.appendText(" ")
    check(store.readActivePack(p1.manifest.id) == null)
    println("SOURCE_PLATFORM_FOUNDATION_SMOKE_OK events=" + recorder.snapshot().size)
}
''', encoding="utf-8")
        smoke_jar = temp / "smoke.jar"
        print("SOURCE_PLATFORM_FOUNDATION_COMPILE=smoke", flush=True)
        run([KOTLINC, "-classpath", classpath, str(smoke), "-include-runtime", "-d", str(smoke_jar)])
        runtime_cp = os.pathsep.join([str(smoke_jar), classpath])
        print("SOURCE_PLATFORM_FOUNDATION_RUN=smoke", flush=True)
        run(["java", "-cp", runtime_cp, "SmokeKt", str(v1), str(v2), str(tampered), str(traversal), pub_b64, str(temp / "store")])

    print("SOURCE_PLATFORM_FOUNDATION_CHECK_OK")


if __name__ == "__main__":
    main()
