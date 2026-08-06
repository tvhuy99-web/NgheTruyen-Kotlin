#!/usr/bin/env python3
"""Compile selected Kotlin code with tiny stubs when kotlinc is available."""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")


def run(command: list[str]) -> None:
    completed = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
    if completed.returncode:
        if completed.stdout:
            print(completed.stdout)
        if completed.stderr:
            print(completed.stderr)
        raise SystemExit(completed.returncode)


def kotlin_lib(name: str) -> str | None:
    if not KOTLINC:
        return None
    root = Path(KOTLINC).resolve().parents[1] / "lib"
    path = root / name
    return str(path) if path.is_file() else None


def main() -> None:
    if not KOTLINC:
        print("KOTLIN_STATIC_COMPILE_SKIPPED: kotlinc not found")
        return

    with tempfile.TemporaryDirectory(prefix="nghe_kotlin_static_") as temp_name:
        temp = Path(temp_name)
        core_output = temp / "core.jar"
        coroutines = kotlin_lib("kotlinx-coroutines-core-jvm.jar")
        pronunciation_stub = temp / "pronunciation-stub.kt"
        pronunciation_stub.write_text(
            """package vn.nghetruyen.app.data.local
data class PronunciationEntity(val id:Long=0,val original:String,val replacement:String,val enabled:Boolean=true,val createdAt:Long=0,val updatedAt:Long=0)
""",
            encoding="utf-8",
        )
        command = [
            KOTLINC,
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/core/model/Models.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/playback/PlaybackQueueStore.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/playback/ReaderChapterNavigation.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/sources/StorySource.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/downloads/StoryDownloadPlanner.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/downloads/DownloadBatchPlanner.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/downloads/ChapterRangeSelector.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/sources/StorySearch.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/sources/HostRequestGovernor.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/following/FollowingUpdateDetector.kt"),
            str(pronunciation_stub),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/playback/PronunciationProcessor.kt"),
        ]
        if coroutines:
            command += ["-cp", coroutines]
        command += ["-d", str(core_output)]
        run(command)

        stub_root = temp / "stubs"
        (stub_root / "okhttp3").mkdir(parents=True)
        (stub_root / "org/jsoup/nodes").mkdir(parents=True)
        (stub_root / "vn/nghetruyen/app/sources").mkdir(parents=True)
        (stub_root / "okhttp3/HttpUrl.kt").write_text(
            '''package okhttp3
class HttpUrl {
 val isHttps:Boolean=true; val host:String="truyencv.io"; val encodedPath:String="/"; val pathSegments:List<String> = emptyList()
 fun queryParameter(name:String):String?=null
 fun newBuilder():Builder=Builder(); override fun toString():String="https://truyencv.io/"
 class Builder { fun addQueryParameter(n:String,v:String?):Builder=this; fun setQueryParameter(n:String,v:String?):Builder=this; fun removeAllQueryParameters(n:String):Builder=this; fun encodedPath(v:String):Builder=this; fun host(v:String):Builder=this; fun query(v:String?):Builder=this; fun fragment(v:String?):Builder=this; fun build():HttpUrl=HttpUrl() }
 companion object { fun String.toHttpUrl():HttpUrl=HttpUrl() }
}
''',
            encoding="utf-8",
        )
        (stub_root / "org/jsoup/nodes/Nodes.kt").write_text(
            '''package org.jsoup.nodes
open class Element { fun text():String=""; fun closest(s:String):Element?=null; fun selectFirst(s:String):Element?=null; fun select(s:String):Elements=Elements(); fun tagName():String=""; fun attr(k:String):String=""; fun absUrl(k:String):String=""; fun wholeText():String=""; fun after(v:String):Element=this; fun clone():Element=Element() }
class Document:Element(){ fun title():String=""; fun body():Element?=null }
class Elements:ArrayList<Element>(){ fun remove()=Unit }
''',
            encoding="utf-8",
        )
        (stub_root / "vn/nghetruyen/app/sources/Stubs.kt").write_text(
            '''package vn.nghetruyen.app.sources
import org.jsoup.nodes.Document
class HttpHtmlClient:HtmlDocumentClient { override suspend fun getDocument(url:String, allowedHosts:Set<String>):Document=Document(); companion object { const val DEFAULT_USER_AGENT="ua" } }
class HttpTextClient:TextDocumentClient { override suspend fun getText(url:String, allowedHosts:Set<String>, headers:Map<String,String>):String="" }
class ResponseTooLargeException(message:String=""):Exception(message)
class HttpSourceException(val statusCode:Int,message:String=""):Exception(message)
class SourceParseException(message:String):Exception(message)
class SourceChallengeException(message:String):Exception(message)
''',
            encoding="utf-8",
        )
        run([
            KOTLINC,
            str(stub_root / "okhttp3/HttpUrl.kt"),
            str(stub_root / "org/jsoup/nodes/Nodes.kt"),
            str(stub_root / "vn/nghetruyen/app/sources/Stubs.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/core/model/Models.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/sources/HtmlDocumentClient.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/sources/TextDocumentClient.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/sources/StorySource.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/sources/TruyenFullSource.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/sources/TruyenCvSource.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/sources/TruyenComSource.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/sources/TruyenYySource.kt"),
            "-d", str(temp / "websources.jar"),
        ])

        pronunciation_stub = temp / "pronunciation-stub.kt"
        pronunciation_stub.write_text(
            """package vn.nghetruyen.app.data.local
data class PronunciationEntity(val id:Long=0,val original:String,val replacement:String,val enabled:Boolean=true,val createdAt:Long=0,val updatedAt:Long=0)
""",
            encoding="utf-8",
        )
        run([
            KOTLINC,
            str(pronunciation_stub),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/playback/PronunciationProcessor.kt"),
            "-d", str(temp / "pronunciation.jar"),
        ])

    print("KOTLIN_STATIC_COMPILE_OK")


if __name__ == "__main__":
    main()
