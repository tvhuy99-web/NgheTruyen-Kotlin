#!/usr/bin/env python3
"""Offline P2 gate for Wikidich, Sang Tac Viet, sessions and health checks."""
from __future__ import annotations

import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")


def run(command: list[str]) -> None:
    completed = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
    if completed.returncode:
        print(completed.stdout)
        print(completed.stderr)
        raise SystemExit(completed.returncode)


def write(root: Path, path: str, text: str) -> Path:
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")
    return target


def fixture_gate() -> None:
    wiki = ROOT / "app/src/test/resources/wikidich"
    stv = ROOT / "app/src/test/resources/sangtacviet"
    assert {"list.html", "detail.html", "chapter-page-3.html", "chapter.html"} <= {p.name for p in wiki.iterdir()}
    assert {"list.html", "detail.html", "toc.json", "chapter.json", "login-required.json"} <= {p.name for p in stv.iterdir()}
    assert "wikidichvn.com/quy-bi-chi-chu" in (wiki / "list.html").read_text(encoding="utf-8")
    assert "Số chương: 205" in (wiki / "detail.html").read_text(encoding="utf-8")
    toc = (stv / "toc.json").read_text(encoding="utf-8")
    assert toc.count("-//-") == 2 and "Chương 3: Tranh phong" in toc
    assert '"code":4005' in (stv / "login-required.json").read_text(encoding="utf-8")

    registry = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/SourceRegistry.kt").read_text(encoding="utf-8")
    for token in ("WikidichSource()", "SangTacVietSource(sessionStore)"):
        assert token in registry, token
    assert 'NotPortedSource("wikidich"' not in registry
    assert 'NotPortedSource("sangtacviet"' not in registry

    wiki_source = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/WikidichSource.kt").read_text(encoding="utf-8")
    for token in ("wikidichvn.com", "parseChapterPage", "lastChapterPage", "SourceHealth.DEGRADED"):
        assert token in wiki_source, token

    stv_source = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/SangTacVietSource.kt").read_text(encoding="utf-8")
    for token in ("chapterlist", "readchapter", "SOURCE_LOGIN_REQUIRED", "MiniJsonObject", "loginUrl = BASE_URL"):
        assert token in stv_source, token

    login = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/SourceLoginActivity.kt").read_text(encoding="utf-8")
    for token in ("setAcceptThirdPartyCookies(this, false)", "settings.allowFileAccess = false", "MIXED_CONTENT_NEVER_ALLOW", "safeBrowsingEnabled = true", "isAllowed", "captureSession", "clearStoredSession"):
        assert token in login, token
    assert "addJavascriptInterface" not in login

    encrypted = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/EncryptedSourceSessionStore.kt").read_text(encoding="utf-8")
    for token in ("AndroidKeyStore", "AES/GCM/NoPadding", "updateAAD", "setRandomizedEncryptionRequired(true)"):
        assert token in encrypted, token
    sessions = (ROOT / "app/src/main/java/vn/nghetruyen/app/sources/SourceSessionStore.kt").read_text(encoding="utf-8")
    for token in ("MAX_COOKIE_COUNT = 128", "MAX_COOKIE_HEADER_BYTES = 32 * 1024"):
        assert token in sessions, token


