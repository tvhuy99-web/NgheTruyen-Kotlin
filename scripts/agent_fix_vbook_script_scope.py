from pathlib import Path
import re

ROOT = Path('.')


def replace(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'expected block not found in {path}: {old[:160]!r}')
    target.write_text(text.replace(old, new, 1), encoding='utf-8')


prelude_path = 'app/src/main/java/vn/nghetruyen/app/sourceplatform/ChromiumVBookPrelude.kt'
old_loader = r'''              var __scriptExecutionPrelude='';
              function load(){
                throw new Error('VBOOK_LOAD_LITERAL_REQUIRED');
              }
              global.load=load;
              var __scriptApi={
                execute:function(rawPath,functionName){
                  var path=__path(rawPath), requested=String(functionName||'execute');
                  if(!/^[A-Za-z_$][A-Za-z0-9_$]{0,127}$/.test(requested)) throw new Error('VBOOK_SCRIPT_FUNCTION_INVALID');
                  var compiled=__rpc('script_compile',{path:path})||{};
                  var code=String(compiled.source||'');
                  var prefix=String(__scriptExecutionPrelude||'');
                  var factory=(0,eval)('(function(){\n'+prefix+'\n'+code+'\n;return (typeof '+requested+'===\'function\'?'+requested+':(typeof execute===\'function\'?execute:null));})\n//# sourceURL='+path.replace(/\s/g,'_'));
                  var fn=factory.call(global);
                  if(typeof fn!=='function') throw new Error('VBOOK_SCRIPT_FUNCTION_MISSING:'+requested);
                  return fn.apply(global,Array.prototype.slice.call(arguments,2));
                }
              };
              Object.defineProperty(__scriptApi,'__ngheSetExecutionPrelude',{
                value:function(code){__scriptExecutionPrelude=String(code||'');return true;},
                enumerable:false,
                writable:false,
                configurable:false
              });
              global.Script=Object.freeze(__scriptApi);
'''
new_loader = r'''              var __loadedScripts={};
              var __loadingScripts={};
              var __globalPreludeSource=null;
              var __scriptMarkerSeq=0;
              var __scriptMarkers={};
              function __source(raw){ return String(__rpc('resource_read',{path:__path(raw)})||''); }
              function __runClassicScript(path,code){
                path=__path(path);
                code=String(code==null?'':code);
                var marker='m'+String(++__scriptMarkerSeq);
                var capturedError='';
                function onError(event){
                  if(!capturedError) capturedError=String(event&&(event.error&&(event.error.stack||event.error.message)||event.message)||'VBOOK_SCRIPT_ERROR');
                }
                global.addEventListener('error',onError);
                try{
                  var node=global.document.createElement('script');
                  node.type='text/javascript';
                  node.text=code+'\n;globalThis.__ngheVBookScriptMarker("'+marker+'");\n//# sourceURL='+path.replace(/\s/g,'_');
                  var parent=global.document.head||global.document.documentElement||global.document.body;
                  if(!parent) throw new Error('VBOOK_SCRIPT_DOCUMENT_ROOT_MISSING');
                  parent.appendChild(node);
                  if(node.parentNode) node.parentNode.removeChild(node);
                }finally{
                  global.removeEventListener('error',onError);
                }
                var completed=__scriptMarkers[marker]===true;
                delete __scriptMarkers[marker];
                if(capturedError) throw new Error('VBOOK_SCRIPT_EXECUTION_FAILED:'+path+':'+capturedError);
                if(!completed) throw new Error('VBOOK_SCRIPT_EXECUTION_INCOMPLETE:'+path);
                return true;
              }
              Object.defineProperty(global,'__ngheVBookScriptMarker',{
                value:function(marker){__scriptMarkers[String(marker||'')]=true;},
                enumerable:false,
                writable:false,
                configurable:false
              });
              function load(raw){
                if(String(raw||'').toLowerCase()==='crypto.js') return true;
                var path=__path(raw);
                if(__loadedScripts[path]) return true;
                if(__loadingScripts[path]) throw new Error('VBOOK_LOAD_CYCLE:'+path);
                __loadingScripts[path]=true;
                try{
                  __runClassicScript(path,__source(path));
                  __loadedScripts[path]=true;
                  return true;
                }finally{
                  delete __loadingScripts[path];
                }
              }
              global.load=load;
              var __scriptApi={
                execute:function(rawPath,functionName){
                  var path=__path(rawPath), requested=String(functionName||'execute');
                  if(!/^[A-Za-z_$][A-Za-z0-9_$]{0,127}$/.test(requested)) throw new Error('VBOOK_SCRIPT_FUNCTION_INVALID');
                  var code=__source(path);
                  var factory=(0,eval)('(function(){\n'+code+'\n;return (typeof '+requested+'===\'function\'?'+requested+':(typeof execute===\'function\'?execute:null));})\n//# sourceURL='+path.replace(/\s/g,'_'));
                  var fn=factory.call(global);
                  if(typeof fn!=='function') throw new Error('VBOOK_SCRIPT_FUNCTION_MISSING:'+requested);
                  return fn.apply(global,Array.prototype.slice.call(arguments,2));
                }
              };
              Object.defineProperty(__scriptApi,'__ngheInstallGlobalPrelude',{
                value:function(code){
                  code=String(code||'');
                  if(!code) return true;
                  if(__globalPreludeSource!==null){
                    if(__globalPreludeSource!==code) throw new Error('VBOOK_GLOBAL_PRELUDE_CONFLICT');
                    return true;
                  }
                  __runClassicScript('src/__nghe_vbook_config.js',code);
                  __globalPreludeSource=code;
                  return true;
                },
                enumerable:false,
                writable:false,
                configurable:false
              });
              global.Script=Object.freeze(__scriptApi);
'''
replace(prelude_path, old_loader, new_loader)

runtime_path = ROOT / 'app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidChromiumVBookRuntime.kt'
runtime = runtime_path.read_text(encoding='utf-8')
runtime = runtime.replace('import vn.nghetruyen.source.vbook.VBookScriptBundleCompiler\n', '')
runtime = runtime.replace('                "script_compile" -> scriptCompile(payload)\n', '')
runtime = runtime.replace('        private const val MAX_COMPILED_SCRIPT_BYTES = 6 * 1024 * 1024\n', '')
runtime, count = re.subn(
    r'\n        private fun scriptCompile\(payload: JsonValue\.Obj\): JsonValue \{.*?\n        \}\n\n(?=        private fun resourceRead)',
    '\n',
    runtime,
    count=1,
    flags=re.S,
)
if count != 1:
    raise SystemExit('scriptCompile method block not found exactly once')
runtime_path.write_text(runtime, encoding='utf-8')

compat_path = 'source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookCompatibilityRuntime.kt'
replace(
    compat_path,
    "if (Script && typeof Script.__ngheSetExecutionPrelude === 'function') {\n              Script.__ngheSetExecutionPrelude(__vbookTargetScriptPrelude);\n            }",
    "if (Script && typeof Script.__ngheInstallGlobalPrelude === 'function') {\n              Script.__ngheInstallGlobalPrelude(__vbookTargetScriptPrelude);\n            }",
)

for obsolete in [
    'source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookScriptBundleCompiler.kt',
    'source-vbook/src/test/kotlin/vn/nghetruyen/source/vbook/VBookScriptBundleCompilerTest.kt',
]:
    path = ROOT / obsolete
    if path.exists():
        path.unlink()

print('dynamic classic-script vBook loader staged successfully')
