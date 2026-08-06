#!/usr/bin/env python3
"""Compile P2 Android session/login wiring with small stubs."""
from __future__ import annotations
import shutil, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
K=shutil.which('kotlinc')

def w(root,path,text):
 p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');return p

def main():
 if not K:
  print('P2_ANDROID_WIRING_SKIPPED');return
 with tempfile.TemporaryDirectory(prefix='nghe_p2_android_') as td:
  r=Path(td); f=[]
  f += [w(r,'android/annotation/SuppressLint.kt','package android.annotation\n@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION) annotation class SuppressLint(vararg val value:String)\n')]
  f += [w(r,'android/content/Context.kt',r'''package android.content
open class Context { open val applicationContext:Context get()=this; fun getSharedPreferences(n:String,m:Int):SharedPreferences=Prefs(); companion object { const val MODE_PRIVATE=0 } }
interface SharedPreferences { fun getString(k:String,d:String?):String?; fun edit():Editor; interface Editor { fun putString(k:String,v:String?):Editor; fun remove(k:String):Editor; fun apply() } }
class Prefs:SharedPreferences { override fun getString(k:String,d:String?)=d; override fun edit()=E(); class E:SharedPreferences.Editor { override fun putString(k:String,v:String?)=this; override fun remove(k:String)=this; override fun apply(){} } }
class Intent { fun getStringExtra(k:String):String?=null; fun getStringArrayExtra(k:String):Array<String>?=null }
''')]
  f += [w(r,'android/os/Bundle.kt','package android.os\nopen class Bundle\n')]
  f += [w(r,'android/graphics/Color.kt','package android.graphics\nobject Color { const val WHITE:Int=0xffffff }\n')]
  f += [w(r,'android/net/Uri.kt',r'''package android.net
class Uri { val scheme:String?="https"; val host:String?="sangtacviet.vip"; companion object { fun parse(v:String)=Uri() } }
''')]
  f += [w(r,'android/view/View.kt',r'''package android.view
open class View
open class ViewGroup:View(){ open class LayoutParams(val width:Int,val height:Int){ companion object { const val MATCH_PARENT=-1; const val WRAP_CONTENT=-2 } } }
''')]
  f += [w(r,'android/widget/Widgets.kt',r'''package android.widget
import android.content.Context
import android.view.View
import android.view.ViewGroup
open class LinearLayout(c:Context):ViewGroup(){ var orientation:Int=0; fun setBackgroundColor(c:Int){}; fun addView(v:View,p:ViewGroup.LayoutParams){}; companion object { const val VERTICAL=1; const val HORIZONTAL=0 }; class LayoutParams(w:Int,h:Int,val weight:Float=0f):ViewGroup.LayoutParams(w,h) }
open class TextView(c:Context):View(){ var text:CharSequence=""; fun setPadding(a:Int,b:Int,c:Int,d:Int){} }
class Button(c:Context):TextView(c){ fun setOnClickListener(l:(View)->Unit){} }
''')]
  f += [w(r,'android/webkit/Web.kt',r'''package android.webkit
import android.content.Context
import android.net.Uri
import android.view.ViewGroup
open class WebView(c:Context):ViewGroup(){ val settings=WebSettings(); var webViewClient:WebViewClient=WebViewClient(); fun loadUrl(u:String){}; fun stopLoading(){}; fun removeAllViews(){}; fun destroy(){} }
class WebSettings { var javaScriptEnabled=false; var domStorageEnabled=false; var databaseEnabled=false; var allowFileAccess=false; var allowContentAccess=false; var javaScriptCanOpenWindowsAutomatically=false; var mixedContentMode:Int=0; var safeBrowsingEnabled:Boolean=false; fun setSupportMultipleWindows(v:Boolean){}; companion object { const val MIXED_CONTENT_NEVER_ALLOW=1 } }
open class WebViewClient { open fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean=false; open fun onPageFinished(view:WebView,url:String){} }
interface WebResourceRequest { val url:Uri }
class CookieManager { fun setAcceptCookie(v:Boolean){}; fun setAcceptThirdPartyCookies(v:WebView,b:Boolean){}; fun getCookie(u:String):String?=null; fun setCookie(u:String,c:String){}; fun flush(){}; companion object { private val x=CookieManager(); fun getInstance()=x } }
''')]
  f += [w(r,'android/security/keystore/Keys.kt',r'''package android.security.keystore
import java.security.spec.AlgorithmParameterSpec
object KeyProperties { const val KEY_ALGORITHM_AES="AES"; const val PURPOSE_ENCRYPT=1; const val PURPOSE_DECRYPT=2; const val BLOCK_MODE_GCM="GCM"; const val ENCRYPTION_PADDING_NONE="NoPadding" }
class KeyGenParameterSpec:AlgorithmParameterSpec { class Builder(a:String,p:Int){ fun setBlockModes(vararg v:String)=this; fun setEncryptionPaddings(vararg v:String)=this; fun setRandomizedEncryptionRequired(v:Boolean)=this; fun build()=KeyGenParameterSpec() } }
''')]
  f += [w(r,'android/util/Base64.kt',r'''package android.util
object Base64 { const val NO_WRAP=2; fun encodeToString(b:ByteArray,f:Int)=java.util.Base64.getEncoder().encodeToString(b); fun decode(s:String,f:Int)=java.util.Base64.getDecoder().decode(s) }
''')]
  f += [w(r,'androidx/activity/ComponentActivity.kt',r'''package androidx.activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
open class ComponentActivity:Context(){ open fun onCreate(b:Bundle?){}; open fun onPause(){}; open fun onDestroy(){}; val intent=Intent(); val application:Any=vn.nghetruyen.app.NgheTruyenApplication(); fun setContentView(v:View){}; fun setResult(v:Int){}; fun finish(){}; companion object { const val RESULT_OK=-1 } }
''')]
  f += [w(r,'vn/nghetruyen/app/App.kt',r'''package vn.nghetruyen.app
class NgheTruyenApplication { val container=Container() }
class Container { val sourceSessionStore:vn.nghetruyen.app.sources.SourceSessionStore=vn.nghetruyen.app.sources.InMemorySourceSessionStore() }
''')]
  f += [ROOT/'app/src/main/java/vn/nghetruyen/app/sources/SourceSessionStore.kt', ROOT/'app/src/main/java/vn/nghetruyen/app/sources/EncryptedSourceSessionStore.kt', ROOT/'app/src/main/java/vn/nghetruyen/app/sources/SourceLoginActivity.kt']
  cp=subprocess.run([K,*map(str,f),'-d',str(r/'out.jar')],cwd=ROOT,text=True,capture_output=True)
  if cp.returncode:
   print(cp.stdout);print(cp.stderr);raise SystemExit(cp.returncode)
 print('P2_ANDROID_WIRING_OK')
if __name__=='__main__':main()