def compile_sources() -> None:
    if not KOTLINC:
        print("P2_KOTLIN_COMPILE_SKIPPED: kotlinc not found")
        return
    with tempfile.TemporaryDirectory(prefix="nghe_p2_sources_") as name:
        temp = Path(name)
        stubs = temp / "stubs"
        files = [
            write(stubs, "okhttp3/HttpUrl.kt", '''package okhttp3
class HttpUrl {
 val isHttps:Boolean=true; val host:String="wikidichvn.com"; val encodedPath:String="/"; val pathSegments:List<String> = listOf("truyen","qidian","1","123")
 fun queryParameter(name:String):String?=null
 fun newBuilder():Builder=Builder(); override fun toString():String="https://wikidichvn.com/"
 class Builder { fun addQueryParameter(n:String,v:String?):Builder=this; fun setQueryParameter(n:String,v:String?):Builder=this; fun removeAllQueryParameters(n:String):Builder=this; fun encodedPath(v:String):Builder=this; fun host(v:String):Builder=this; fun query(v:String?):Builder=this; fun fragment(v:String?):Builder=this; fun build():HttpUrl=HttpUrl() }
 companion object { fun String.toHttpUrl():HttpUrl=HttpUrl() }
}
'''),
            write(stubs, "org/jsoup/nodes/Nodes.kt", '''package org.jsoup.nodes
open class Element { fun text():String=""; fun closest(s:String):Element?=null; fun parent():Element?=null; fun selectFirst(s:String):Element?=null; fun select(s:String):Elements=Elements(); fun attr(k:String):String=""; fun absUrl(k:String):String=""; fun wholeText():String=""; fun after(v:String):Element=this; fun remove(){} }
class Document:Element(){ fun title():String=""; fun body():Element=Element() }
class Elements:ArrayList<Element>(){ fun remove(){} }
'''),
            write(stubs, "org/jsoup/Jsoup.kt", '''package org.jsoup
import org.jsoup.nodes.Document
object Jsoup { fun parseBodyFragment(html:String,baseUri:String):Document=Document() }
'''),
            write(stubs, "vn/nghetruyen/app/sources/Stubs.kt", '''package vn.nghetruyen.app.sources
import org.jsoup.nodes.Document
class HttpHtmlClient:HtmlDocumentClient { override suspend fun getDocument(url:String,allowedHosts:Set<String>):Document=Document(); companion object { const val DEFAULT_USER_AGENT="ua" } }
class SessionHttpClient(store:SourceSessionStore) { suspend fun getDocument(sourceId:String,url:String,allowedHosts:Set<String>,headers:Map<String,String> = emptyMap()):Document=Document(); suspend fun getText(sourceId:String,url:String,allowedHosts:Set<String>,headers:Map<String,String> = emptyMap()):String=""; suspend fun postEmpty(sourceId:String,url:String,allowedHosts:Set<String>,headers:Map<String,String> = emptyMap()):String="" }
class ResponseTooLargeException(message:String=""):Exception(message)
class HttpSourceException(val statusCode:Int,message:String=""):Exception(message)
'''),
            ROOT / "app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/core/model/Models.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/sources/HtmlDocumentClient.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/sources/StorySource.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/sources/SourceErrors.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/sources/SourceSessionStore.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/sources/MiniJsonObject.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/sources/WikidichSource.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/sources/SangTacVietSource.kt",
        ]
        run([KOTLINC, *(str(p) for p in files), "-d", str(temp / "p2-sources.jar")])

        smoke = write(temp, "Smoke.kt", '''package vn.nghetruyen.app.sources
fun main(){ val j=MiniJsonObject.parse("{\\\"code\\\":0,\\\"data\\\":\\\"a\\\\n b\\\"}"); check(j.int("code")==0); val s=InMemorySourceSessionStore(); s.replaceCookieHeader("x","A=1; B=2"); s.mergeSetCookieHeaders("x",listOf("A=3; Path=/","B=; Max-Age=0")); check(s.cookieHeader("x")=="A=3"); println("P2_PURE_SMOKE_OK") }
''')
        run([KOTLINC, str(ROOT / "app/src/main/java/vn/nghetruyen/app/sources/MiniJsonObject.kt"), str(ROOT / "app/src/main/java/vn/nghetruyen/app/sources/SourceSessionStore.kt"), str(smoke), "-include-runtime", "-d", str(temp / "smoke.jar")])
        run(["java", "-jar", str(temp / "smoke.jar")])


def main() -> None:
    fixture_gate()
    compile_sources()
    print("P2_SOURCE_CHECK_OK")


if __name__ == "__main__":
    main()
