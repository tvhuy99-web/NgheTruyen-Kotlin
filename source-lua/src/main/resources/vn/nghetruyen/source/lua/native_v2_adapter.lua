local NativeApi = require "app.sources.native_api"

local Adapter = {}

local function text(v)
  if v == nil then return "" end
  return tostring(v)
end

local function isArray(value)
  if type(value) ~= "table" then return false end
  local max, count = 0, 0
  for key in pairs(value) do
    if type(key) ~= "number" or key < 1 or key % 1 ~= 0 then return false end
    if key > max then max = key end
    count = count + 1
  end
  return max == count
end

local function jsonEscape(value)
  local s = tostring(value or "")
  return s:gsub('[%z\1-\31\\"]', function(ch)
    local map = {['\\']='\\\\', ['"']='\\"', ['\b']='\\b', ['\f']='\\f', ['\n']='\\n', ['\r']='\\r', ['\t']='\\t'}
    return map[ch] or string.format('\\u%04x', string.byte(ch))
  end)
end

local function jsonEncode(value, _)
  local seen = {}
  local function encode(v, depth)
    if depth > 64 then error("Native Source JSON vượt độ sâu cho phép") end
    local typ = type(v)
    if typ == "nil" then return "null" end
    if typ == "boolean" then return v and "true" or "false" end
    if typ == "number" then
      if v ~= v or v == math.huge or v == -math.huge then return "null" end
      return tostring(v)
    end
    if typ == "string" then return '"' .. jsonEscape(v) .. '"' end
    if typ ~= "table" then return '"' .. jsonEscape(tostring(v)) .. '"' end
    if seen[v] then error("Native Source JSON có vòng tham chiếu") end
    seen[v] = true
    local parts = {}
    if isArray(v) then
      for i = 1, #v do parts[#parts + 1] = encode(v[i], depth + 1) end
      seen[v] = nil
      return "[" .. table.concat(parts, ",") .. "]"
    end
    local keys = {}
    for key, item in pairs(v) do
      if type(key) == "string" and type(item) ~= "function" and type(item) ~= "userdata" and type(item) ~= "thread" then
        keys[#keys + 1] = key
      end
    end
    table.sort(keys)
    for _, key in ipairs(keys) do parts[#parts + 1] = '"' .. jsonEscape(key) .. '":' .. encode(v[key], depth + 1) end
    seen[v] = nil
    return "{" .. table.concat(parts, ",") .. "}"
  end
  return encode(value, 0)
end

local CORE_TEMPLATE = [====[
load("crypto.js");
var NativeV2=(function(){
var ACTIONS=__NATIVE_V2_ACTIONS__;
var PIPELINES=__NATIVE_V2_PIPELINES__;
var BASE=__NATIVE_V2_BASE__;
var PERMISSIONS=__NATIVE_V2_PERMISSIONS__;
var RUNTIME_VERSION=__NATIVE_V2_RUNTIME_VERSION__;
var MAX_HOOK_INPUT_BYTES=__NATIVE_V2_MAX_HOOK_INPUT_BYTES__;
function own(o,k){return Object.prototype.hasOwnProperty.call(o||{},k)}
function isObj(v){return !!v&&typeof v==="object"&&!Array.isArray(v)}
function isNode(v){return !!v&&typeof v.select==="function"&&typeof v.text==="function"}
function isEmpty(v){return v===null||v===undefined||v===""||(Array.isArray(v)&&v.length===0)}
function asArray(v){if(v===null||v===undefined)return [];return Array.isArray(v)?v:[v]}
function log(){var a=["NATIVE_V2"].concat(Array.prototype.slice.call(arguments));try{Log.log.apply(null,a)}catch(e){}}
var WARNED=Object.create(null),WARNING_COUNT=0,MAX_WARNINGS=64;
function warn(stage,message,detail){
 var d=String(detail==null?"":detail),key=String(stage||"parse")+"|"+String(message||"")+"|"+d;
 if(WARNED[key]||WARNING_COUNT>=MAX_WARNINGS)return;WARNED[key]=true;WARNING_COUNT++;
 log("WARNING",String(stage||"parse"),String(message||"Lỗi parsing"),d);
}
function fail(stage,message,detail){var e=new Error("Native Source API 2 ["+String(stage||"runtime")+"]: "+String(message||"Lỗi không xác định")+(detail?" | "+String(detail):""));e.nativeV2Stage=String(stage||"runtime");throw e}
function normalizeUrl(value,base){value=String(value==null?"":value).trim();if(!value)return "";if(value==="NO_NEXT"||value==="NO_PREV")return value;if(/^javascript:/i.test(value))return "";try{return new URL(value,base||BASE||location.href).href}catch(e){return value}}
function pathTokens(path){
 path=String(path||"").replace(/^\$\.?/,"").replace(/\[(?:\"([^\"]+)\"|'([^']+)'|(\d+)|\*)\]/g,function(_,a,b,c){return "."+(a||b||(c!==undefined?c:"*"))});
 return path.split(".").filter(function(x){return x!==""})
}
function pathGet(root,path){
 if(path===null||path===undefined||String(path)===""||String(path)==="$")return root;
 var tokens=pathTokens(path),current=[root];
 for(var ti=0;ti<tokens.length;ti++){
  var token=tokens[ti],next=[];
  for(var i=0;i<current.length;i++){
   var value=current[i];
   if(value===null||value===undefined)continue;
   if(token==="*"){
    if(Array.isArray(value))next=next.concat(value);
    else if(typeof value==="object")Object.keys(value).forEach(function(k){next.push(value[k])});
   }else if(Array.isArray(value)&&/^\d+$/.test(token)){
    var idx=Number(token);if(value[idx]!==undefined)next.push(value[idx]);
   }else if(typeof value==="object"&&value[token]!==undefined)next.push(value[token]);
  }
  current=next;
  if(!current.length)return undefined;
 }
 return current.length===1?current[0]:current;
}
function configValue(key){try{return localConfig.getItem(String(key||""))}catch(e){return ""}}
function resolveToken(ctx,raw){
 raw=String(raw||"").trim();
 var parts=raw.split("|"),name=parts.shift(),def="";
 var qi=name.indexOf("??");if(qi>=0){def=name.slice(qi+2);name=name.slice(0,qi)}
 var value;
 if(name==="input")value=ctx.input;else if(name==="query")value=ctx.query;else if(name==="page")value=ctx.page;else if(name==="url"||name==="current_url")value=ctx.current_url;else if(name==="base_url")value=BASE;
 else if(name.indexOf("vars.")===0)value=pathGet(ctx.vars,name.slice(5));
 else if(name.indexOf("config.")===0)value=configValue(name.slice(7));
 else if(name.indexOf("responses.")===0)value=pathGet(ctx.responses,name.slice(10));
 else if(name.indexOf("args.")===0)value=pathGet(ctx.args||{},name.slice(5));
 else value=pathGet(ctx.vars,name);
 if(isEmpty(value)&&def!=="")value=def;
 for(var i=0;i<parts.length;i++){
  var f=String(parts[i]||"").toLowerCase();
  if(f==="urlencode")value=encodeURIComponent(String(value==null?"":value));
  else if(f==="lower")value=String(value==null?"":value).toLowerCase();
  else if(f==="upper")value=String(value==null?"":value).toUpperCase();
  else if(f==="trim")value=String(value==null?"":value).trim();
  else if(f==="json")value=JSON.stringify(value);
 }
 return value==null?"":value;
}
function isTemplateToken(raw){var head=String(raw||"").trim().split("|")[0],qi=head.indexOf("??"),name=(qi>=0?head.slice(0,qi):head).trim();return /^[A-Za-z_][A-Za-z0-9_.-]*$/.test(name)}
function hasTemplate(value){if(typeof value!=="string")return false;var found=false;String(value).replace(/\{([^{}]+)\}/g,function(all,token){if(isTemplateToken(token))found=true;return all});return found}
function expandString(value,ctx){return String(value==null?"":value).replace(/\{([^{}]+)\}/g,function(all,token){if(!isTemplateToken(token))return all;var v=resolveToken(ctx,token);return typeof v==="string"?v:JSON.stringify(v)})}
function expand(value,ctx){
 if(Array.isArray(value))return value.map(function(x){return expand(x,ctx)});
 if(isObj(value)){var out={};Object.keys(value).forEach(function(k){out[k]=expand(value[k],ctx)});return out}
 return typeof value==="string"?expandString(value,ctx):value;
}
function resolveRef(ctx,ref){
 if(ref===null||ref===undefined||ref==="")return ctx.last;
 if(typeof ref!=="string")return ref;
 var s=String(ref);
 if(s.indexOf("$$")===0)return s.slice(1);
 if(hasTemplate(s))return expandString(s,ctx);
 if(s==="$response"||s==="$last")return ctx.last;
 if(s==="$input")return ctx.input;if(s==="$query")return ctx.query;if(s==="$page")return ctx.page;if(s==="$url"||s==="$current_url")return ctx.current_url;if(s==="$base_url")return BASE;
 if(s==="$args")return ctx.args||{};if(s==="$vars")return ctx.vars||{};if(s==="$responses")return ctx.responses||{};
 if(s.indexOf("$args.")===0)return pathGet(ctx.args||{},s.slice(6));
 if(s.indexOf("$vars.")===0)return pathGet(ctx.vars,s.slice(6));
 if(s.indexOf("$config.")===0)return configValue(s.slice(8));
 if(s.indexOf("$responses.")===0)return pathGet(ctx.responses,s.slice(11));
 if(s[0]==="$"){
  var body=s.slice(1),dot=body.indexOf("."),id=dot>=0?body.slice(0,dot):body,rest=dot>=0?body.slice(dot+1):"";
  var entry=ctx.responses[id];if(entry===undefined)return undefined;
  var data=isObj(entry)&&own(entry,"data")?entry.data:entry;
  return rest?pathGet(data,rest):data;
 }
 if(ctx.responses[s]!==undefined){var e=ctx.responses[s];return isObj(e)&&own(e,"data")?e.data:e}
 return pathGet(ctx.last,s);
}
function nodeValue(node,desc){
 if(!node)return "";
 var mode=String((desc&&desc.mode)||"").toLowerCase(),attr=desc&&desc.attr;
 if(attr)return node.attr(String(attr));
 if(mode==="html")return node.html();
 if(mode==="outer_html"||mode==="outerhtml")return node.outerHtml();
 if(mode==="own_text"||mode==="owntext")return typeof node.ownText==="function"?node.ownText():node.text();
 return node.text();
}
function applyReplace(value,rules){
 var list=Array.isArray(rules)?rules:(rules?[rules]:[]),out=String(value==null?"":value);
 list.forEach(function(rule){if(!rule)return;var pattern=String(rule.pattern==null?"":rule.pattern),replacement=String(rule.replacement==null?"":rule.replacement);if(!pattern)return;try{if(rule.plain)out=out.split(pattern).join(replacement);else out=out.replace(new RegExp(pattern,String(rule.flags||"g")),replacement)}catch(e){warn("parse.replace","Regex thay thế không hợp lệ: "+pattern,e&&e.message?e.message:e)}});
 return out;
}
function postProcess(value,desc,ctx){
 if(Array.isArray(value)){
  var arr=value.map(function(v){return postProcess(v,Object.assign({},desc,{all:false}),ctx)});
  if(desc.compact!==false)arr=arr.filter(function(v){return !isEmpty(v)});
  if(desc.join!==undefined)return arr.join(String(desc.join));
  return arr;
 }
 var out=value;
 if(out===null||out===undefined)out="";
 if(typeof out==="string"){
  if(desc.trim!==false)out=out.trim();
  if(desc.replace)out=applyReplace(out,desc.replace);
  if(desc.regex){try{var m=String(out).match(new RegExp(String(desc.regex),String(desc.flags||"")));out=m?(m[Number(desc.group==null?1:desc.group)]!==undefined?m[Number(desc.group==null?1:desc.group)]:m[0]):""}catch(e){warn("parse.regex","Regex trích xuất không hợp lệ: "+String(desc.regex),e&&e.message?e.message:e);out=""}}
  if(desc.prefix!==undefined){var prefixValue=resolveDynamic(ctx,desc.prefix,out);out=String(prefixValue==null?"":prefixValue)+out}
  if(desc.suffix!==undefined){var suffixValue=resolveDynamic(ctx,desc.suffix,out);out=out+String(suffixValue==null?"":suffixValue)}
  if(desc.absolute)out=normalizeUrl(out,ctx.current_url||BASE);
 }
 if(desc.matches!==undefined){try{out=new RegExp(String(desc.matches),String(desc.flags||"i")).test(String(out==null?"":out))}catch(e){warn("parse.matches","Regex matches không hợp lệ: "+String(desc.matches),e&&e.message?e.message:e);out=false}}
 else if(desc.contains!==undefined)out=String(out==null?"":out).toLowerCase().indexOf(String(resolveDynamic(ctx,desc.contains,out)).toLowerCase())>=0;
 if(desc.map&&isObj(desc.map)){var mk=String(out==null?"":out);if(own(desc.map,mk))out=resolveDynamic(ctx,desc.map[mk],out);else if(desc.map_default!==undefined)out=resolveDynamic(ctx,desc.map_default,out)}
 if(isEmpty(out)&&desc.default!==undefined)out=resolveDynamic(ctx,desc.default,out);
 var asType=String(desc.as||"").toLowerCase();
 if(asType==="number"){var num=Number(out);out=isFinite(num)?num:0}
 else if(asType==="boolean"&&typeof out!=="boolean"){var bv=String(out==null?"":out).toLowerCase();out=!(bv===""||bv==="0"||bv==="false"||bv==="no"||bv==="off"||bv==="null")}
 var parseType=String(desc.parse||"").toLowerCase();
 if(parseType==="html"&&typeof out==="string")try{out=Html.parse(out)}catch(e){fail("parse.html",e.message||e)}
 else if(parseType==="json"&&typeof out==="string")try{out=JSON.parse(out)}catch(e){fail("parse.json",e.message||e)}
 return out;
}
function extract(source,desc,ctx){
 if(desc===null||desc===undefined)return source;
 if(Array.isArray(desc)){
  for(var ai=0;ai<desc.length;ai++){var av=extract(source,desc[ai],ctx);if(!isEmpty(av))return av}return "";
 }
 if(typeof desc==="string"){
  if(desc.indexOf("$$")===0)return desc.slice(1);
  if(desc.charAt(0)==="$" )return resolveRef(ctx,desc);
  if(hasTemplate(desc))return expandString(desc,ctx);
  if(isNode(source)){try{var q=source.select(desc);return q&&q.length?q.first().text():""}catch(e){warn("parse.selector","Selector không hợp lệ: "+String(desc||""),e&&e.message?e.message:e);return ""}}
  return pathGet(source,desc);
 }
 if(!isObj(desc))return desc;
 if(desc.from!==undefined)source=resolveRef(ctx,desc.from);
 if(desc.first){var firstList=asArray(desc.first);for(var fi=0;fi<firstList.length;fi++){var fv=extract(source,firstList[fi],ctx);if(!isEmpty(fv))return postProcess(fv,desc,ctx)}return postProcess("",desc,ctx)}
 var value;
 if(desc.items!==undefined||desc.item!==undefined){
  var nestedRoots=collection(source,desc.items!==undefined?desc.items:desc.item,ctx),nested=[];
  for(var ni=0;ni<nestedRoots.length;ni++)nested.push(mapFields(nestedRoots[ni],desc.fields||{},ctx));
  value=nested;
 }else if(desc.value!==undefined)value=resolveDynamic(ctx,desc.value,source);
 else if(desc.template!==undefined)value=expandString(desc.template,ctx);
 else if(desc.var!==undefined)value=pathGet(ctx.vars,String(desc.var));
 else if(desc.path!==undefined)value=pathGet(source,desc.path);
 else if(desc.self===true||desc.selector==="."){
  value=isNode(source)?nodeValue(source,desc):source;
 }else if(desc.selector!==undefined){
  if(!isNode(source))value="";
  else{
   var selectors=asArray(desc.selector),q=null;
   for(var si=0;si<selectors.length;si++){try{var candidate=source.select(String(selectors[si]||""));if(candidate&&candidate.length){q=candidate;break}}catch(ignoreSelector){warn("parse.selector","Selector không hợp lệ: "+String(selectors[si]||""),ignoreSelector&&ignoreSelector.message?ignoreSelector.message:ignoreSelector)}}
   if(!q)q=[];
   if(desc.remove){asArray(desc.remove).forEach(function(sel){try{q.select(String(sel)).remove()}catch(e){warn("parse.selector.remove","Selector remove không hợp lệ: "+String(sel||""),e&&e.message?e.message:e)}})}
   if(desc.all){value=[];for(var i=0;i<q.length;i++)value.push(nodeValue(q[i],desc))}
   else value=q&&q.length?nodeValue(q.first(),desc):"";
  }
 }else value=source;
 return postProcess(value,desc,ctx);
}
function collection(source,spec,ctx){
 if(spec===null||spec===undefined)return Array.isArray(source)?source:[];
 if(typeof spec==="string"){
  if(isNode(source)){var q=source.select(spec);return Array.prototype.slice.call(q||[])}
  var p=pathGet(source,spec);return Array.isArray(p)?p:(p===undefined||p===null?[]:[p]);
 }
 if(Array.isArray(spec))return spec;
 if(!isObj(spec))return [];
 if(spec.from!==undefined)source=resolveRef(ctx,spec.from);
 if(spec.path!==undefined){var pv=pathGet(source,spec.path);return Array.isArray(pv)?pv:(pv===undefined||pv===null?[]:[pv])}
 if(spec.selector!==undefined&&isNode(source)){var sels=asArray(spec.selector);for(var si=0;si<sels.length;si++){try{var q2=source.select(String(sels[si]||""));if(q2&&q2.length)return Array.prototype.slice.call(q2||[])}catch(ignoreCollectionSelector){warn("parse.collection_selector","Selector collection không hợp lệ: "+String(sels[si]||""),ignoreCollectionSelector&&ignoreCollectionSelector.message?ignoreCollectionSelector.message:ignoreCollectionSelector)}}return []}
 return Array.isArray(source)?source:[];
}
function ensureHeaders(h){var out={};Object.keys(h||{}).forEach(function(k){out[String(k)]=String(h[k]==null?"":h[k])});return out}
function parseHttpResponse(res,type,charset){
 type=String(type||"html").toLowerCase();
 if(type==="json")return res.json();
 if(type==="text")return res.text(charset||"utf-8");
 if(type==="base64"||type==="bytes")return res.base64();
 if(type==="response"||type==="raw")return res;
 if(type==="auto"){
  var ct=String(res.header("content-type")||"").toLowerCase();
  if(ct.indexOf("json")>=0){try{return res.json()}catch(e){warn("parse.response.auto_json","Content-Type là JSON nhưng parse thất bại; fallback sang HTML",e&&e.message?e.message:e)}}
  return res.html(charset||"utf-8");
 }
 return res.html(charset||"utf-8");
}
function replayMode(value){
 var mode=String(value||"auto").toLowerCase().replace(/[- ]/g,"_");
 if(mode!=="fresh"&&mode!=="keyed")mode="auto";
 return mode;
}
function replayKey(spec,fallback){
 var key=String((spec&&spec.replay_key)||"").trim();
 return key||String(fallback||"");
}
function doRequest(rawSpec,ctx,index){
 var spec=resolveDeep(ctx,rawSpec||{},ctx.last),id=String(spec.id||("request"+index)),transport=String(spec.transport||"http").toLowerCase();
 var url=normalizeUrl(spec.url||ctx.current_url||BASE,ctx.current_url||BASE);if(!url)fail("request."+id,"URL request rỗng");
 log("REQUEST",id,transport,String(spec.method||"GET").toUpperCase(),url);
 var data,meta={url:url,status:0,headers:{},captured_url:""};
 if(transport==="browser"){
  if(!PERMISSIONS||PERMISSIONS.browser!==true)fail("request."+id,"Nguồn chưa được cấp quyền Browser");
  var browserReplayMode=replayMode(spec.replay),browserReplayKey=replayKey(spec,"request."+id);
  var browser=Engine.newBrowser({replayMode:browserReplayMode,replayKey:browserReplayKey});
  try{
   if(browser.setReplayPolicy)browser.setReplayPolicy(browserReplayMode,browserReplayKey);
   if((spec.dialog_policy||spec.alert_policy)&&browser.setDialogPolicy)browser.setDialogPolicy(spec.dialog_policy||spec.alert_policy);
   if(spec.user_agent&&browser.setUserAgent)browser.setUserAgent(String(spec.user_agent));
   if(spec.block)browser.block(asArray(spec.block));
   var doc=browser.launch(url,Number(spec.timeout||30000));
   if(browser.currentUrl){var launchedUrl=String(browser.currentUrl()||"");if(launchedUrl)meta.url=launchedUrl}
   if(spec.wait_url){meta.captured_url=String(browser.waitUrl(asArray(spec.wait_url),Number(spec.wait_timeout||spec.timeout||15000))||"")}
   if(spec.wait_request){
    if(!PERMISSIONS||PERMISSIONS.network_capture!==true)fail("request."+id,"Nguồn chưa được cấp quyền network_capture");
    var waitedRequest=browser.waitRequest(asArray(spec.wait_request),Number(spec.wait_timeout||spec.timeout||15000),{method:String(spec.wait_method||"")});
    if(!waitedRequest&&!spec.allow_wait_timeout)fail("request."+id,"Browser không bắt được request mạng",String(spec.wait_request));
    if(waitedRequest){meta.captured_request=waitedRequest;meta.captured_url=String(waitedRequest.url||meta.captured_url||"")}
   }
   if(spec.wait_selector){
    var selectorResult=browser.waitSelector(asArray(spec.wait_selector),Number(spec.wait_timeout||spec.timeout||15000));
    if(!selectorResult&&!spec.allow_wait_timeout)fail("request."+id,"Browser không tìm thấy wait_selector",String(spec.wait_selector));
   }
   if(spec.wait_ms)doc=browser.html(Number(spec.wait_ms)||0);else if(spec.wait_selector)doc=browser.html(0);
   if(spec.evaluate!==undefined){
    var browserType=String(spec.response||spec.type||"text").toLowerCase();
    if(browserType==="json"||browserType==="object"){
     data=browser.callJson(String(spec.evaluate),Number(spec.evaluate_timeout||5000));
     if(browserType==="json"&&typeof data==="string"){try{data=JSON.parse(data)}catch(e){fail("request."+id,"Không parse được JSON từ Browser",e.message||e)}}
    }else{
     data=browser.callJs(String(spec.evaluate),Number(spec.evaluate_timeout||5000));
     if(browserType==="html"&&typeof data==="string")data=Html.parse(data);
     else if(browserType==="text")data=String(data==null?"":data);
    }
   }else data=doc;
   meta.url=meta.captured_url||meta.url||url;meta.status=200;
  }finally{try{browser.close()}catch(ignore){}}
 }else{
  var cookieMode=String(spec.cookie_mode||"shared").toLowerCase().replace(/[- ]/g,"_");
  if(cookieMode==="readonly")cookieMode="read_only";if(cookieMode==="writeonly")cookieMode="write_only";
  var options={method:String(spec.method||"GET").toUpperCase(),headers:ensureHeaders(spec.headers||{}),timeout:Number(spec.timeout||30000),cookie_mode:cookieMode,__cookieMode:cookieMode,__replayMode:replayMode(spec.replay),__replayKey:replayKey(spec,"request."+id)};
  if(spec.query||spec.queries)options.queries=spec.query||spec.queries;
  if(spec.body!==undefined){options.body=typeof spec.body==="string"?spec.body:JSON.stringify(spec.body)}
  var retries=Math.max(0,Number(spec.retries||0)),attempt=0,res;
  while(true){
   attempt++;res=fetch(url,options);
   if(res.ok||spec.allow_http_error||attempt>retries)break;
   log("RETRY",id,"attempt="+attempt,"status="+res.status);
  }
  if(!res.ok&&!spec.allow_http_error)fail("request."+id,"HTTP "+res.status+" "+res.statusText,url);
  data=parseHttpResponse(res,spec.response||spec.type||"html",spec.charset);
  meta={url:String(res.url||url),status:Number(res.status||0),statusText:String(res.statusText||""),headers:res.headers||{},requestHeaders:(res.request&&res.request.headers)||{}};
 }
 ctx.responses[id]={data:data,url:meta.url,status:meta.status,statusText:meta.statusText||"",headers:meta.headers||{},requestHeaders:meta.requestHeaders||{},captured_url:meta.captured_url||"",captured_request:meta.captured_request||null};
 ctx.last=data;ctx.last_id=id;ctx.current_url=String(meta.url||url);ctx.vars.current_url=ctx.current_url;
 log("RESPONSE",id,"status="+String(meta.status||0),"url="+ctx.current_url);
 return data;
}
function truthy(v){if(v===false||v===null||v===undefined||v===""||v===0)return false;if(Array.isArray(v)&&!v.length)return false;return true}
function isControlFlowError(e){return !!(e&&(e.__vbookNeedHttp||e.__vbookNeedBrowser))}
function resolveDynamic(ctx,value,source){
 if(value===null||value===undefined)return value;
 if(typeof value==="string"){
  if(value.indexOf("$$")===0)return value.slice(1);
  if(value.charAt(0)==="$")return resolveRef(ctx,value);
  if(hasTemplate(value))return expandString(value,ctx);
  return value;
 }
 if(isObj(value)){
  if(value.from!==undefined||value.path!==undefined||value.selector!==undefined||value.value!==undefined||value.template!==undefined||value.var!==undefined||value.first!==undefined||value.self===true)return extract(source===undefined?ctx.last:source,value,ctx);
  return resolveDeep(ctx,value,source);
 }
 return value;
}
function comparable(v){if(v&&typeof v==="object")try{return JSON.stringify(v)}catch(e){}return String(v==null?"":v)}
function resolveDeep(ctx,value,source){if(Array.isArray(value))return value.map(function(v){return resolveDeep(ctx,v,source)});if(isObj(value)){var out={};Object.keys(value).forEach(function(k){out[k]=resolveDeep(ctx,value[k],source)});return out}return resolveDynamic(ctx,value,source)}
function evalCondition(condition,ctx,source){
 source=source===undefined?ctx.last:source;
 if(condition===null||condition===undefined)return false;
 if(typeof condition==="boolean")return condition;
 if(typeof condition==="number")return condition!==0;
 if(typeof condition==="string")return truthy(resolveDynamic(ctx,condition,source));
 if(!isObj(condition))return truthy(condition);
 if(Array.isArray(condition.all)){for(var ai=0;ai<condition.all.length;ai++)if(!evalCondition(condition.all[ai],ctx,source))return false;return true}
 if(Array.isArray(condition.any)){for(var oi=0;oi<condition.any.length;oi++)if(evalCondition(condition.any[oi],ctx,source))return true;return false}
 if(condition.not!==undefined)return !evalCondition(condition.not,ctx,source);
 var leftSpec=condition.left!==undefined?condition.left:(condition.value!==undefined?condition.value:(condition.from!==undefined?{from:condition.from,path:condition.path}:condition));
 var left=resolveDynamic(ctx,leftSpec,source),right=resolveDynamic(ctx,condition.right,source),op=String(condition.op||"truthy").toLowerCase().replace(/[- ]/g,"_");
 if(op==="truthy")return truthy(left);if(op==="falsy")return !truthy(left);if(op==="empty")return isEmpty(left);if(op==="exists")return left!==undefined&&left!==null;if(op==="not_empty")return !isEmpty(left);
 if(op==="equals"||op==="equal"||op==="eq"||op==="==")return comparable(left)===comparable(right);
 if(op==="not_equals"||op==="not_equal"||op==="neq"||op==="!=")return comparable(left)!==comparable(right);
 var ls=String(left==null?"":left),rs=String(right==null?"":right);if(condition.ignore_case===true){ls=ls.toLowerCase();rs=rs.toLowerCase()}
 if(op==="contains")return ls.indexOf(rs)>=0;if(op==="not_contains")return ls.indexOf(rs)<0;if(op==="starts_with")return ls.indexOf(rs)===0;if(op==="ends_with")return rs===""||ls.slice(-rs.length)===rs;
 if(op==="matches"||op==="regex")try{return new RegExp(rs,String(condition.flags||"")).test(ls)}catch(e){warn("parse.condition_regex","Regex điều kiện không hợp lệ: "+rs,e&&e.message?e.message:e);return false}
 if(op==="in"){var arr=Array.isArray(right)?right:[right];for(var ii=0;ii<arr.length;ii++)if(comparable(left)===comparable(arr[ii]))return true;return false}
 var ln=Number(left),rn=Number(right);if(op==="greater"||op==="gt")return ln>rn;if(op==="greater_equal"||op==="gte")return ln>=rn;if(op==="less"||op==="lt")return ln<rn;if(op==="less_equal"||op==="lte")return ln<=rn;
 return truthy(left);
}
function flattenDeep(input,deep){var out=[];asArray(input).forEach(function(v){if(Array.isArray(v)&&(deep!==false))out=out.concat(flattenDeep(v,deep));else out.push(v)});return out}
function htmlDecode(value){try{var d=(new DOMParser()).parseFromString("<textarea>"+String(value==null?"":value)+"</textarea>","text/html");return d.querySelector("textarea").value}catch(e){warn("parse.html_decode","Không giải mã được HTML entity",e&&e.message?e.message:e);return String(value==null?"":value)}}
function b64EncodeText(value){try{return btoa(unescape(encodeURIComponent(String(value==null?"":value))))}catch(e){return btoa(String(value==null?"":value))}}
function b64DecodeText(value){try{return decodeURIComponent(escape(atob(String(value||""))))}catch(e){try{return atob(String(value||""))}catch(ignore){return ""}}}
function utf8ByteLength(value){value=String(value==null?"":value);try{return unescape(encodeURIComponent(value)).length}catch(e){return value.length}}
function applyTransformOperation(value,rawOp,ctx,stage){
 var opSpec=typeof rawOp==="string"?{op:rawOp}:(rawOp||{}),op=String(opSpec.op||opSpec.operation||"").toLowerCase().replace(/[- ]/g,"_");
 if(op==="trim")return String(value==null?"":value).trim();if(op==="lower"||op==="lowercase")return String(value==null?"":value).toLowerCase();if(op==="upper"||op==="uppercase")return String(value==null?"":value).toUpperCase();
 if(op==="replace"){var pattern=String(resolveDynamic(ctx,opSpec.pattern,value)||""),rep=String(resolveDynamic(ctx,opSpec.replacement,value)||"");return opSpec.plain===true?String(value==null?"":value).split(pattern).join(rep):String(value==null?"":value).replace(new RegExp(pattern,String(opSpec.flags||"g")),rep)}
 if(op==="regex_replace"){try{return String(value==null?"":value).replace(new RegExp(String(opSpec.pattern||""),String(opSpec.flags||"g")),String(resolveDynamic(ctx,opSpec.replacement,value)||""))}catch(e){fail(stage,"regex_replace không hợp lệ",e.message||e)}}
 if(op==="split")return String(value==null?"":value).split(String(opSpec.separator!==undefined?opSpec.separator:(opSpec.delimiter||",")));
 if(op==="join")return asArray(value).join(String(opSpec.separator!==undefined?opSpec.separator:(opSpec.delimiter||"")));
 if(op==="substring"||op==="slice_text"){var st=Number(opSpec.start||0)||0,en=opSpec.stop!==undefined?Number(opSpec.stop):(opSpec.end!==undefined?Number(opSpec.end):undefined);if(opSpec.length!==undefined)en=st+(Number(opSpec.length)||0);return String(value==null?"":value).slice(st,en)}
 if(op==="url_encode")return encodeURIComponent(String(value==null?"":value));if(op==="url_decode")try{return decodeURIComponent(String(value==null?"":value))}catch(e){warn(stage+".url_decode","URL decode thất bại",e&&e.message?e.message:e);return String(value==null?"":value)}
 if(op==="html_decode")return htmlDecode(value);if(op==="base64_encode")return b64EncodeText(value);if(op==="base64_decode")return b64DecodeText(value);
 if(op==="json_encode")return JSON.stringify(value);if(op==="json_decode")try{return typeof value==="string"?JSON.parse(value):value}catch(e){fail(stage,"json_decode thất bại",e.message||e)}
 if(op==="flatten")return flattenDeep(value,opSpec.deep!==false);if(op==="reverse")return asArray(value).slice().reverse();if(op==="first")return asArray(value)[0];if(op==="last"){var la=asArray(value);return la.length?la[la.length-1]:undefined}
 if(op==="slice"){var arr=asArray(value),start=Number(opSpec.start||0)||0,stop=opSpec.stop!==undefined?Number(opSpec.stop):(opSpec.end!==undefined?Number(opSpec.end):undefined);if(opSpec.length!==undefined)stop=start+(Number(opSpec.length)||0);return arr.slice(start,stop)}
 if(op==="map"){
  var src=asArray(value),mapped=[],asName=String(opSpec.as||"item"),indexName=String(opSpec.index_as||"index"),oldAs=ctx.vars[asName],oldIndex=ctx.vars[indexName];
  try{for(var mi=0;mi<src.length;mi++){ctx.vars[asName]=src[mi];ctx.vars[indexName]=mi+1;if(opSpec.fields)mapped.push(mapFields(src[mi],opSpec.fields,ctx));else if(opSpec.value!==undefined)mapped.push(extract(src[mi],opSpec.value,ctx));else mapped.push(src[mi])}}
  finally{ctx.vars[asName]=oldAs;ctx.vars[indexName]=oldIndex}
  return mapped;
 }
 if(op==="filter"){
  var fs=asArray(value),filtered=[],fa=String(opSpec.as||"item"),fi=String(opSpec.index_as||"index"),oldFa=ctx.vars[fa],oldFi=ctx.vars[fi];
  try{for(var fidx=0;fidx<fs.length;fidx++){ctx.vars[fa]=fs[fidx];ctx.vars[fi]=fidx+1;if(evalCondition(opSpec.condition,ctx,fs[fidx]))filtered.push(fs[fidx])}}
  finally{ctx.vars[fa]=oldFa;ctx.vars[fi]=oldFi}
  return filtered;
 }
 if(op==="unique"){
  var us=asArray(value),seen=Object.create(null),unique=[];for(var ui=0;ui<us.length;ui++){var key=opSpec.by!==undefined?extract(us[ui],opSpec.by,ctx):us[ui],sk=comparable(key);if(!seen[sk]){seen[sk]=true;unique.push(us[ui])}}return unique;
 }
 if(op==="sort"){
  var sorted=asArray(value).slice(),desc=opSpec.desc===true||opSpec.descending===true;sorted.sort(function(a,b){var av=opSpec.by!==undefined?extract(a,opSpec.by,ctx):a,bv=opSpec.by!==undefined?extract(b,opSpec.by,ctx):b;var an=Number(av),bn=Number(bv),cmp=(isFinite(an)&&isFinite(bn))?(an-bn):String(av==null?"":av).localeCompare(String(bv==null?"":bv));return desc?-cmp:cmp});return sorted;
 }
 fail(stage,"Transform operation không được hỗ trợ: "+op);
}
function doTransform(rawSpec,ctx,stage){
 var spec=rawSpec||{},source=spec.from!==undefined?resolveRef(ctx,spec.from):ctx.last,value=spec.input!==undefined?resolveDynamic(ctx,spec.input,source):source,ops=spec.operations!==undefined?spec.operations:(spec.ops!==undefined?spec.ops:(spec.op||spec.operation?[spec]:[]));
 ops=Array.isArray(ops)?ops:[ops];for(var i=0;i<ops.length;i++)value=applyTransformOperation(value,ops[i],ctx,stage+".op"+(i+1));
 if(spec.into)ctx.vars[String(spec.into)]=value;ctx.last=value;return value;
}
function toSerializable(value,depth){
 depth=depth||0;if(depth>8)return null;if(value===null||value===undefined)return null;if(isNode(value)){try{return value.outerHtml()}catch(e){return value.text()}}
 if(Array.isArray(value))return value.map(function(v){return toSerializable(v,depth+1)});if(typeof value==="object"){var out={};Object.keys(value).forEach(function(k){if(typeof value[k]!=="function")out[k]=toSerializable(value[k],depth+1)});return out}if(typeof value==="function")return null;return value;
}
function hookContextVars(vars,contextSpec){
 var out={},src=(vars&&typeof vars==="object")?vars:{},used=2;
 if(contextSpec===false||contextSpec==="none")return out;
 var include=null,budget=2048,perValue=1024;
 if(Array.isArray(contextSpec))include=contextSpec;
 else if(contextSpec&&typeof contextSpec==="object"){
  if(Array.isArray(contextSpec.include))include=contextSpec.include;
  if(contextSpec.max_bytes!==undefined)budget=Math.max(256,Math.min(Number(contextSpec.max_bytes)||2048,8192));
  if(contextSpec.max_value_bytes!==undefined)perValue=Math.max(128,Math.min(Number(contextSpec.max_value_bytes)||1024,4096));
 }
 var keys=include||Object.keys(src);
 for(var i=0;i<keys.length;i++){
  var k=String(keys[i]||"");if(!k||!Object.prototype.hasOwnProperty.call(src,k))continue;
  var v=src[k];
  if(!include&&typeof v==="string"&&(v.length>1024||(/<[^>]+>/.test(v)&&v.length>256)))continue;
  var sv=toSerializable(v),encoded="";try{encoded=JSON.stringify(sv)}catch(e){continue}
  var encodedBytes=utf8ByteLength(encoded),cost=utf8ByteLength(JSON.stringify(k))+encodedBytes+2;
  if(encodedBytes<=perValue&&used+cost<=budget){out[k]=sv;used+=cost}
 }
 return out;
}
function doHook(rawSpec,ctx,stage){
 var spec=typeof rawSpec==="string"?{name:rawSpec}:(rawSpec||{}),nameValue=resolveDynamic(ctx,spec.name,ctx.last),name=String(nameValue==null?"":nameValue);if(!name)fail(stage,"hook.name bị thiếu");
 var value=spec.input!==undefined?resolveDynamic(ctx,spec.input,ctx.last):(spec.value!==undefined?resolveDynamic(ctx,spec.value,ctx.last):ctx.last),args=spec.args!==undefined?resolveDeep(ctx,spec.args,ctx.last):{};
 var payload={value:toSerializable(value),args:toSerializable(args),context:{input:ctx.input,query:ctx.query,page:ctx.page,current_url:ctx.current_url,vars:hookContextVars(ctx.vars,spec.context!==undefined?spec.context:spec.context_vars)}},payloadText=JSON.stringify(payload);
 if(utf8ByteLength(payloadText)>8192){payload.context.vars={};payloadText=JSON.stringify(payload)}
 if(utf8ByteLength(payloadText)>MAX_HOOK_INPUT_BYTES)fail(stage,"Dữ liệu gửi vào Lua hook vượt giới hạn "+String(MAX_HOOK_INPUT_BYTES)+" byte. Hãy truyền dữ liệu cần thiết qua hook.input/hook.args thay vì toàn bộ ngữ cảnh.");
 var raw=__bridge("native_hook",{name:name,input:payloadText}),out;try{out=JSON.parse(String(raw==null?"null":raw))}catch(e){fail(stage,"Lua hook trả JSON không hợp lệ",e.message||e)}
 if(spec.into)ctx.vars[String(spec.into)]=out;ctx.last=out;log("HOOK",name);return out;
}
function browserRecord(ctx,name,create,spec,stage){
 name=String(name||"main");ctx.browsers=ctx.browsers||{};var record=ctx.browsers[name];
 if(!record&&create){
  if(!PERMISSIONS||PERMISSIONS.browser!==true)fail(stage,"Nguồn chưa được cấp permissions.browser");
  var initialReplayMode=replayMode(spec&&spec.replay),initialReplayKey=replayKey(spec,stage);
  var browser=Engine.newBrowser({replayMode:initialReplayMode,replayKey:initialReplayKey});record={browser:browser,name:name,closed:false};ctx.browsers[name]=record;
  if(spec&&spec.user_agent&&browser.setUserAgent)browser.setUserAgent(String(expandString(spec.user_agent,ctx)));
  if(spec&&spec.block)browser.block(asArray(expand(spec.block,ctx)));
  if(spec&&(spec.dialog_policy||spec.alert_policy)&&browser.setDialogPolicy)browser.setDialogPolicy(resolveDeep(ctx,spec.dialog_policy||spec.alert_policy,ctx.last));
 }
 if(!record||record.closed)fail(stage,"Browser session không tồn tại hoặc đã đóng: "+name);
 return record;
}
function parseBrowserValue(value,type,stage){
 type=String(type||"value").toLowerCase();
 if((type==="json"||type==="object")&&typeof value==="string")try{return JSON.parse(value)}catch(e){fail(stage,"Không parse được JSON từ Browser",e.message||e)}
 if(type==="html"&&typeof value==="string")try{return Html.parse(value)}catch(e){fail(stage,"Không parse được HTML từ Browser",e.message||e)}
 if(type==="text")return String(value==null?"":value);
 return value;
}
function capturedHeaders(record){
 var out={},headers=(record&&record.headers)||{};Object.keys(headers).forEach(function(k){var lower=String(k).toLowerCase();if(lower!=="cookie"&&lower!=="host"&&lower!=="content-length"&&lower!=="connection")out[String(k)]=String(headers[k]==null?"":headers[k])});return out;
}
function doBrowser(rawSpec,ctx,stage){
 var spec=resolveDeep(ctx,rawSpec||{},ctx.last),op=String(spec.op||spec.operation||"").toLowerCase().replace(/[- ]/g,"_"),name=String(spec.session||spec.name||"main");
 if(!op){if(spec.url)op="launch";else if(spec.wait_selector)op="wait_selector";else if(spec.wait_request)op="wait_request";else if(spec.evaluate!==undefined||spec.script!==undefined)op="evaluate";else op="open"}
 if(op==="open"||op==="create"){
  var opened=browserRecord(ctx,name,true,spec,stage);ctx.last={session:name,opened:true};if(spec.into)ctx.vars[String(spec.into)]=ctx.last;log("BROWSER_OPEN",name);return ctx.last;
 }
 var record=browserRecord(ctx,name,true,spec,stage),browser=record.browser,value=null;
 var operationReplayMode=replayMode(spec.replay),operationReplayKey=replayKey(spec,stage);
 if(browser.setReplayPolicy)browser.setReplayPolicy(operationReplayMode,operationReplayKey);
 if((spec.dialog_policy||spec.alert_policy)&&browser.setDialogPolicy)browser.setDialogPolicy(spec.dialog_policy||spec.alert_policy);
 if(op==="launch"||op==="open_url"||op==="launch_async"){
  var target=normalizeUrl(spec.url||ctx.current_url||BASE,ctx.current_url||BASE);if(!target)fail(stage,"browser.launch thiếu url");
  if(op==="launch_async"){
   value=browser.launchAsync(target);ctx.current_url=target;ctx.vars.current_url=target;
  }else{
   value=browser.launch(target,Number(spec.timeout||30000));var finalUrl=browser.currentUrl?String(browser.currentUrl()||target):target;ctx.current_url=finalUrl||target;ctx.vars.current_url=ctx.current_url;
  }
  if(spec.wait_url){var foundUrl=browser.waitUrl(asArray(spec.wait_url),Number(spec.wait_timeout||spec.timeout||15000));if(foundUrl){ctx.current_url=String(foundUrl);ctx.vars.current_url=ctx.current_url}}
  if(spec.wait_selector){var foundSelector=browser.waitSelector(asArray(spec.wait_selector),Number(spec.wait_timeout||spec.timeout||15000));if(!foundSelector&&!spec.allow_wait_timeout)fail(stage,"Browser không tìm thấy selector",String(spec.wait_selector));if(foundSelector)ctx.vars.browser_wait=foundSelector}
  if(spec.wait_request){if(!PERMISSIONS||PERMISSIONS.network_capture!==true)fail(stage,"Nguồn chưa được cấp permissions.network_capture");var foundRequest=browser.waitRequest(asArray(spec.wait_request),Number(spec.wait_timeout||spec.timeout||15000),{method:String(spec.method||spec.wait_method||""),mainFrame:spec.main_frame===true});if(!foundRequest&&!spec.allow_wait_timeout)fail(stage,"Browser không bắt được request",String(spec.wait_request));if(foundRequest){ctx.vars.browser_request=foundRequest;if(spec.use_captured_url===true){ctx.current_url=String(foundRequest.url||ctx.current_url);ctx.vars.current_url=ctx.current_url}}}
  if(spec.snapshot===true||spec.wait_ms)value=browser.html(Number(spec.wait_ms||0));
 }else if(op==="wait_selector"){
  var selectors=spec.selectors||spec.selector||spec.wait_selector;if(!selectors)fail(stage,"wait_selector cần selectors");value=browser.waitSelector(asArray(selectors),Number(spec.timeout||spec.wait_timeout||15000));if(!value&&!spec.allow_timeout&&!spec.allow_wait_timeout)fail(stage,"Hết thời gian chờ selector",String(selectors));
 }else if(op==="wait_url"){
  var patterns=spec.patterns||spec.urls||spec.wait_url;if(!patterns)fail(stage,"wait_url cần patterns");value=browser.waitUrl(asArray(patterns),Number(spec.timeout||spec.wait_timeout||15000));if(!value&&!spec.allow_timeout&&!spec.allow_wait_timeout)fail(stage,"Hết thời gian chờ URL",String(patterns));
 }else if(op==="wait_request"){
  if(!PERMISSIONS||PERMISSIONS.network_capture!==true)fail(stage,"Nguồn chưa được cấp permissions.network_capture");var requestPatterns=spec.patterns||spec.urls||spec.wait_request;if(!requestPatterns)fail(stage,"wait_request cần patterns");value=browser.waitRequest(asArray(requestPatterns),Number(spec.timeout||spec.wait_timeout||15000),{method:String(spec.method||""),mainFrame:spec.main_frame===true});if(!value&&!spec.allow_timeout&&!spec.allow_wait_timeout)fail(stage,"Hết thời gian chờ request mạng",String(requestPatterns));
 }else if(op==="capture"||op==="requests"){
  if(!PERMISSIONS||PERMISSIONS.network_capture!==true)fail(stage,"Nguồn chưa được cấp permissions.network_capture");value=browser.requests({patterns:asArray(spec.patterns||spec.urls||spec.match||[]),method:String(spec.method||""),mainFrame:spec.main_frame===true,limit:Number(spec.limit||100)});
  if(spec.latest===true)value=value&&value.length?value[value.length-1]:null;
  if(spec.fetch){
   var capture=value;if(Array.isArray(capture))capture=capture.length?capture[capture.length-1]:null;if(!capture||!capture.url)fail(stage,"Không có request đã capture để HTTP handoff");
   var fetchSpec=isObj(spec.fetch)?spec.fetch:{},capturedMethod=String(capture.method||"GET").toUpperCase(),method=String(fetchSpec.method||((capturedMethod==="GET"||capturedMethod==="HEAD")?capturedMethod:"GET")).toUpperCase();
   if(capturedMethod!=="GET"&&capturedMethod!=="HEAD"&&!fetchSpec.method)fail(stage,"Không thể tự replay request "+capturedMethod+" vì WebView không cung cấp request body; hãy khai báo fetch.method/body thủ công");
   // Ép CookieManager flush/read trước khi chuyển về HTTP; HTTP host dùng cùng cookie jar này.
   try{browser.cookie(String(capture.url))}catch(ignoreCookieHandoff){}
   var headers=capturedHeaders(capture);Object.keys(fetchSpec.headers||{}).forEach(function(k){headers[k]=fetchSpec.headers[k]});
   value=doRequest({id:fetchSpec.id||("browser_capture_"+ctx.operation_count),url:String(capture.url),method:method,headers:headers,body:fetchSpec.body,response:fetchSpec.response||fetchSpec.type||"auto",charset:fetchSpec.charset,timeout:fetchSpec.timeout||30000,retries:fetchSpec.retries||0,allow_http_error:fetchSpec.allow_http_error===true,cookie_mode:fetchSpec.cookie_mode||"shared",replay:spec.replay,replay_key:spec.replay_key},ctx,ctx.operation_count);
  }
 }else if(op==="evaluate"||op==="js"||op==="call_js"||op==="evaluate_json"||op==="evaluate_object"||op==="call_js_json"||op==="call_js_object"||op==="object"){
  var script=spec.script!==undefined?spec.script:spec.evaluate;if(script===undefined)fail(stage,"browser.evaluate thiếu script");
  var requestedType=String(spec.response||spec.type||"value").toLowerCase();
  var jsonOperation=(op==="evaluate_json"||op==="call_js_json"||requestedType==="json");
  var objectOperation=(op==="evaluate_object"||op==="call_js_object"||op==="object"||requestedType==="object");
  if(jsonOperation||objectOperation){
   value=browser.callJson(String(script),Number(spec.timeout||spec.evaluate_timeout||5000));
   if(jsonOperation&&typeof value==="string"){try{value=JSON.parse(value)}catch(e){fail(stage,"Không parse được JSON từ Browser",e.message||e)}}
  }else{value=browser.callJs(String(script),Number(spec.timeout||spec.evaluate_timeout||5000));value=parseBrowserValue(value,requestedType,stage)}
 }else if(op==="evaluate_async"||op==="js_async"||op==="call_js_async"){
  var asyncScript=spec.script!==undefined?spec.script:spec.evaluate;if(asyncScript===undefined)fail(stage,"browser.evaluate_async thiếu script");value=browser.callJsAsync(String(asyncScript));
 }else if(op==="tap"||op==="tap_selector"||op==="click"||op==="click_selector"){
  var tapSelector=spec.selector||(spec.selectors&&asArray(spec.selectors)[0]);if(!tapSelector)fail(stage,"browser.tap_selector thiếu selector");value=browser.tapSelector(String(tapSelector),Number(spec.timeout||5000));
 }else if(op==="set_dialog_policy"||op==="dialog_policy"||op==="alert_policy"||op==="js_dialog_policy"){
  browser.setDialogPolicy(spec.policy||spec.dialog_policy||spec.alert_policy||{default_action:spec.default_action||"passthrough",default_value:spec.default_value||"",rules:spec.rules||[]});value=true;
 }else if(op==="dialogs"||op==="dialog_history"){
  value=browser.dialogs(Number(spec.limit||50));
 }else if(op==="last_dialog"||op==="dialog_last"){
  value=browser.lastDialog();
 }else if(op==="wait_dialog"||op==="dialog_wait"){
  value=browser.waitDialog({type:String(spec.dialog_type||spec.type||"any"),match:String(spec.match||""),match_mode:String(spec.match_mode||"contains"),after_id:Number(spec.after_id||0),timeout:Number(spec.timeout||15000)});
  if(!value&&!spec.allow_timeout&&!spec.allow_wait_timeout)fail(stage,"Hết thời gian chờ JavaScript dialog",String(spec.match||spec.dialog_type||"any"));
 }else if(op==="cookie_snapshot"||op==="session_snapshot"){
  var snapUrl=normalizeUrl(spec.url||ctx.current_url||BASE,ctx.current_url||BASE);value=browser.cookieSnapshot(snapUrl);ctx.vars.browser_cookie=value&&value.cookie?String(value.cookie):"";
 }else if(op==="sync_session"||op==="session_sync"||op==="sync_cookies"){
  var syncUrl=normalizeUrl(spec.url||ctx.current_url||BASE,ctx.current_url||BASE);value=browser.syncSession(syncUrl,String(spec.direction||"both"),{cookie:spec.cookie,cookies:spec.cookies,direction:spec.direction});ctx.vars.browser_cookie=value&&value.cookie?String(value.cookie):"";
 }else if(op==="set_cookies"||op==="import_cookies"){
  var setCookieUrl=normalizeUrl(spec.url||ctx.current_url||BASE,ctx.current_url||BASE);value=browser.setCookies(spec.cookies!==undefined?spec.cookies:spec.cookie,setCookieUrl);ctx.vars.browser_cookie=value&&value.cookie?String(value.cookie):"";
 }else if(op==="clear_cookies"||op==="clear_cookie"||op==="reset_cookies"){
  var clearUrl=normalizeUrl(spec.url||ctx.current_url||BASE,ctx.current_url||BASE);value=browser.clearCookies(clearUrl,asArray(spec.names||spec.cookies||[]));ctx.vars.browser_cookie=value;
 }else if(op==="html"||op==="snapshot"){
  value=browser.html(Number(spec.wait_ms||spec.wait||0));
 }else if(op==="urls"){
  if(!PERMISSIONS||PERMISSIONS.network_capture!==true)fail(stage,"Nguồn chưa được cấp permissions.network_capture");value=browser.requests({patterns:[],limit:Number(spec.limit||100)}).map(function(x){return x.url});
 }else if(op==="cookies"||op==="cookie"||op==="handoff"){
  var cookieUrl=normalizeUrl(spec.url||ctx.current_url||BASE,ctx.current_url||BASE);value=browser.cookie(cookieUrl);ctx.vars.browser_cookie=value;
  // HTTP host dùng cùng Android CookieManager, nên request HTTP tiếp theo tự nhận cookie này.
 }else if(op==="block"){
  browser.block(asArray(spec.urls||spec.patterns||spec.block||[]));value=true;
 }else if(op==="close"){
  value=browser.close();record.closed=true;
 }else fail(stage,"Browser operation không được hỗ trợ: "+op);
 if(spec.into)ctx.vars[String(spec.into)]=value;ctx.last=value;log("BROWSER",op,name);return value;
}
function closeManagedBrowsers(ctx){
 var saved=ctx.last,browsers=ctx.browsers||{},firstError=null;
 Object.keys(browsers).forEach(function(name){var record=browsers[name];if(record&&!record.closed){try{record.browser.close();record.closed=true}catch(e){if(isControlFlowError(e))throw e;log("BROWSER_CLOSE_ERROR",name,String(e&&e.message?e.message:e));if(!firstError)firstError=e}}});
 ctx.last=saved;if(firstError)log("BROWSER_CLEANUP_PARTIAL",String(firstError&&firstError.message?firstError.message:firstError));
}
function handleErrorPolicy(policy,error,ctx,stage){
 if(!policy)return false;if(isControlFlowError(error))return false;var mode=typeof policy==="string"?String(policy).toLowerCase():String(policy.mode||policy.action||"").toLowerCase(),obj=typeof policy==="string"?{}:policy;
 ctx.vars.error={message:String(error&&error.message?error.message:error),stage:String(stage||""),name:String(error&&error.name?error.name:"Error")};log("ERROR_POLICY",stage,mode||"fallback",ctx.vars.error.message);
 if(obj.set)Object.keys(obj.set).forEach(function(k){ctx.vars[k]=resolveDynamic(ctx,obj.set[k],ctx.vars.error)});
 var fallback=obj.fallback||obj.steps;if(fallback){runStepList(fallback,ctx,stage+".fallback");if(obj.into)ctx.vars[String(obj.into)]=ctx.last;return true}
 if(obj.into){ctx.vars[String(obj.into)]=obj.value!==undefined?resolveDynamic(ctx,obj.value,ctx.vars.error):ctx.vars.error;ctx.last=ctx.vars[String(obj.into)]}
 return mode==="continue"||mode==="fallback";
}
function doCallPipeline(rawSpec,ctx,stage){
 var spec=typeof rawSpec==="string"?{name:rawSpec}:(rawSpec||{}),nameValue=resolveDynamic(ctx,spec.name!==undefined?spec.name:spec.pipeline,ctx.last),name=String(nameValue==null?"":nameValue),pipeline=PIPELINES[name];if(!pipeline)fail(stage,"Reusable pipeline không tồn tại: "+name);
 ctx.pipeline_depth=(ctx.pipeline_depth||0)+1;if(ctx.pipeline_depth>12){ctx.pipeline_depth--;fail(stage,"Reusable pipeline gọi lồng quá 12 cấp")}
 var oldArgs=ctx.args;ctx.args=spec.args!==undefined?resolveDeep(ctx,spec.args,ctx.last):{};var steps=Array.isArray(pipeline)?pipeline:(pipeline.steps||[]),policy=Array.isArray(pipeline)?null:pipeline.on_error;
 try{
  try{runStepList(steps,ctx,stage+"."+name)}catch(e){if(isControlFlowError(e))throw e;if(!handleErrorPolicy(policy,e,ctx,stage+"."+name))throw e}
  if(spec.into)ctx.vars[String(spec.into)]=ctx.last;log("PIPELINE",name);return ctx.last;
 }finally{ctx.args=oldArgs;ctx.pipeline_depth=Math.max(0,(ctx.pipeline_depth||1)-1)}
}
function appendValue(bucket,value,flatten){
 if(flatten!==false&&Array.isArray(value)){for(var i=0;i<value.length;i++)bucket.push(value[i])}
 else bucket.push(value);
}
function mappedCollection(source,spec,fields,ctx,allowEmpty){
 var roots=collection(source,spec,ctx),items=[];
 for(var i=0;i<roots.length;i++){
  var mapped=mapFields(roots[i],fields||{},ctx),nameValue=String(mapped.name||mapped.title||"").trim(),urlValue=String(mapped.url||mapped.link||"").trim();
  var meaningful=Object.keys(mapped).some(function(k){return !isEmpty(mapped[k])});
  if(allowEmpty||nameValue||urlValue||meaningful)items.push(mapped);
 }
 return items;
}
function rangeValues(range,ctx){
 range=range||{};var base=ctx.last;
 var start=Number(extract(base,range.from===undefined?1:range.from,ctx)),finish=Number(extract(base,range.to,ctx)),stride=Number(extract(base,range.step===undefined?1:range.step,ctx));
 if(!isFinite(start))start=1;if(!isFinite(finish))return [];if(!isFinite(stride)||stride===0)stride=1;
 var rawRangeMax=range.max!==undefined?Number(resolveDynamic(ctx,range.max,ctx.last)):__NATIVE_V2_MAX_FOREACH_ITEMS__,limit=Math.max(0,Math.min(isFinite(rawRangeMax)?rawRangeMax:__NATIVE_V2_MAX_FOREACH_ITEMS__,__NATIVE_V2_MAX_FOREACH_ITEMS__)),out=[];
 if(stride>0){for(var n=start;n<=finish&&out.length<limit;n+=stride)out.push(n)}else{for(var n2=start;n2>=finish&&out.length<limit;n2+=stride)out.push(n2)}
 return out;
}
function collectLoopValue(loop,ctx){
 if(loop.collect===undefined)return ctx.last;
 var spec=loop.collect;
 if(isObj(spec)&&(spec.items!==undefined||spec.item!==undefined||spec.fields!==undefined)){
  var src=spec.from!==undefined?resolveRef(ctx,spec.from):ctx.last;
  return mappedCollection(src,spec.items!==undefined?spec.items:spec.item,spec.fields||{},ctx,spec.allow_empty===true);
 }
 return extract(ctx.last,spec,ctx);
}
function doStorage(rawSpec,ctx,stage){
 if(!PERMISSIONS||PERMISSIONS.storage!==true)fail(stage,"Nguồn chưa được cấp quyền Storage");
 var spec=resolveDeep(ctx,rawSpec||{},ctx.last),op=String(spec.op||spec.operation||"get").toLowerCase(),scope=String(spec.scope||"local").toLowerCase(),store=scope==="cache"?cacheStorage:localStorage,key=String(spec.key||"");
 if(op!=="clear"&&!key)fail(stage,"storage.key bị thiếu");
 if(op==="get"){var value=store.getItem(key);if(spec.parse==="json"&&value!==null&&value!=="")try{value=JSON.parse(value)}catch(e){fail(stage,"Không parse được JSON trong Storage",e.message||e)};if(spec.into)ctx.vars[String(spec.into)]=value;ctx.last=value;return}
 if(op==="set"){var value2=spec.value!==undefined?spec.value:ctx.last;if(spec.json===true||typeof value2==="object")value2=JSON.stringify(value2);store.setItem(key,String(value2==null?"":value2));ctx.last=value2;return}
 if(op==="remove"){store.removeItem(key);ctx.last=true;return}
 if(op==="clear"){store.clear();ctx.last=true;return}
 fail(stage,"Storage operation không được hỗ trợ: "+op);
}
function doCrypto(rawSpec,ctx,stage){
 var spec=resolveDeep(ctx,rawSpec||{},ctx.last),alg=String(spec.algorithm||spec.alg||"sha256").toLowerCase().replace(/[-_]/g,""),value=String(spec.value!==undefined?spec.value:(ctx.last==null?"":ctx.last)),key=String(spec.key==null?"":spec.key),word;
 if(alg==="md5")word=CryptoJS.MD5(value);else if(alg==="sha1")word=CryptoJS.SHA1(value);else if(alg==="sha256")word=CryptoJS.SHA256(value);else if(alg==="sha512")word=CryptoJS.SHA512(value);
 else if(alg==="hmacmd5")word=CryptoJS.HmacMD5(value,key);else if(alg==="hmacsha1")word=CryptoJS.HmacSHA1(value,key);else if(alg==="hmacsha256")word=CryptoJS.HmacSHA256(value,key);else if(alg==="hmacsha512")word=CryptoJS.HmacSHA512(value,key);
 else fail(stage,"Thuật toán Crypto không được hỗ trợ: "+alg);
 var encoding=String(spec.encoding||"hex").toLowerCase(),out=encoding==="base64"?word.toString(CryptoJS.enc.Base64):word.toString(CryptoJS.enc.Hex);if(spec.into)ctx.vars[String(spec.into)]=out;ctx.last=out;
}
function executeStepOperation(step,ctx,stage){
 if(step.request)return doRequest(step.request,ctx,ctx.operation_count);
 if(step.set){Object.keys(step.set).forEach(function(k){ctx.vars[k]=extract(ctx.last,step.set[k],ctx);log("SET",k,String(ctx.vars[k]).slice(0,160))});return}
 if(step.append){var a=step.append||{},keyValue=resolveDynamic(ctx,a.into!==undefined?a.into:a.var,ctx.last),key=String(keyValue==null?"":keyValue);if(!key)fail(stage,"append.into bị thiếu");var bucket=Array.isArray(ctx.vars[key])?ctx.vars[key]:[],value=a.value===undefined?ctx.last:extract(ctx.last,a.value,ctx);appendValue(bucket,value,a.flatten!==false);ctx.vars[key]=bucket;ctx.last=bucket;log("APPEND",key,"size="+bucket.length);return}
 if(step.foreach||step.for_each){
  var loop=step.foreach||step.for_each||{},values;if(loop.range)values=rangeValues(loop.range,ctx);else{var loopSource=loop.from!==undefined?resolveRef(ctx,loop.from):ctx.last;values=collection(loopSource,loop.items,ctx)}
  var rawLoopMax=loop.max!==undefined?Number(resolveDynamic(ctx,loop.max,ctx.last)):__NATIVE_V2_MAX_FOREACH_ITEMS__,max=Math.max(0,Math.min(isFinite(rawLoopMax)?rawLoopMax:__NATIVE_V2_MAX_FOREACH_ITEMS__,__NATIVE_V2_MAX_FOREACH_ITEMS__));if(values.length>max)values=values.slice(0,max);var intoValue=resolveDynamic(ctx,loop.into,ctx.last),asValue=resolveDynamic(ctx,loop.as,ctx.last),indexValue=resolveDynamic(ctx,loop.index_as,ctx.last),into=String(intoValue==null?"":intoValue),bucket=into?(Array.isArray(ctx.vars[into])?ctx.vars[into]:[]):null,asName=String(asValue==null||asValue===""?"item":asValue),indexName=String(indexValue==null||indexValue===""?"index":indexValue),oldAs=ctx.vars[asName],oldIndex=ctx.vars[indexName];
  try{for(var li=0;li<values.length;li++){ctx.vars[asName]=values[li];ctx.vars[indexName]=li+1;runStepList(loop.steps||[],ctx,stage+"["+(li+1)+"]");if(bucket)appendValue(bucket,collectLoopValue(loop,ctx),loop.flatten!==false)}}
  finally{ctx.vars[asName]=oldAs;ctx.vars[indexName]=oldIndex}
  if(into){ctx.vars[into]=bucket;ctx.last=bucket;log("FOREACH",into,"size="+bucket.length)}return;
 }
 if(step.storage)return doStorage(step.storage,ctx,stage);if(step.crypto)return doCrypto(step.crypto,ctx,stage);if(step.browser)return doBrowser(step.browser,ctx,stage);if(step.transform)return doTransform(step.transform,ctx,stage);if(step.hook)return doHook(step.hook,ctx,stage);if(step.call_pipeline)return doCallPipeline(step.call_pipeline,ctx,stage);
 var branch=step.if_||step.condition;if(branch){var cond=branch.condition!==undefined?branch.condition:branch.when,passed=evalCondition(cond,ctx,ctx.last),chosen=passed?(branch.then_steps||branch.then||[]):(branch.else_steps||branch.else_||[]);log("IF",stage,"branch="+(passed?"then":"else"));return runStepList(chosen,ctx,stage+".branch")}
 if(step.assert){var av=step.assert.condition!==undefined?evalCondition(step.assert.condition,ctx,ctx.last):truthy(extract(ctx.last,step.assert.value!==undefined?step.assert.value:step.assert,ctx));if(!av)fail(stage,String(step.assert.message||"Điều kiện assert không đạt"));return}
 if(step.sleep!==undefined){sleep(Math.max(0,Number(resolveDynamic(ctx,step.sleep,ctx.last))||0));return}if(step.log!==undefined){var logValue=resolveDynamic(ctx,step.log,ctx.last);log("LOG",typeof logValue==="string"?logValue:JSON.stringify(toSerializable(logValue)));return}
}
function runStepList(steps,ctx,prefix){
 steps=Array.isArray(steps)?steps:[];prefix=String(prefix||"step");
 for(var i=0;i<steps.length;i++){
  ctx.operation_count=(ctx.operation_count||0)+1;if(ctx.operation_count>__NATIVE_V2_MAX_PIPELINE_OPERATIONS__)fail(prefix,"Pipeline vượt quá "+String(__NATIVE_V2_MAX_PIPELINE_OPERATIONS__)+" thao tác");var step=steps[i]||{},stage=prefix+"."+(step.label?String(step.label):(i+1));
  if(step.when!==undefined&&!evalCondition(step.when,ctx,ctx.last)){log("SKIP",stage);continue}
  try{executeStepOperation(step,ctx,stage)}catch(e){if(isControlFlowError(e))throw e;if(!handleErrorPolicy(step.on_error,e,ctx,stage))throw e}
 }
}
function runSteps(action,ctx){var steps=Array.isArray(action.steps)?action.steps:[];if(action.request)steps=[{request:action.request}].concat(steps);runStepList(steps,ctx,"step")}
function autoAbsolute(field,value,ctx){if((field==="url"||field==="link"||field==="cover"||field==="next"||field==="prev")&&typeof value==="string")return normalizeUrl(value,ctx.current_url||BASE);return value}
function mapFields(root,fields,ctx){var out={};Object.keys(fields||{}).forEach(function(k){out[k]=autoAbsolute(k,extract(root,fields[k],ctx),ctx)});return out}
function resultType(name,result){var t=String((result&&result.type)||"").toLowerCase();if(t)return t;if(name==="detail")return "detail";if(name==="content")return "content";return "items"}
function finalize(name,action,ctx){
 var result=action.result||{},source=result.from!==undefined?resolveRef(ctx,result.from):ctx.last,type=resultType(name,result);
 if(type==="items"||type==="list"||type==="categories"){
  var items=mappedCollection(source,result.items!==undefined?result.items:result.item,result.fields||{},ctx,result.allow_empty===true);
  var next=result.next!==undefined?extract(source,result.next,ctx):"";
  return {data:items,data2:isEmpty(next)?"":next};
 }
 if(type==="detail"){
  var d=mapFields(source,result.fields||result,ctx);if(!d.url)d.url=ctx.current_url||ctx.input||"";if(!d.name&&!d.title)d.name=String(ctx.vars.title||"");return {data:d,data2:""};
 }
 if(type==="content"){
  var c=mapFields(source,result.fields||result,ctx);
  if(c.content===undefined)c.content=extract(source,result.content!==undefined?result.content:result.body,ctx);
  if(c.title===undefined)c.title=extract(source,result.title,ctx)||"Chương truyện";
  if(c.prev===undefined)c.prev="NO_PREV";if(c.next===undefined)c.next="NO_NEXT";
  c.prev=c.prev?autoAbsolute("prev",c.prev,ctx):"NO_PREV";c.next=c.next?autoAbsolute("next",c.next,ctx):"NO_NEXT";
  return {data:c,data2:""};
 }
 if(type==="value")return {data:extract(source,result.value!==undefined?result.value:result,ctx),data2:""};
 return {data:source,data2:""};
}
function chooseAction(name){if(ACTIONS[name])return name;if(name==="search"&&ACTIONS.stories)return "stories";if(name==="search"&&ACTIONS.latest)return "latest";if(name==="stories"&&ACTIONS.search)return "search";if(name==="stories"&&ACTIONS.latest)return "latest";if(name==="latest"&&ACTIONS.stories)return "stories";if(name==="latest"&&ACTIONS.search)return "search";return name}
function run(name,args){
 name=chooseAction(name);var action=ACTIONS[name];if(!action)fail("dispatch","Action không tồn tại: "+name);
 args=args||{};var initialUrl=String(args.url||"");if(!initialUrl&&/^https?:\/\//i.test(String(args.input||"")))initialUrl=String(args.input||"");if(!initialUrl)initialUrl=BASE;var ctx={input:String(args.input==null?"":args.input),query:String(args.query==null?args.input||"":args.query),page:String(args.page==null?"":args.page),current_url:initialUrl,vars:{},responses:{},args:{},browsers:{},last:null,last_id:"",operation_count:0,pipeline_depth:0};ctx.vars.input=ctx.input;ctx.vars.query=ctx.query;ctx.vars.page=ctx.page;ctx.vars.current_url=ctx.current_url;
 log("ACTION_START",name,"input="+ctx.input,"page="+ctx.page);
 try{runSteps(action,ctx)}catch(e){if(isControlFlowError(e))throw e;var handled=false;try{handled=handleErrorPolicy(action.on_error,e,ctx,"action."+name)}catch(policyError){if(isControlFlowError(policyError))throw policyError;try{closeManagedBrowsers(ctx)}catch(cleanupError){if(isControlFlowError(cleanupError))throw cleanupError}throw policyError}if(!handled){try{closeManagedBrowsers(ctx)}catch(cleanupError2){if(isControlFlowError(cleanupError2))throw cleanupError2}throw e}}
 closeManagedBrowsers(ctx);
 var out=finalize(name,action,ctx);log("ACTION_DONE",name,"items="+(Array.isArray(out.data)?out.data.length:"object"),"next="+String(out.data2||""));return out;
}
function response(name,args){try{var r=run(name,args||{});return Response.success(r.data,r.data2)}catch(e){if(e&&e.__vbookNeedHttp)throw e;if(e&&e.__vbookNeedBrowser)throw e;log("ERROR",name,String(e&&e.stack?e.stack:e));return Response.error(String(e&&e.message?e.message:e))}}
return {run:run,response:response,version:RUNTIME_VERSION};
})();
]====]

local function replaceToken(source, token, value)
  return (source:gsub(token, function() return value end))
end

local function wrapper(actionName, signature, argsExpression)
  return table.concat({
    [[load("native_v2_core.js");]],
    "function execute(" .. signature .. ") {",
    "  return NativeV2.response(" .. string.format("%q", actionName) .. ", " .. argsExpression .. ");",
    "}",
  }, "\n")
end

function Adapter.build(sourceInput, metadata, options)
  options = options or {}
  local bindClass = options.bindClass
  local actions = type(sourceInput.actions) == "table" and sourceInput.actions or {}
  local pipelines = type(sourceInput.pipelines) == "table" and sourceInput.pipelines or {}
  local config = type(sourceInput.config) == "table" and sourceInput.config or {}
  local permissions = type(sourceInput.permissions) == "table" and sourceInput.permissions or {}
  if permissions.browser == nil then permissions.browser = false end
  if permissions.storage == nil then permissions.storage = false end
  if permissions.network_capture == nil then permissions.network_capture = false end
  local base = text(sourceInput.base_url or sourceInput.home_url or metadata.website):gsub("/+$", "")
  local name = text(metadata.name or sourceInput.name or "Native Source API 2")

  local encodedBase = jsonEncode(base, bindClass)
  if encodedBase:sub(1, 1) ~= '"' or encodedBase:sub(-1) ~= '"' then
    error("Native Source API 2 không mã hóa được base_url thành chuỗi JSON hợp lệ")
  end

  local core = CORE_TEMPLATE
  core = replaceToken(core, "__NATIVE_V2_RUNTIME_VERSION__", tostring(NativeApi.RUNTIME_VERSION))
  core = replaceToken(core, "__NATIVE_V2_MAX_HOOK_INPUT_BYTES__", tostring(NativeApi.MAX_HOOK_INPUT_BYTES))
  core = replaceToken(core, "__NATIVE_V2_MAX_FOREACH_ITEMS__", tostring(NativeApi.MAX_FOREACH_ITEMS))
  core = replaceToken(core, "__NATIVE_V2_MAX_PIPELINE_OPERATIONS__", tostring(NativeApi.MAX_PIPELINE_OPERATIONS))
  core = replaceToken(core, "__NATIVE_V2_ACTIONS__", jsonEncode(actions, bindClass))
  core = replaceToken(core, "__NATIVE_V2_PIPELINES__", jsonEncode(pipelines, bindClass))
  core = replaceToken(core, "__NATIVE_V2_BASE__", encodedBase)
  core = replaceToken(core, "__NATIVE_V2_PERMISSIONS__", jsonEncode(permissions, bindClass))

  local files = {
    ["native_v2_core.js"] = core,
    ["native_v2_search.js"] = wrapper("search", "query, page", [[{input: query || "", query: query || "", page: page || "", url: ""}]]),
    ["native_v2_stories.js"] = wrapper("stories", "input, page", [[{input: input || "", query: input || "", page: page || "", url: input || ""}]]),
    ["native_v2_latest.js"] = wrapper("latest", "input, page", [[{input: input || "", query: input || "", page: page || "", url: input || ""}]]),
    ["native_v2_detail.js"] = actions.detail and wrapper("detail", "url", [[{input: url || "", url: url || ""}]]) or [[function execute(url) { return Response.success({name:String(url || "Truyện"), url:String(url || "")}); }]],
    ["native_v2_toc.js"] = wrapper("chapters", "url", [[{input: url || "", url: url || ""}]]),
    ["native_v2_chap.js"] = wrapper("content", "url", [[{input: url || "", url: url || ""}]]),
  }

  if actions.comments then
    files["native_v2_comments.js"] = wrapper("comments", "input, page", [[{input: input || "", query: input || "", page: page || "", url: input || ""}]])
    if actions.detail then
      files["native_v2_detail.js"] = table.concat({
        [[load("native_v2_core.js");]],
        [[function execute(url) {]],
        [[  try {]],
        [[    var r = NativeV2.run("detail", {input:url || "", url:url || ""});]],
        [[    var d = (r && r.data && typeof r.data === "object") ? r.data : {};]],
        [[    var comments = Array.isArray(d.comments) ? d.comments.slice() : [];]],
        [[    comments.push({title:"Bình luận", input:String(url || ""), script:"native_v2_comments.js"});]],
        [[    d.comments = comments;]],
        [[    return Response.success(d);]],
        [[  } catch (e) { if (e && e.__vbookNeedHttp) throw e; if (e && e.__vbookNeedBrowser) throw e; return Response.error(String(e && e.message ? e.message : e)); }]],
        [[}]],
      }, "\n")
    end
  end

  local categoryAction = actions.stories and "stories" or (actions.search and "search" or (actions.latest and "latest" or "stories"))
  files["native_v2_genre.js"] = table.concat({
    [[load("native_v2_core.js");]],
    "function execute() {",
    [[  var r = NativeV2.run("categories", {input:"", query:"", page:"", url:""});]],
    [[  var list = Array.isArray(r.data) ? r.data : [];]],
    [[  return Response.success(list.map(function(x){ return { title: String(x.title || x.name || "Mục"), input: String(x.url || x.link || x.input || ""), script: ]] .. string.format("%q", categoryAction == "search" and "native_v2_search.js" or (categoryAction == "latest" and "native_v2_latest.js" or "native_v2_stories.js")) .. [[ }; }));]],
    "}",
  }, "\n")

  local homeEntries = {}
  local uiSpec = type(sourceInput.ui) == "table" and sourceInput.ui or {}
  local exploreUi = type(uiSpec.explore) == "table" and uiSpec.explore or {}
  local isSangtacvietNative = tostring(metadata.id or ""):find("sangtacviet%-native%-") ~= nil
  local defaultShortcutMode = (isSangtacvietNative and actions.categories) and "none" or "auto"
  local shortcutMode = tostring(exploreUi.home_shortcuts or defaultShortcutMode):lower():gsub("[- ]", "_")
  local allowLatest, allowStories = true, true
  if shortcutMode == "none" or shortcutMode == "hidden" or shortcutMode == "off" then allowLatest, allowStories = false, false end
  if type(exploreUi.home_shortcuts) == "table" then
    allowLatest = exploreUi.home_shortcuts.latest ~= false
    allowStories = exploreUi.home_shortcuts.stories ~= false
  end
  if allowLatest and actions.latest then homeEntries[#homeEntries + 1] = [[{title:"Mới cập nhật", input:"", script:"native_v2_latest.js"}]] end
  if allowStories and actions.stories then homeEntries[#homeEntries + 1] = [[{title:"Danh sách", input:"", script:"native_v2_stories.js"}]] end
  files["native_v2_home.js"] = table.concat({
    "function execute() {",
    "  return Response.success([" .. table.concat(homeEntries, ",") .. "]);",
    "}",
  }, "\n")

  local manifest = {
    metadata = {
      id = text(metadata.id or sourceInput.id or "native-v2"),
      name = name,
      author = text(metadata.author or "Native Source"),
      version = tonumber(metadata.version or 1) or 1,
      source = base,
      description = text(metadata.description or "Native Source API v2"),
      locale = text(metadata.locale or "vi"),
      regexp = text(metadata.regexp or ""),
      type = "novel",
      nsfw = metadata.nsfw == true,
    },
    script = {
      search = "native_v2_search.js",
      detail = "native_v2_detail.js",
      toc = "native_v2_toc.js",
      chap = "native_v2_chap.js",
    },
    config = config,
  }
  if actions.categories then manifest.script.genre = "native_v2_genre.js" end
  if actions.comments then manifest.script.comments = "native_v2_comments.js" end
  if #homeEntries > 0 then manifest.script.home = "native_v2_home.js" end

  local filesPayload = {}
  for path, body in pairs(files) do filesPayload["src/" .. path] = body end

  local capabilities = {
    categories = actions.categories ~= nil,
    stories = actions.stories ~= nil or actions.search ~= nil or actions.latest ~= nil,
    detail = actions.detail ~= nil,
    chapters = actions.chapters ~= nil,
    content = actions.content ~= nil,
    comments = actions.comments ~= nil,
    search = actions.search ~= nil,
    latest = actions.latest ~= nil,
    dynamic_web = permissions.browser == true,
    network_capture = permissions.network_capture == true,
  }

  return {
    manifest_json = jsonEncode(manifest, bindClass),
    files_json = jsonEncode(filesPayload, bindClass),
    capabilities = capabilities,
    search_url = actions.search and (base .. "#__vbook_search=") or "",
    latest_url = "",
    runtime = NativeApi.ENGINE_NAME,
  }
end

return Adapter
