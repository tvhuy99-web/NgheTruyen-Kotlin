#!/usr/bin/env python3
"""Compile the real P2 session-aware HTTP client against narrow JVM stubs."""
from __future__ import annotations
import shutil, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
K=shutil.which('kotlinc')

def w(root,path,text):
 p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');return p

def main():
 if not K:
  print('P2_NETWORK_STATIC_SKIPPED');return
 with tempfile.TemporaryDirectory(prefix='nghe_p2_net_') as td:
  r=Path(td); f=[]
  f += [w(r,'kotlinx/coroutines/Core.kt',r'''package kotlinx.coroutines
object Dispatchers { object IO }
suspend fun <T> withContext(ctx:Any, block:suspend ()->T):T=block()
suspend fun delay(ms:Long){}
''')]
  f += [w(r,'kotlinx/coroutines/sync/Mutex.kt',r'''package kotlinx.coroutines.sync
class Mutex
suspend inline fun <T> Mutex.withLock(action:()->T):T=action()
''')]
  f += [w(r,'okhttp3/Ok.kt',r'''package okhttp3
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.time.Duration
open class RequestBody { companion object { fun ByteArray.toRequestBody(type:MediaType?):RequestBody=RequestBody() } }
open class FormBody:RequestBody(){ class Builder { fun add(name:String,value:String):Builder=this; fun build():FormBody=FormBody() } }
class MediaType { fun charset(default:Charset):Charset?=default; companion object { fun String.toMediaType():MediaType=MediaType() } }
class HttpUrl {
 val isHttps:Boolean=true
 val host:String="sangtacviet.vip"
 fun resolve(location:String):HttpUrl?=HttpUrl()
 override fun toString():String="https://sangtacviet.vip/"
 companion object { fun String.toHttpUrl():HttpUrl=HttpUrl() }
}
class Request private constructor(){ class Builder { fun url(url:HttpUrl):Builder=this; fun header(name:String,value:String):Builder=this; fun post(body:RequestBody):Builder=this; fun get():Builder=this; fun build():Request=Request() } }
class ResponseBody { fun contentLength():Long=0; fun byteStream():InputStream=ByteArrayInputStream(ByteArray(0)); fun contentType():MediaType?=MediaType() }
class Response:AutoCloseable { val code:Int=200; val isSuccessful:Boolean=true; val body:ResponseBody=ResponseBody(); fun headers(name:String):List<String> = emptyList(); fun header(name:String):String?=null; override fun close(){} }
class Call { fun execute():Response=Response() }
class OkHttpClient { fun newCall(request:Request):Call=Call(); class Builder { fun connectTimeout(v:Duration):Builder=this; fun readTimeout(v:Duration):Builder=this; fun callTimeout(v:Duration):Builder=this; fun followRedirects(v:Boolean):Builder=this; fun followSslRedirects(v:Boolean):Builder=this; fun retryOnConnectionFailure(v:Boolean):Builder=this; fun build():OkHttpClient=OkHttpClient() } }
''')]
  f += [w(r,'org/jsoup/Jsoup.kt',r'''package org.jsoup
import org.jsoup.nodes.Document
object Jsoup { fun parse(html:String,baseUri:String):Document=Document() }
''')]
  f += [w(r,'org/jsoup/nodes/Document.kt','package org.jsoup.nodes\nclass Document\n')]
  f += [
   ROOT/'app/src/main/java/vn/nghetruyen/app/sources/SourceSessionStore.kt',
   ROOT/'app/src/main/java/vn/nghetruyen/app/sources/HostRequestGovernor.kt',
   ROOT/'app/src/main/java/vn/nghetruyen/app/sources/HtmlDocumentClient.kt',
   ROOT/'app/src/main/java/vn/nghetruyen/app/sources/HttpHtmlClient.kt',
   ROOT/'app/src/main/java/vn/nghetruyen/app/sources/SessionHttpClient.kt',
  ]
  cp=subprocess.run([K,*map(str,f),'-d',str(r/'out.jar')],cwd=ROOT,text=True,capture_output=True)
  if cp.returncode:
   print(cp.stdout);print(cp.stderr);raise SystemExit(cp.returncode)
 print('P2_NETWORK_STATIC_OK')
if __name__=='__main__': main()
