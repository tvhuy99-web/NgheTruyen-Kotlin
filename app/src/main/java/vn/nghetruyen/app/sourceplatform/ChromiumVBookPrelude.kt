package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue

/**
 * Synchronous string-only host surface for the headless Chromium action runtime.
 *
 * The only native rendezvous is window.prompt(token, json). No Java object is installed into the
 * page. The random token stays inside this closure and every host operation is still revalidated by
 * the Kotlin broker boundary.
 */
internal object ChromiumVBookPrelude {
    fun build(bridgeToken: String, entryPath: String, inputJson: String): String {
        val token = JsonCodec.stringify(JsonValue.Str(bridgeToken))
        val entry = JsonCodec.stringify(JsonValue.Str(entryPath))
        val input = JsonCodec.stringify(JsonValue.Str(inputJson))
        return """
            (function(global){
              'use strict';
              var __nativePrompt = global.prompt.bind(global);
              var __bridgeToken = $token;
              function __rpc(op,payload){
                var request = JSON.stringify({op:String(op||''),payload:(payload&&typeof payload==='object'?payload:{})});
                var raw = __nativePrompt(__bridgeToken, request);
                if (raw === null || raw === undefined) throw new Error('CHROMIUM_BRIDGE_CANCELLED:' + op);
                var envelope;
                try { envelope = JSON.parse(String(raw)); }
                catch (error) { throw new Error('CHROMIUM_BRIDGE_RESPONSE_INVALID:' + op); }
                if (!envelope || envelope.ok !== true) throw new Error(String(envelope && envelope.error || ('CHROMIUM_BRIDGE_FAILED:' + op)));
                return envelope.value;
              }
              function __bridge(op,payload){
                op=String(op||'');
                if(op==='host_command') return __rpc('host_command',payload||{});
                if(op==='native_hook') return __rpc('native_hook',payload||{});
                throw new Error('VBOOK_BRIDGE_OPERATION_DENIED:' + op);
              }
              Object.defineProperty(global,'__bridge',{value:__bridge,writable:false,configurable:false});

              function __path(raw){
                var clean=String(raw||'').replace(/\\/g,'/').replace(/^\/+/, '');
                return clean.indexOf('src/')===0?clean:'src/'+clean;
              }
              var __loaded={};
              function __source(raw){ return String(__rpc('resource_read',{path:__path(raw)})||''); }
              function load(raw){
                var path=__path(raw);
                if(__loaded[path]) return true;
                __loaded[path]=true;
                var code=__source(path);
                (0,eval)(code+'\n//# sourceURL='+path.replace(/\s/g,'_'));
                return true;
              }
              global.load=load;
              global.Script=Object.freeze({
                execute:function(rawPath,functionName){
                  var path=__path(rawPath), requested=String(functionName||'execute');
                  if(!/^[A-Za-z_$][A-Za-z0-9_$]{0,127}$/.test(requested)) throw new Error('VBOOK_SCRIPT_FUNCTION_INVALID');
                  var code=__source(path);
                  var factory=(0,eval)('(function(){\n'+code+'\n;return (typeof '+requested+'===\'function\'?'+requested+':(typeof execute===\'function\'?execute:null));})\n//# sourceURL='+path.replace(/\s/g,'_'));
                  var fn=factory.call(global);
                  if(typeof fn!=='function') throw new Error('VBOOK_SCRIPT_FUNCTION_MISSING:'+requested);
                  return fn.apply(global,Array.prototype.slice.call(arguments,2));
                }
              });

              function __nativeElements(nodes,baseUrl){
                var arr=[];
                for(var i=0;i<nodes.length;i++) arr.push(__nativeElement(nodes[i],baseUrl));
                arr.get=function(i){return arr[Number(i)||0];};
                arr.eq=arr.get;
                arr.size=function(){return arr.length;};
                arr.isEmpty=function(){return arr.length===0;};
                arr.first=function(){return arr.length?arr[0]:undefined;};
                arr.last=function(){return arr.length?arr[arr.length-1]:undefined;};
                arr.text=function(){return arr.map(function(v){return v.text();}).filter(Boolean).join(' ');};
                arr.html=function(){return arr.map(function(v){return v.html();}).join('\n');};
                arr.outerHtml=function(){return arr.map(function(v){return v.outerHtml();}).join('\n');};
                arr.attr=function(name){return arr.length?arr[0].attr(name):'';};
                arr.eachText=function(){return arr.map(function(v){return v.text();});};
                arr.texts=arr.eachText;
                arr.toArray=function(){return arr.slice();};
                arr.select=function(selector){
                  var merged=[];
                  for(var j=0;j<arr.length;j++){
                    var nested=arr[j].select(selector);
                    for(var k=0;k<nested.length;k++) merged.push(nested[k]);
                  }
                  return __nativeElements(merged.map(function(v){return v.__node;}),baseUrl);
                };
                return arr;
              }
              function __nativeElement(node,baseUrl){
                if(!node) return undefined;
                var out={__node:node};
                out.select=function(selector){return __nativeElements(node.querySelectorAll(String(selector||'')),baseUrl);};
                out.selectFirst=function(selector){return __nativeElement(node.querySelector(String(selector||'')),baseUrl);};
                out.text=function(){return String(node.textContent||'').replace(/\s+/g,' ').trim();};
                out.ownText=function(){
                  var text=''; for(var i=0;i<node.childNodes.length;i++) if(node.childNodes[i].nodeType===3) text+=node.childNodes[i].nodeValue||'';
                  return text.replace(/\s+/g,' ').trim();
                };
                out.wholeText=function(){return String(node.textContent||'');};
                out.html=function(){return String(node.innerHTML||'');};
                out.outerHtml=function(){return String(node.outerHTML||'');};
                out.attr=function(name){return String(node.getAttribute(String(name||''))||'');};
                out.absUrl=function(name){
                  var raw=out.attr(name); if(!raw)return '';
                  try{return new URL(raw,String(baseUrl||'')).href;}catch(e){return raw;}
                };
                out.id=function(){return String(node.id||'');};
                out.tagName=function(){return String(node.tagName||'').toLowerCase();};
                out.hasClass=function(name){return !!(node.classList&&node.classList.contains(String(name||'')));};
                out.parent=function(){return __nativeElement(node.parentElement,baseUrl);};
                out.children=function(){return __nativeElements(node.children||[],baseUrl);};
                out.remove=function(){if(node.parentNode)node.parentNode.removeChild(node);return out;};
                return out;
              }
              function __nativeDocument(content,baseUrl){
                var doc=(new DOMParser()).parseFromString(String(content==null?'':content),'text/html');
                var out={};
                out.select=function(selector){return __nativeElements(doc.querySelectorAll(String(selector||'')),baseUrl);};
                out.selectFirst=function(selector){return __nativeElement(doc.querySelector(String(selector||'')),baseUrl);};
                out.first=out.selectFirst;
                out.text=function(){return String(doc.body&&doc.body.textContent||'').replace(/\s+/g,' ').trim();};
                out.html=function(){return String(doc.documentElement&&doc.documentElement.innerHTML||'');};
                out.outerHtml=function(){return String(doc.documentElement&&doc.documentElement.outerHTML||'');};
                out.title=function(){return String(doc.title||'');};
                out.location=function(){return String(baseUrl||'');};
                out.baseUri=out.location;
                out.body=function(){return __nativeElement(doc.body,baseUrl);};
                return out;
              }
              global.Html=global.HTML=global.Document={parse:function(content,baseUrl){return __nativeDocument(content,baseUrl);}};

              function __storage(prefix){
                prefix=String(prefix||'');
                var out={};
                function keyName(k){return prefix+String(k||'');}
                out.get=function(k){return __rpc('storage_get',{key:keyName(k)});};
                out.put=function(k,v){__rpc('storage_put',{key:keyName(k),value:String(v==null?'':v)});return true;};
                out.remove=function(k){__rpc('storage_remove',{key:keyName(k)});return true;};
                out.keys=function(extra){
                  var values=__rpc('storage_keys',{prefix:prefix+String(extra||'')})||[];
                  return values.map(function(k){return String(k).substring(prefix.length);});
                };
                out.key=function(i){return out.keys('')[Number(i)||0];};
                out.clearPrefix=function(extra){__rpc('storage_clear_prefix',{prefix:prefix+String(extra||'')});return true;};
                out.clear=function(){__rpc('storage_clear_prefix',{prefix:prefix});return true;};
                out.getItem=out.get; out.setItem=out.put; out.removeItem=out.remove;
                Object.defineProperty(out,'length',{get:function(){return out.keys('').length;}});
                return out;
              }
              function __installHostGlobal(name,value){
      name=String(name||'');
      try {
        Object.defineProperty(global,name,{value:value,writable:true,configurable:true,enumerable:true});
        return value;
      } catch(error) {
        throw new Error('CHROMIUM_GLOBAL_INSTALL_FAILED:'+name+':'+String(error&&(error.message||error)||'unknown'));
      }
    }
    var __vbookLocalStorage=__storage('');
    __installHostGlobal('Storage',__vbookLocalStorage);
    __installHostGlobal('localStorage',__vbookLocalStorage);
              global.cacheStorage=__storage('cache:');
              global.localConfig=Object.freeze({getItem:function(){return undefined;},key:function(){return undefined;},length:0});

              function __copyHeaders(value){
                var input=value&&typeof value==='object'?value:{},out={},keys=Object.keys(input);
                for(var i=0;i<keys.length;i++)out[String(keys[i])]=String(input[keys[i]]==null?'':input[keys[i]]);
                return out;
              }
              function __response(raw,requestUrl){
                raw=raw||{}; var body=String(raw.body==null?'':raw.body), headers=raw.headers||{};
                var out={ok:Number(raw.status||0)>=200&&Number(raw.status||0)<300,status:Number(raw.status||0),statusCode:Number(raw.status||0),url:String(raw.url||requestUrl||''),headers:headers,body:body};
                out.text=function(){return body;}; out.string=out.text;
                out.json=function(){return JSON.parse(body);};
                out.html=function(){return Html.parse(body,out.url);}; out.document=out.html;
                out.header=function(name){name=String(name||'').toLowerCase();var keys=Object.keys(headers);for(var i=0;i<keys.length;i++)if(keys[i].toLowerCase()===name)return headers[keys[i]];return undefined;};
                return out;
              }
              global.fetch=function(url,options){
                options=options||{}; var body=options.body;
                if(body&&typeof body==='object')body=JSON.stringify(body);
                var raw=__rpc('network_fetch',{url:String(url||''),method:String(options.method||'GET'),headers:__copyHeaders(options.headers),body:String(body==null?'':body),contentType:options.contentType==null?null:String(options.contentType),timeoutMs:Number(options.timeout||0)});
                return __response(raw,url);
              };
              global.Http=global.HTTP=Object.freeze({
                get:function(url,headers){return fetch(url,{method:'GET',headers:headers||{}});},
                post:function(url,body,headers){return fetch(url,{method:'POST',body:body,headers:headers||{}});},
                fetch:global.fetch
              });

              function __binaryToBase64(binary){var out='';for(var i=0;i<binary.length;i++)out+=String.fromCharCode(binary[i]&255);return btoa(out);}
              function __base64ToBytes(text){var raw=atob(String(text||'').replace(/\s+/g,'')),out=[];for(var i=0;i<raw.length;i++)out.push(raw.charCodeAt(i)&255);return out;}
              function __utf8ToBase64(text){return btoa(unescape(encodeURIComponent(String(text==null?'':text))));}
              function __base64ToUtf8(text){try{return decodeURIComponent(escape(atob(String(text||'').replace(/\s+/g,''))));}catch(e){return '';}}
              function __hexToBase64(hex){hex=String(hex||'').replace(/\s+/g,'');var bytes=[];for(var i=0;i<hex.length;i+=2)bytes.push(parseInt(hex.substr(i,2),16)||0);return __binaryToBase64(bytes);}
              function __base64ToHex(text){return __base64ToBytes(text).map(function(v){return ('0'+v.toString(16)).slice(-2);}).join('');}
              function __hashBase64(algorithm,data){return String(__rpc('crypto_digest',{algorithm:String(algorithm||'SHA-256'),dataBase64:String(data||'')})||'');}
              function __hmacBase64(algorithm,data,key){return String(__rpc('crypto_hmac',{algorithm:String(algorithm||'HmacSHA256'),dataBase64:String(data||''),keyBase64:String(key||'')})||'');}
              global.Crypto=Object.freeze({
                md5:function(v,format){var h=__hashBase64('MD5',__utf8ToBase64(v));return String(format||'hex').toLowerCase()==='base64'?__hexToBase64(h):h;},
                sha1:function(v,format){var h=__hashBase64('SHA-1',__utf8ToBase64(v));return String(format||'hex').toLowerCase()==='base64'?__hexToBase64(h):h;},
                sha256:function(v,format){var h=__hashBase64('SHA-256',__utf8ToBase64(v));return String(format||'hex').toLowerCase()==='base64'?__hexToBase64(h):h;},
                sha512:function(v,format){var h=__hashBase64('SHA-512',__utf8ToBase64(v));return String(format||'hex').toLowerCase()==='base64'?__hexToBase64(h):h;},
                hmacMd5:function(v,k,format){var h=__hmacBase64('HmacMD5',__utf8ToBase64(v),__utf8ToBase64(k));return String(format||'hex').toLowerCase()==='base64'?__hexToBase64(h):h;},
                hmacSha1:function(v,k,format){var h=__hmacBase64('HmacSHA1',__utf8ToBase64(v),__utf8ToBase64(k));return String(format||'hex').toLowerCase()==='base64'?__hexToBase64(h):h;},
                hmacSha256:function(v,k,format){var h=__hmacBase64('HmacSHA256',__utf8ToBase64(v),__utf8ToBase64(k));return String(format||'hex').toLowerCase()==='base64'?__hexToBase64(h):h;},
                hmacSha512:function(v,k,format){var h=__hmacBase64('HmacSHA512',__utf8ToBase64(v),__utf8ToBase64(k));return String(format||'hex').toLowerCase()==='base64'?__hexToBase64(h):h;},
                hashBase64:__hashBase64,hmacBase64:__hmacBase64,
                utf8ToBase64:__utf8ToBase64,base64ToUtf8:__base64ToUtf8,
                latin1ToBase64:function(v){return btoa(String(v||''));},base64ToLatin1:function(v){return atob(String(v||''));},
                hexToBase64:__hexToBase64,base64ToHex:__base64ToHex,
                concatBase64:function(a,b){return __binaryToBase64(__base64ToBytes(a).concat(__base64ToBytes(b)));},
                randomBase64:function(n){var bytes=new Uint8Array(Math.max(0,Math.min(65536,Number(n)||0)));global.crypto.getRandomValues(bytes);return __binaryToBase64(Array.prototype.slice.call(bytes));},
                base64Length:function(v){return __base64ToBytes(v).length;},
                aes:function(operation,data,keyType,keyBase64,passphrase,ivBase64,mode,padding){return __rpc('crypto_aes',{operation:String(operation||'decrypt'),dataBase64:String(data||''),keyType:String(keyType||'raw'),keyBase64:String(keyBase64||''),passphrase:String(passphrase||''),ivBase64:String(ivBase64||''),mode:String(mode||'CBC'),padding:String(padding||'PKCS7')});},
                encrypt:function(v){return __rpc('crypto_gcm_encrypt',{text:String(v==null?'':v)});},
                decrypt:function(v){return __rpc('crypto_gcm_decrypt',{dataBase64:String(v||'')});}
              });

              var Response=Object.freeze({
                success:function(data,data2){return JSON.stringify({code:0,data:data,data2:(data2===undefined?null:data2)});},
                error:function(message){return JSON.stringify({code:1,data:String(message||'VBook error')});}
              });
              function __cryptoWord(o){o=o||{};if(o.__base64!==undefined)o.__base64=String(o.__base64||'').replace(/\s+/g,'');if(o.__hex!==undefined)o.__hex=String(o.__hex||'').replace(/\s+/g,'').toLowerCase();if(o.__text!==undefined)o.__text=String(o.__text==null?'':o.__text);o.toString=function(enc){if(enc&&enc.stringify)return enc.stringify(o);return __cryptoToHex(o);};o.concat=function(other){o.__base64=Crypto.concatBase64(__cryptoToBase64(o),__cryptoToBase64(other));delete o.__hex;delete o.__text;o.sigBytes=Crypto.base64Length(o.__base64);return o;};o.clamp=function(){return o;};o.clone=function(){return __cryptoWord({__base64:__cryptoToBase64(o),sigBytes:o.sigBytes});};if(o.sigBytes===undefined)o.sigBytes=Crypto.base64Length(__cryptoToBase64(o));return o;}
              function __cryptoToBase64(v){if(v&&typeof v==='object'){if(v.ciphertext)return __cryptoToBase64(v.ciphertext);if(v.__base64!==undefined)return String(v.__base64||'');if(v.__hex!==undefined)return Crypto.hexToBase64(v.__hex);if(v.__text!==undefined)return Crypto.utf8ToBase64(String(v.__text||''));}return Crypto.utf8ToBase64(String(v==null?'':v));}
              function __cryptoToHex(v){if(v&&typeof v==='object'&&v.__hex!==undefined)return String(v.__hex||'');return Crypto.base64ToHex(__cryptoToBase64(v));}
              function __cryptoRawText(v){if(v&&typeof v==='object'){if(v.__text!==undefined)return String(v.__text||'');if(v.__base64!==undefined)return Crypto.base64ToUtf8(v.__base64);if(v.__hex!==undefined)return Crypto.base64ToUtf8(Crypto.hexToBase64(v.__hex));}return String(v==null?'':v);}
              function __cryptoWordCreate(words,sigBytes){if(words&&words.__base64!==undefined)return __cryptoWord({__base64:words.__base64,sigBytes:sigBytes});if(typeof words==='string')return __cryptoWord({__text:words,sigBytes:sigBytes});words=Array.isArray(words)?words:[];var needed=sigBytes==null?words.length*4:Number(sigBytes),hex='';for(var i=0;i<words.length&&hex.length/2<needed;i++){var w=Number(words[i])>>>0;for(var shift=24;shift>=0&&hex.length/2<needed;shift-=8)hex+=('0'+((w>>>shift)&255).toString(16)).slice(-2);}return __cryptoWord({__base64:Crypto.hexToBase64(hex),sigBytes:Math.min(needed,hex.length/2)});}
              function __cryptoCipherParams(b64){var p={ciphertext:__cryptoWord({__base64:String(b64||'')})};p.toString=function(format){if(format&&format.stringify)return format.stringify(p);return String(b64||'');};return p;}
              var CryptoJS=(function(){var enc={Utf8:{parse:function(s){return __cryptoWord({__text:String(s==null?'':s)});},stringify:function(w){return __cryptoRawText(w);}},Base64:{parse:function(s){return __cryptoWord({__base64:String(s||'')});},stringify:function(w){return __cryptoToBase64(w);}},Hex:{parse:function(s){return __cryptoWord({__hex:String(s||'').toLowerCase()});},stringify:function(w){return __cryptoToHex(w);}},Latin1:{parse:function(s){return __cryptoWord({__base64:Crypto.latin1ToBase64(String(s||''))});},stringify:function(w){return Crypto.base64ToLatin1(__cryptoToBase64(w));}}};function digest(alg,v){return __cryptoWord({__hex:Crypto.hashBase64(alg,__cryptoToBase64(v))});}function hmac(alg,m,k){return __cryptoWord({__hex:Crypto.hmacBase64(alg,__cryptoToBase64(m),__cryptoToBase64(k))});}var mode={CBC:{__name:'CBC'},ECB:{__name:'ECB'}};var pad={Pkcs7:{__name:'PKCS7'},NoPadding:{__name:'NoPadding'}};function modeName(opts){return opts&&opts.mode&&opts.mode.__name?opts.mode.__name:'CBC';}function paddingName(opts){return opts&&opts.padding&&opts.padding.__name?opts.padding.__name:'PKCS7';}function cipherBase64(v){if(v&&typeof v==='object'&&v.ciphertext)return __cryptoToBase64(v.ciphertext);if(typeof v==='string')return v.replace(/\s+/g,'');return __cryptoToBase64(v);}var format={OpenSSL:{stringify:function(params){return __cryptoToBase64(params&&params.ciphertext?params.ciphertext:params);},parse:function(text){return __cryptoCipherParams(String(text||''));}}};var AES={encrypt:function(message,key,opts){opts=opts||{};var pass=typeof key==='string';var out=Crypto.aes('encrypt',__cryptoToBase64(message),pass?'passphrase':'raw',pass?'':__cryptoToBase64(key),pass?String(key):'',opts.iv?__cryptoToBase64(opts.iv):'',modeName(opts),paddingName(opts));return __cryptoCipherParams(out);},decrypt:function(ciphertext,key,opts){opts=opts||{};var pass=typeof key==='string';var out=Crypto.aes('decrypt',cipherBase64(ciphertext),pass?'passphrase':'raw',pass?'':__cryptoToBase64(key),pass?String(key):'',opts.iv?__cryptoToBase64(opts.iv):'',modeName(opts),paddingName(opts));return __cryptoWord({__base64:out});}};return {lib:{WordArray:{create:__cryptoWordCreate,random:function(n){return __cryptoWord({__base64:Crypto.randomBase64(Math.max(0,Number(n)||0))});}}},enc:enc,mode:mode,pad:pad,format:format,MD5:function(v){return digest('MD5',v);},SHA1:function(v){return digest('SHA-1',v);},SHA256:function(v){return digest('SHA-256',v);},SHA512:function(v){return digest('SHA-512',v);},HmacMD5:function(m,k){return hmac('HmacMD5',m,k);},HmacSHA1:function(m,k){return hmac('HmacSHA1',m,k);},HmacSHA256:function(m,k){return hmac('HmacSHA256',m,k);},HmacSHA512:function(m,k){return hmac('HmacSHA512',m,k);},AES:AES};})();
              global.Response=Response;
              global.CryptoJS=CryptoJS;

              global.localCookie=Object.freeze({
                getCookie:function(url){return String(__rpc('cookie_get',{url:String(url||'')})||'');},
                setCookie:function(cookie,url){__rpc('cookie_set',{cookie:String(cookie||''),url:String(url||'')});return true;},
                clear:function(){__rpc('cookie_clear',{});return true;}
              });
              global.UserAgent=Object.freeze({
                system:function(){return String(__rpc('user_agent',{})||'');},
                android:function(){return String(__rpc('user_agent',{})||'');},
                chrome:function(){return String(__rpc('user_agent',{})||'');},
                ios:function(){return 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1';}
              });
              global.Qt={translate:function(text,target,options){options=options&&typeof options==='object'?options:{};return __rpc('translate',{text:String(text==null?'':text),targetLanguage:(typeof target==='string'?target:String(options.targetLanguage||options.to||'vi')),sourceLanguage:String(options.sourceLanguage||options.from||''),storyId:String(options.storyId||''),chapterId:String(options.chapterId||''),instruction:String(options.instruction||'')});}};

              function __browserMatch(url,pattern){
                url=String(url||''); pattern=String(pattern||'');
                if(!pattern) return false;
                if(url.toLowerCase().indexOf(pattern.toLowerCase())>=0) return true;
                var explicitRegex=pattern.indexOf('regex:')===0;
                var raw=explicitRegex?pattern.substring(6):pattern;
                var looksRegex=explicitRegex||raw.indexOf('.*')>=0||/[+?^$()|\[\]\\]/.test(raw);
                try {
                  if(looksRegex) return new RegExp(raw).test(url);
                  if(raw.indexOf('*')>=0){
                    var escaped=raw.split('*').map(function(part){return part.replace(/[.*+?^${'$'}{}()|\[\]\\]/g,'\\${'$'}&');}).join('.*');
                    return new RegExp('^'+escaped+'${'$'}','i').test(url);
                  }
                } catch(ignored) { return false; }
                return false;
              }
              function __browser(){
                var out={},lastUrl='';
                function action(name,payload){payload=payload||{};payload.action=name;return __rpc('browser_action',payload)||{};}
                function updateUrl(response,fallback){response=response||{};lastUrl=String(response.finalUrl||fallback||lastUrl||'');return response;}
                function snapshot(){var response=updateUrl(action('DOM_SNAPSHOT',{}),lastUrl);return Html.parse(response.value||'',lastUrl);}
                out.launch=function(url){var target=String(url||'');updateUrl(action('NAVIGATE',{url:target}),target);return snapshot();};
                out.launchAsync=function(url){var target=String(url||'');updateUrl(action('NAVIGATE',{url:target}),target);return true;};
                out.loadHtml=function(first,second){
                  var a=String(first==null?'':first),b=String(second==null?'':second);
                  var aLooksHtml=/<[A-Za-z!/][^>]*>/.test(a),aLooksUrl=/^[A-Za-z][A-Za-z0-9+.-]*:\/\//.test(a),bLooksUrl=/^[A-Za-z][A-Za-z0-9+.-]*:\/\//.test(b);
                  var swap=(aLooksHtml&&(bLooksUrl||b.indexOf('/')===0))||(bLooksUrl&&!aLooksUrl);
                  var baseUrl=swap?b:a,html=swap?a:b;
                  updateUrl(action('LOAD_HTML',{url:baseUrl,value:html}),baseUrl);
                  return out;
                };
                out.waitSelector=function(selector,timeoutMs){return action('WAIT_SELECTOR',{selector:String(selector||''),timeoutMs:Number(timeoutMs||0)}).value;};
                out.requests=function(pattern){
                  var values=action('REQUEST_METADATA',{}).metadata||[];
                  if(pattern===undefined||pattern===null||String(pattern)==='') return values;
                  var patterns=Array.isArray(pattern)?pattern:[pattern];
                  return values.filter(function(item){var url=String(item&&item.url||'');for(var i=0;i<patterns.length;i++)if(__browserMatch(url,patterns[i]))return true;return false;});
                };
                out.waitRequest=function(pattern,timeoutMs){
                  var patterns=Array.isArray(pattern)?pattern:[pattern], deadline=Date.now()+Math.max(0,Number(timeoutMs||15000));
                  do {
                    var values=out.requests();
                    for(var i=values.length-1;i>=0;i--){var url=String(values[i]&&values[i].url||'');for(var j=0;j<patterns.length;j++)if(__browserMatch(url,patterns[j]))return values[i];}
                    sleep(100);
                  } while(Date.now()<deadline);
                  return false;
                };
                out.urls=function(){var values=out.requests();var result=[];for(var i=0;i<values.length;i++){var url=String(values[i]&&values[i].url||'');if(url&&result.indexOf(url)<0)result.push(url);}return result;};
                out.html=function(){return snapshot();};
                out.callJs=function(script){return action('EVALUATE_PAGE_SCRIPT',{script:String(script||'')}).value;}; out.evaluate=out.callJs;
                out.callJson=function(script){var raw=out.callJs(script);try{return JSON.parse(String(raw));}catch(ignored){return undefined;}};
                out.callJsAsync=function(script){return action('EVALUATE_PAGE_SCRIPT_ASYNC',{script:String(script||'')}).value;}; out.evaluate_async=out.callJsAsync;
                out.tapSelector=function(selector){return action('CLICK',{selector:String(selector||'')}).value;}; out.tap_selector=out.tapSelector;
                out.getVariable=function(name){return out.callJs(String(name||''));};
                out.cookie=function(url){return localCookie.getCookie(url);}; out.cookieSnapshot=out.cookie;
                out.syncSession=function(url,direction){return action('SYNC_SESSION',{url:String(url||''),options:{direction:String(direction||'both')}}).value;};
                out.setCookies=function(cookies,url){return action('SET_COOKIES',{url:String(url||''),values:Array.isArray(cookies)?cookies:[cookies]}).value;};
                out.clearCookies=function(url,names){return action('CLEAR_COOKIES',{url:String(url||''),values:Array.isArray(names)?names:[]}).value;};
                out.block=function(patterns){return action('SET_BLOCK_PATTERNS',{values:Array.isArray(patterns)?patterns:[patterns]}).value;};
                out.setUserAgent=function(value){return action('SET_USER_AGENT',{value:String(value||'')}).value;};
                out.setReplayPolicy=function(){return true;};
                out.setDialogPolicy=function(policy){return action('SET_DIALOG_POLICY',{options:policy&&typeof policy==='object'?policy:{defaultAction:String(policy||'dismiss')}}).value;};
                out.dialogs=function(){return action('DIALOGS',{}).dialogs||[];};
                out.lastDialog=function(){var values=out.dialogs();return values.length?values[values.length-1]:undefined;};
                out.waitDialog=function(options,timeoutMs){return action('WAIT_DIALOG',{options:options&&typeof options==='object'?options:{},timeoutMs:Number(timeoutMs||0)}).dialog;};
                out.close=function(){return action('CLOSE_SESSION',{}).value;};
                out.currentUrl=function(){var response=updateUrl(action('REQUEST_METADATA',{}),lastUrl);return String(response.finalUrl||lastUrl||'');};
                return out;
              }
              global.Browser=__browser();
              global.Engine={newBrowser:function(){return __browser();},browser:function(){return __browser();}};

              global.WebSocketHost=Object.freeze({exchange:function(url,messages,maxResponses){var r=__rpc('websocket_exchange',{url:String(url||''),messages:Array.isArray(messages)?messages:[],maxResponses:Number(maxResponses||1)});return r&&r.messages||[];}});
              global.WebSocket=function(url){
                var currentUrl=String(url||''),sent=[],connected=false,closeCode=null,closeReason=null;
                return {
                  send:function(v){sent.push(String(v==null?'':v));return true;},
                  connect:function(override){if(arguments.length)currentUrl=String(override||'');connected=true;return true;},
                  message:function(maxResponses){var r=__rpc('websocket_exchange',{url:currentUrl,messages:sent.splice(0),maxResponses:Number(maxResponses||1)});closeCode=r.closeCode;closeReason=r.closeReason;var values=r.messages||[];return values.length?values[0]:'';},
                  messages:function(maxResponses){var r=__rpc('websocket_exchange',{url:currentUrl,messages:sent.splice(0),maxResponses:Number(maxResponses||16)});closeCode=r.closeCode;closeReason=r.closeReason;return r.messages||[];},
                  close:function(){connected=false;return true;},
                  get connected(){return connected;},get closeCode(){return closeCode;},get closeReason(){return closeReason;}
                };
              };

              global.Graphics=Object.freeze({
                createImage:function(base64){return {__vbookImage:true,base64:String(base64||'')};},
                createCanvas:function(width,height){
                  var operations=[],alpha=1;
                  return {
                    drawImage:function(image){operations.push({imageBase64:String(image&&image.base64||''),args:Array.prototype.slice.call(arguments,1).map(Number),alpha:Number(alpha||1)});return true;},
                    setGlobalAlpha:function(v){alpha=Number(v||1);return true;},
                    toBase64:function(format,quality){return __rpc('graphics_render',{width:Number(width||1),height:Number(height||1),operations:operations,format:String(format||'PNG'),quality:Number(quality||100)});},
                    toDataURL:function(format,quality){var f=String(format||'PNG').toLowerCase();return 'data:image/'+f+';base64,'+this.toBase64(format,quality);}
                  };
                }
              });
              global.sleep=function(ms){__rpc('sleep',{millis:Number(ms||0)});return true;};
              function __log(level,args){try{__rpc('log',{level:level,message:Array.prototype.slice.call(args).map(function(v){try{return String(v);}catch(e){return '[value]';}}).join(' ')});}catch(e){}return true;}
              global.Log=global.Console=Object.freeze({d:function(){return __log('DEBUG',arguments);},i:function(){return __log('INFO',arguments);},w:function(){return __log('WARN',arguments);},e:function(){return __log('ERROR',arguments);},debug:function(){return __log('DEBUG',arguments);},info:function(){return __log('INFO',arguments);},log:function(){return __log('INFO',arguments);},warn:function(){return __log('WARN',arguments);},error:function(){return __log('ERROR',arguments);}});
              global.console=global.Console;

              var __payload=JSON.parse($input);
              return String(Script.execute($entry,'execute',__payload));
            })(this)
        """.trimIndent()
    }
}