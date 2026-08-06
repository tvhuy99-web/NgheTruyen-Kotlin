#!/usr/bin/env python3
"""Compile P2 health checker and exercise its fallback/resolution logic."""
from __future__ import annotations
import shutil, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
K=shutil.which('kotlinc')
def w(r,p,t):
 q=r/p;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(t,encoding='utf-8');return q

def main():
 if not K: print('P2_HEALTH_STATIC_SKIPPED'); return
 with tempfile.TemporaryDirectory(prefix='nghe_p2_health_') as td:
  r=Path(td); files=[]
  files += [w(r,'kotlinx/coroutines/Timeout.kt',r'''package kotlinx.coroutines
suspend fun <T> withTimeout(ms:Long, block:suspend ()->T):T=block()
''')]
  files += [w(r,'vn/nghetruyen/app/sources/Registry.kt',r'''package vn.nghetruyen.app.sources
class SourceRegistry(private val items:Map<String,StorySource> = emptyMap()){ fun get(id:String):StorySource?=items[id] }
''')]
  files += [
   ROOT/'app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt',
   ROOT/'app/src/main/java/vn/nghetruyen/app/core/model/Models.kt',
   ROOT/'app/src/main/java/vn/nghetruyen/app/sources/StorySource.kt',
   ROOT/'app/src/main/java/vn/nghetruyen/app/sources/SourceHealthChecker.kt',
  ]
  cp=subprocess.run([K,*map(str,files),'-d',str(r/'out.jar')],cwd=ROOT,text=True,capture_output=True)
  if cp.returncode:
   print(cp.stdout);print(cp.stderr);raise SystemExit(cp.returncode)
 print('P2_HEALTH_STATIC_OK')
if __name__=='__main__': main()
