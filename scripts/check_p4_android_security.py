#!/usr/bin/env python3
"""Compile encrypted AI credential store and validate platform-backup exclusions."""
from __future__ import annotations
import shutil, subprocess, tempfile, xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]; K=shutil.which('kotlinc')
def w(r,p,t): q=r/p;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(t,encoding='utf-8');return q

def main():
 if K:
  with tempfile.TemporaryDirectory(prefix='nghe_p4_key_') as td:
   r=Path(td); f=[]
   f += [w(r,'android/content/Context.kt',r'''package android.content
open class Context { open val applicationContext:Context get()=this; fun getSharedPreferences(name:String,mode:Int)=SharedPreferences(); companion object { const val MODE_PRIVATE=0 } }
class SharedPreferences { private val values=mutableMapOf<String,String>(); fun getString(k:String,d:String?):String?=values[k]?:d; fun contains(k:String):Boolean=values.containsKey(k); fun edit()=Editor(values) }
class Editor(private val values:MutableMap<String,String>){ fun putString(k:String,v:String)=apply{values[k]=v}; fun remove(k:String)=apply{values.remove(k)}; fun apply(){} }
''')]
   f += [w(r,'android/security/keystore/Key.kt',r'''package android.security.keystore
import java.security.spec.AlgorithmParameterSpec
class KeyGenParameterSpec:AlgorithmParameterSpec { class Builder(alias:String,purposes:Int){ fun setBlockModes(vararg v:String)=this; fun setEncryptionPaddings(vararg v:String)=this; fun setRandomizedEncryptionRequired(v:Boolean)=this; fun build()=KeyGenParameterSpec() } }
object KeyProperties { const val KEY_ALGORITHM_AES="AES"; const val PURPOSE_ENCRYPT=1; const val PURPOSE_DECRYPT=2; const val BLOCK_MODE_GCM="GCM"; const val ENCRYPTION_PADDING_NONE="NoPadding" }
''')]
   f += [w(r,'android/util/Base64.kt',r'''package android.util
object Base64 { const val NO_WRAP=2; fun encodeToString(v:ByteArray,flags:Int)=java.util.Base64.getEncoder().encodeToString(v); fun decode(v:String,flags:Int)=java.util.Base64.getDecoder().decode(v) }
''')]
   f += [w(r,'vn/nghetruyen/app/data/settings/AiProvider.kt','package vn.nghetruyen.app.data.settings\nenum class AiProvider { OPENAI_COMPATIBLE, GEMINI }\n')]
   f += [ROOT/'app/src/main/java/vn/nghetruyen/app/ai/AiCredentialStore.kt']
   cp=subprocess.run([K,*map(str,f),'-d',str(r/'out.jar')],cwd=ROOT,text=True,capture_output=True)
   if cp.returncode: print(cp.stdout);print(cp.stderr);raise SystemExit(cp.returncode)
   print('P4_CREDENTIAL_STATIC_COMPILE_OK')
 for rel in ['app/src/main/res/xml/backup_rules.xml','app/src/main/res/xml/data_extraction_rules.xml']:
  ET.parse(ROOT/rel)
  text=(ROOT/rel).read_text(encoding='utf-8')
  for token in ['encrypted_ai_credentials_v1.xml','encrypted_source_sessions_v1.xml','path="datastore/"','domain="database" path="."']:
   if token not in text: raise SystemExit(f'P4 backup exclusion missing in {rel}: {token}')
 print('P4_PLATFORM_BACKUP_EXCLUSION_OK')
if __name__=='__main__': main()
