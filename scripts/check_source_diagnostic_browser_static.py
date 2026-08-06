#!/usr/bin/env python3
"""Compile the dedicated source diagnostic browser with compact Android stubs."""
from __future__ import annotations
import shutil, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
K=shutil.which('kotlinc')

def w(root,path,text):
 p=root/path; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(text,encoding='utf-8'); return p

def main():
 src=(ROOT/'app/src/main/java/vn/nghetruyen/app/sources/SourceDiagnosticBrowserActivity.kt').read_text(encoding='utf-8')
 for token in ('MIXED_CONTENT_NEVER_ALLOW','safeBrowsingEnabled = true','allowFileAccess = false','allowContentAccess = false','shouldInterceptRequest','onReceivedSslError','CookieHeaderCodec.cookieNames','XUẤT JSON','onJsPrompt','ORIGIN_MODE','RESOURCE_MODE','STORAGE_PROBE','requests'):
  assert token in src, token
 assert 'SafeBrowsingResponse' not in src, 'API 27 type must not be linked directly with minSdk 26'
 if not K:
  print('SOURCE_DIAGNOSTIC_BROWSER_COMPILE_SKIPPED'); return
 with tempfile.TemporaryDirectory(prefix='source_diag_') as td:
  r=Path(td); f=[]
  f += [w(r,'android/R.kt','package android\nobject R { object attr { const val progressBarStyleHorizontal:Int=1 } }\n')]
  f += [w(r,'android/annotation/SuppressLint.kt','package android.annotation\n@Target(AnnotationTarget.CLASS,AnnotationTarget.FUNCTION) annotation class SuppressLint(vararg val value:String)\n')]
  f += [w(r,'android/content/Content.kt',r'''package android.content
import android.net.Uri
import java.io.ByteArrayOutputStream
open class Context { fun getSystemService(name:String):Any=ClipboardManager(); companion object { const val CLIPBOARD_SERVICE="clipboard" } }
class ClipData { companion object { fun newPlainText(label:CharSequence,text:CharSequence)=ClipData() } }
class ClipboardManager { fun setPrimaryClip(clip:ClipData){} }
open class ContentResolver { fun openOutputStream(uri:Uri,mode:String):java.io.OutputStream?=ByteArrayOutputStream() }
class Intent { fun getStringExtra(key:String):String?="x"; fun getStringArrayExtra(key:String):Array<String>?=arrayOf("example.com") }
''')]
  f += [w(r,'android/graphics/Graphics.kt','package android.graphics\nopen class Bitmap\nobject Color { const val WHITE:Int=0xffffff }\n')]
  f += [w(r,'android/net/Uri.kt',r'''package android.net
class Uri private constructor(private val raw:String="https://example.com/") {
 val scheme:String?="https"; val host:String?="example.com"; val userInfo:String?=null
 fun buildUpon()=Builder(); override fun toString()=raw
 class Builder { fun clearQuery()=this; fun fragment(value:String?)=this; fun build()=Uri() }
 companion object { fun parse(value:String)=Uri(value) }
}
''')]
  f += [w(r,'android/net/http/SslError.kt','package android.net.http\nclass SslError { val primaryError:Int=0; val url:String?="https://example.com" }\n')]
  f += [w(r,'android/os/Os.kt','package android.os\nopen class Bundle\nopen class Message\n')]
  f += [w(r,'android/view/View.kt',r'''package android.view
open class View
open class ViewGroup:View(){ open class LayoutParams(val width:Int,val height:Int){ companion object { const val MATCH_PARENT=-1; const val WRAP_CONTENT=-2 } } }
''')]
  f += [w(r,'android/widget/Widgets.kt',r'''package android.widget
import android.content.Context
import android.view.View
import android.view.ViewGroup
open class TextView(c:Context):View(){ var text:CharSequence=""; var textSize:Float=0f; fun setPadding(a:Int,b:Int,c:Int,d:Int){}; fun setTextIsSelectable(v:Boolean){} }
class EditText(c:Context):TextView(c){ fun setSingleLine(v:Boolean){}; var hint:CharSequence="" }
class Button(c:Context):TextView(c){ fun setOnClickListener(listener:(View)->Unit){} }
class ProgressBar(c:Context,a:Any?,style:Int):View(){ var max:Int=0; var progress:Int=0 }
open class LinearLayout(c:Context):ViewGroup(){ var orientation:Int=0; fun setBackgroundColor(v:Int){}; fun addView(v:View,p:ViewGroup.LayoutParams){}; class LayoutParams(w:Int,h:Int,val weight:Float=0f):ViewGroup.LayoutParams(w,h); companion object { const val VERTICAL=1; const val HORIZONTAL=0 } }
class ScrollView(c:Context):ViewGroup(){ fun addView(v:View,p:ViewGroup.LayoutParams){} }
''')]
  f += [w(r,'android/webkit/Web.kt',r'''package android.webkit
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.ViewGroup
import java.io.InputStream
open class ConsoleMessage { fun message():String=""; fun messageLevel():MessageLevel=MessageLevel.LOG; fun lineNumber():Int=0; enum class MessageLevel { TIP,LOG,WARNING,ERROR,DEBUG } }
class CookieManager { fun setAcceptCookie(v:Boolean){}; fun setAcceptThirdPartyCookies(view:WebView,v:Boolean){}; fun getCookie(url:String):String?=null; fun setCookie(url:String,value:String){}; fun flush(){}; companion object { fun getInstance()=CookieManager() } }
open class SslErrorHandler { fun cancel(){} }
open class JsResult { fun confirm(){}; fun cancel(){} }
open class JsPromptResult:JsResult() { fun confirm(value:String){} }
open class WebChromeClient { open fun onProgressChanged(view:WebView?,newProgress:Int){}; open fun onConsoleMessage(message:ConsoleMessage):Boolean=false; open fun onCreateWindow(view:WebView?,isDialog:Boolean,isUserGesture:Boolean,resultMsg:Message?):Boolean=false; open fun onJsAlert(view:WebView?,url:String?,message:String?,result:JsResult):Boolean=false; open fun onJsConfirm(view:WebView?,url:String?,message:String?,result:JsResult):Boolean=false; open fun onJsPrompt(view:WebView?,url:String?,message:String?,defaultValue:String?,result:JsPromptResult):Boolean=false }
open class WebResourceError { val errorCode:Int=0; val description:CharSequence="" }
interface WebResourceRequest { val url:Uri; val method:String; val isForMainFrame:Boolean; val requestHeaders:Map<String,String> }
open class WebResourceResponse { val statusCode:Int=200; constructor(mime:String?,encoding:String?,data:InputStream?); constructor(mime:String?,encoding:String?,statusCode:Int,reason:String,headers:Map<String,String>,data:InputStream?) }
open class WebSettings { var javaScriptEnabled=false; var domStorageEnabled=false; var databaseEnabled=false; var allowFileAccess=false; var allowContentAccess=false; var javaScriptCanOpenWindowsAutomatically=false; var mixedContentMode:Int=0; var safeBrowsingEnabled=false; var mediaPlaybackRequiresUserGesture=false; var cacheMode:Int=0; var userAgentString:String=""; fun setSupportMultipleWindows(v:Boolean){}; companion object { const val MIXED_CONTENT_NEVER_ALLOW=1; const val LOAD_DEFAULT=0; fun getDefaultUserAgent(context:Context):String="system" } }
open class WebView(c:Context):ViewGroup(){ val settings=WebSettings(); var webChromeClient:WebChromeClient=WebChromeClient(); var webViewClient:WebViewClient=WebViewClient(); var title:String?=""; var url:String?="https://example.com"; fun canGoBack()=false; fun canGoForward()=false; fun goBack(){}; fun goForward(){}; fun reload(){}; fun loadUrl(value:String){}; fun evaluateJavascript(script:String,callback:(String?)->Unit){callback("null")}; fun stopLoading(){}; fun removeAllViews(){}; fun destroy(){}; companion object { fun setWebContentsDebuggingEnabled(v:Boolean){} } }
open class WebViewClient { open fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean=false; open fun onPageStarted(view:WebView,url:String,favicon:Bitmap?){}; open fun onPageFinished(view:WebView,url:String){}; open fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse?=null; open fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){}; open fun onReceivedHttpError(view:WebView,request:WebResourceRequest,errorResponse:WebResourceResponse){}; open fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:SslError){} }
''')]
  f += [w(r,'androidx/activity/result/Result.kt',r'''package androidx.activity.result
class ActivityResultLauncher<I>{ fun launch(input:I){} }
open class ActivityResultContract<I,O>
''')]
  f += [w(r,'androidx/activity/result/contract/Contracts.kt',r'''package androidx.activity.result.contract
import android.net.Uri
import androidx.activity.result.ActivityResultContract
object ActivityResultContracts { class CreateDocument(val mime:String):ActivityResultContract<String,Uri?>() }
''')]
  f += [w(r,'androidx/activity/ComponentActivity.kt',r'''package androidx.activity
import android.content.*
import android.os.Bundle
import android.view.View
import androidx.activity.result.*
open class ComponentActivity:Context(){ open fun onCreate(state:Bundle?){}; open fun onPause(){}; open fun onDestroy(){}; val intent=Intent(); val application:Any=vn.nghetruyen.app.NgheTruyenApplication(); val contentResolver=ContentResolver(); fun setContentView(v:View){}; fun <I,O> registerForActivityResult(contract:ActivityResultContract<I,O>,callback:(O)->Unit)=ActivityResultLauncher<I>() }
''')]
  f += [w(r,'org/json/Json.kt',r'''package org.json
class JSONArray { constructor(values:Collection<*>); constructor() }
class JSONObject { constructor(); fun put(key:String,value:Any?):JSONObject=this; fun toString(indent:Int):String="{}" }
class JSONTokener(val value:String){ fun nextValue():Any?=value }
''')]
  f += [w(r,'vn/nghetruyen/app/App.kt',r'''package vn.nghetruyen.app
class NgheTruyenApplication { val container=Container() }
class Container { val sourceSessionStore:vn.nghetruyen.app.sources.SourceSessionStore=vn.nghetruyen.app.sources.InMemorySourceSessionStore() }
''')]
  f += [ROOT/'app/src/main/java/vn/nghetruyen/app/sources/SourceSessionStore.kt', ROOT/'app/src/main/java/vn/nghetruyen/app/sources/SourceDiagnosticBrowserActivity.kt']
  cp=subprocess.run([K,*map(str,f),'-d',str(r/'out.jar')],cwd=ROOT,text=True,capture_output=True)
  if cp.returncode:
   print(cp.stdout); print(cp.stderr); raise SystemExit(cp.returncode)
 print('SOURCE_DIAGNOSTIC_BROWSER_COMPILE_OK')
if __name__=='__main__': main()
