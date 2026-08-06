#!/usr/bin/env python3
"""Offline safety and Kotlin stub-compile gate for the vBook compatibility runtime."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")


def write(root: Path, relative: str, text: str) -> Path:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")
    return target


def run(command: list[str]) -> None:
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        print(result.stdout)
        print(result.stderr)
        raise SystemExit(result.returncode)


def main() -> None:
    runtime = (ROOT / "source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt").read_text(encoding="utf-8")
    importer = (ROOT / "source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookPluginImporter.kt").read_text(encoding="utf-8")
    required = [
        "optimizationLevel = -1",
        "instructionObserverThreshold",
        "ClassShutter",
        "classShutter = ClassShutter { false }",
        "Context.VERSION_ES6",
        "VBOOK_TIMEOUT",
        "VBOOK_INSTRUCTION_BUDGET_EXCEEDED",
        "SourceActionName.COMMENTS -> normalizeComments",
        "private fun normalizeComments",
        ".take(100)",
    ]
    for token in required:
        assert token in runtime, f"VBook runtime missing safety token: {token}"
    for forbidden in ("addJavascriptInterface", "Runtime.getRuntime", "ProcessBuilder(", "Class.forName("):
        assert forbidden not in runtime, f"Forbidden vBook bridge token: {forbidden}"
    assert 'if (plugin.scripts.containsKey("homecontent")) "homecontent" else "home"' in importer
    assert 'if (plugin.scripts.containsKey("genrecontent")) "genrecontent" else "genre"' in importer

    wattpad = ROOT / "examples/sourcepacks/wattpad"
    for script in wattpad.glob("src/*.js"):
        text = script.read_text(encoding="utf-8", errors="replace")
        for forbidden in ("importClass", "JavaAdapter", "Packages.", "java.", "javax."):
            assert forbidden not in text, f"{script} uses forbidden Java bridge: {forbidden}"

    if not KOTLINC:
        print("VBOOK_STATIC_COMPILE_SKIPPED")
        print("VBOOK_STATIC_COMPILE_OK")
        return

    with tempfile.TemporaryDirectory(prefix="vbook-static-") as name:
        temp = Path(name)
        stubs = [
            write(temp, "org/mozilla/javascript/Stubs.kt", r'''package org.mozilla.javascript
fun interface ClassShutter { fun visibleToScripts(fullClassName:String):Boolean }
interface Scriptable { val ids:Array<Any>; fun delete(name:String); fun get(name:String,start:Scriptable):Any; fun get(index:Int,start:Scriptable):Any; companion object { val NOT_FOUND=Any() } }
open class ScriptableObject:Scriptable { override val ids:Array<Any> get()=emptyArray(); var parentScope:Scriptable?=null; var prototype:Scriptable?=null; override fun delete(name:String){}; override fun get(name:String,start:Scriptable):Any=Scriptable.NOT_FOUND; override fun get(index:Int,start:Scriptable):Any=Scriptable.NOT_FOUND; fun put(name:String,start:Scriptable,value:Any?){}; open fun getClassName():String="Object"; companion object { fun getProperty(scope:Scriptable,name:String):Any=Scriptable.NOT_FOUND; fun putProperty(scope:Scriptable,name:String,value:Any?){}; fun getObjectPrototype(scope:Scriptable):Scriptable?=null } }
open class Function:ScriptableObject(){ open fun call(cx:Context,scope:Scriptable,thisObj:Scriptable,args:Array<out Any>):Any?=null }
open class BaseFunction:Function()
open class NativeArray(val length:Long=0):ScriptableObject()
open class Context { var optimizationLevel:Int=0; var languageVersion:Int=0; var instructionObserverThreshold:Int=0; var classShutter:ClassShutter?=null; fun initSafeStandardObjects():Scriptable=ScriptableObject(); fun evaluateString(scope:Scriptable,source:String,name:String,line:Int,security:Any?):Any=Any(); fun newObject(scope:Scriptable):Scriptable=ScriptableObject(); companion object { const val VERSION_ES6=200; fun exit(){}; fun toString(v:Any?):String=v?.toString()?:"null"; fun toNumber(v:Any?):Double=0.0; fun getUndefinedValue():Any=Any(); fun javaToJS(v:Any?,scope:Scriptable):Any=v?:Any() } }
open class ContextFactory { protected open fun makeContext():Context=Context(); protected open fun observeInstructionCount(cx:Context,instructionCount:Int){}; fun enterContext():Context=makeContext() }
'''),
            write(temp, "org/jsoup/Jsoup.kt", '''package org.jsoup
import org.jsoup.nodes.Document
object Jsoup { fun parse(html:String,base:String=""):Document=Document(); fun parseBodyFragment(html:String):Document=Document() }
'''),
            write(temp, "org/jsoup/nodes/Nodes.kt", '''package org.jsoup.nodes
import org.jsoup.select.Elements
open class Element { fun select(css:String)=Elements(); fun selectFirst(css:String):Element?=Element(); fun text()=""; fun ownText()=""; fun html()=""; fun outerHtml()=""; fun attr(name:String)=""; fun absUrl(name:String)=""; fun clone():Element=Element(); fun wholeText()=""; fun after(value:String):Element=this; fun id()=""; fun tagName()=""; fun hasClass(name:String)=false; fun parent():Element?=null; fun children():List<Element> = emptyList() }
class Document:Element() { fun title()=""; fun location()=""; fun body():Element?=Element() }
'''),
            write(temp, "org/jsoup/select/Elements.kt", '''package org.jsoup.select
import org.jsoup.nodes.Element
class Elements():ArrayList<Element>() { constructor(element:Element):this(){add(element)}; constructor(elements:Collection<Element>):this(){addAll(elements)}; fun text()=""; fun remove():Elements=this }
'''),
        ]
        sources: list[Path] = []
        for module in ("source-api", "source-diagnostics", "source-package", "source-runtime", "source-vbook"):
            sources.extend(sorted((ROOT / module / "src/main/kotlin").rglob("*.kt")))
        run([KOTLINC, *(str(p) for p in stubs), *(str(p) for p in sources), "-d", str(temp / "vbook.jar")])

    print("VBOOK_STATIC_COMPILE_OK")


if __name__ == "__main__":
    main()
