#!/usr/bin/env python3
"""Offline structural and Kotlin stub-compile gate for v2.4.0 source compatibility work."""
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


def require(path: str, *tokens: str) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    for token in tokens:
        assert token in text, f"{path} missing token: {token}"


def main() -> None:
    require(
        "source-lua/src/main/kotlin/vn/nghetruyen/source/lua/LuaSandbox.kt",
        "JsePlatform.standardGlobals()",
        "debug.sethook",
        "NATIVE_LUA_INSTRUCTION_BUDGET_EXCEEDED",
        '"luajava", "io", "os", "debug", "package"',
        '"loadstring"',
        "NATIVE_LUA_MODULE_DENIED",
    )
    require(
        "source-lua/src/main/kotlin/vn/nghetruyen/source/lua/NativeLuaSourceImporter.kt",
        "NATIVE_LUA_API_VERSION_UNSUPPORTED",
        "SourceRuntimeMode.NATIVE_LUA_COMPAT",
        "SourceCookieMode.BROWSER_SHARED",
        "native/source.lua",
        "hasComments",
        "validationSandbox",
        "adapterSandbox",
        "sanitizePackage",
    )
    require(
        "source-lua/src/main/kotlin/vn/nghetruyen/source/lua/LuaNativeHookBroker.kt",
        "NATIVE_LUA_HOOK_NOT_FOUND",
        "MAX_INPUT_BYTES",
        "request.maxOutputBytes",
        "NATIVE_API_RESOURCE",
        "request.resourceSources",
    )
    require(
        "source-lua/src/main/kotlin/vn/nghetruyen/source/lua/NativeLuaArchiveImporter.kt",
        "MAX_ARCHIVE_INFLATED_BYTES",
        "NATIVE_LUA_ARCHIVE_INFLATED_LIMIT",
        "paths.singleOrNull()",
    )
    adapter = (ROOT / "source-lua/src/main/resources/vn/nghetruyen/source/lua/native_v2_adapter.lua").read_text(encoding="utf-8")
    for forbidden in ("luajava.bindClass", "java.lang", "Runtime.getRuntime", "ProcessBuilder"):
        assert forbidden not in adapter, f"Native adapter contains forbidden bridge: {forbidden}"
    assert 'files["native_v2_comments.js"]' in adapter

    require(
        "source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt",
        'putProperty(scope, "Http"',
        'putProperty(scope, "Html"',
        'putProperty(scope, "Engine"',
        'putProperty(scope, "Browser"',
        'putProperty(scope, "Storage"',
        'putProperty(scope, "WebSocketHost"',
        'putProperty(scope, "WebSocket"',
        'putProperty(scope, "Document"',
        'putProperty(scope, "localCookie"',
        'putProperty(scope, "Script"',
        'putProperty(scope, "Qt"',
        'putProperty(scope, "__bridge"',
        "BrowserCompatObject",
        "SourceRuntimeMode.NATIVE_LUA_COMPAT",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/sources/SourceDiagnosticBrowserActivity.kt",
        "ExtensionWebViewAuthority.apply",
        "recordBrowserEnvironment",
        "shouldInterceptRequest",
        "onReceivedSslError",
        "XUẤT NHẬT KÝ",
        "captureSession",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/sources/StorySource.kt",
        "enum class SourceCommentCapability",
        "DYNAMIC_BROWSER",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformManager.kt",
        "commentFixtureCount",
        "prepareNativeLuaImport",
    )

    if not KOTLINC:
        print("V240_STATIC_COMPILE_SKIPPED")
        print("V240_NATIVE_LUA_VBOOK_DIAGNOSTICS_COMMENTS_OK")
        return

    with tempfile.TemporaryDirectory(prefix="v240-static-") as name:
        temp = Path(name)
        stubs = [
            write(temp, "org/mozilla/javascript/Stubs.kt", r'''package org.mozilla.javascript
fun interface ClassShutter { fun visibleToScripts(fullClassName:String):Boolean }
interface Scriptable { val ids:Array<Any>; fun delete(name:String); fun get(name:String,start:Scriptable):Any; fun get(index:Int,start:Scriptable):Any; companion object { val NOT_FOUND=Any() } }
open class ScriptableObject:Scriptable { override val ids:Array<Any> get()=emptyArray(); var parentScope:Scriptable?=null; var prototype:Scriptable?=null; override fun delete(name:String){}; override fun get(name:String,start:Scriptable):Any=Scriptable.NOT_FOUND; override fun get(index:Int,start:Scriptable):Any=Scriptable.NOT_FOUND; fun put(name:String,start:Scriptable,value:Any?){}; open fun getClassName():String="Object"; companion object { fun getProperty(scope:Scriptable,name:String):Any=Scriptable.NOT_FOUND; fun putProperty(scope:Scriptable,name:String,value:Any?){}; fun getObjectPrototype(scope:Scriptable):Scriptable?=null } }
open class Function:ScriptableObject(){ open fun call(cx:Context,scope:Scriptable,thisObj:Scriptable,args:Array<out Any>):Any?=null }
open class BaseFunction:Function()
open class NativeArray(val length:Long=0):ScriptableObject()
open class Context { var optimizationLevel:Int=0; var languageVersion:Int=0; var instructionObserverThreshold:Int=0; var classShutter:ClassShutter?=null; fun initSafeStandardObjects():Scriptable=ScriptableObject(); fun evaluateString(scope:Scriptable,source:String,name:String,line:Int,security:Any?):Any=Any(); fun newObject(scope:Scriptable):Scriptable=ScriptableObject(); companion object { const val VERSION_ES6=200; fun exit(){}; fun toString(v:Any?):String=v?.toString()?"":""; fun toNumber(v:Any?):Double=0.0; fun getUndefinedValue():Any=Any(); fun javaToJS(v:Any?,scope:Scriptable):Any=v?:Any() } }
open class ContextFactory { protected open fun makeContext():Context=Context(); protected open fun observeInstructionCount(cx:Context,instructionCount:Int){}; fun enterContext():Context=makeContext() }
'''.replace('v?.toString()?"":""', 'v?.toString() ?: "null"')),
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
            write(temp, "org/luaj/vm2/Lua.kt", r'''package org.luaj.vm2
open class Varargs { open fun arg1():LuaValue=LuaValue.NIL; open fun arg(i:Int):LuaValue=LuaValue.NIL }
open class LuaValue:Varargs() {
 open fun call():LuaValue=this
 open fun call(a:LuaValue):LuaValue=this
 open fun call(a:LuaValue,b:LuaValue):LuaValue=this
 open fun call(a:LuaValue,b:LuaValue,c:LuaValue):LuaValue=this
 open fun invoke(args:Varargs):Varargs=this
 open fun get(key:String):LuaValue=NIL
 open fun get(key:Int):LuaValue=NIL
 open fun get(key:LuaValue):LuaValue=NIL
 open fun set(key:String,value:LuaValue){}
 open fun set(key:Int,value:LuaValue){}
 open fun set(key:LuaValue,value:LuaValue){}
 open fun isnil()=false
 open fun isboolean()=false
 open fun isnumber()=false
 open fun isstring()=false
 open fun istable()=false
 open fun isfunction()=false
 open fun isint()=false
 open fun toboolean()=false
 open fun todouble()=0.0
 open fun toint()=0
 open fun tojstring()=""
 open fun checkjstring()=""
 open fun checktable():LuaTable=LuaTable()
 open fun optint(defaultValue:Int)=defaultValue
 open fun optjstring(defaultValue:String)=defaultValue
 open fun optboolean(defaultValue:Boolean)=defaultValue
 open fun length()=0
 open fun keys():Array<LuaValue> = emptyArray()
 companion object {
   val NIL=LuaValue()
   val NONE=LuaValue()
   fun valueOf(value:String)=LuaValue()
   fun valueOf(value:Boolean)=LuaValue()
   fun valueOf(value:Double)=LuaValue()
   fun valueOf(value:Int)=LuaValue()
 }
}
open class LuaTable:LuaValue() { override fun istable()=true; override fun checktable():LuaTable=this }
open class Globals:LuaTable() { fun load(source:String,name:String):LuaValue=LuaValue(); fun load(lib:LuaValue):LuaValue=LuaValue() }
class LuaError(message:String):RuntimeException(message)
'''),
            write(temp, "org/luaj/vm2/lib/Libs.kt", r'''package org.luaj.vm2.lib
import org.luaj.vm2.*
open class OneArgFunction:LuaValue(){ override fun call(a:LuaValue):LuaValue=LuaValue.NIL }
open class TwoArgFunction:LuaValue(){ override fun call(a:LuaValue,b:LuaValue):LuaValue=LuaValue.NIL }
open class ThreeArgFunction:LuaValue(){ override fun call(a:LuaValue,b:LuaValue,c:LuaValue):LuaValue=LuaValue.NIL }
open class VarArgFunction:LuaValue(){ open override fun invoke(args:Varargs):Varargs=LuaValue.NONE }
open class DebugLib:LuaValue()
'''),
            write(temp, "org/luaj/vm2/lib/jse/JsePlatform.kt", r'''package org.luaj.vm2.lib.jse
import org.luaj.vm2.Globals
object JsePlatform { fun standardGlobals():Globals=Globals() }
'''),
        ]
        sources: list[Path] = []
        for module in ("source-api", "source-diagnostics", "source-package", "source-runtime", "source-vbook", "source-lua"):
            sources.extend(sorted((ROOT / module / "src/main/kotlin").rglob("*.kt")))
        run([KOTLINC, *(str(p) for p in stubs), *(str(p) for p in sources), "-d", str(temp / "v240.jar")])

    print("V240_NATIVE_LUA_VBOOK_DIAGNOSTICS_COMMENTS_OK")


if __name__ == "__main__":
    main()
