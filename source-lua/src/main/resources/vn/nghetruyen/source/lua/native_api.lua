local NativeApi = {}

NativeApi.CURRENT_VERSION = 2
NativeApi.SUPPORTED_VERSIONS = { [2] = true }
NativeApi.RUNTIME_VERSION = 16
NativeApi.ENGINE_NAME = "native-api-2-stable-r16"
NativeApi.MAX_SOURCE_BYTES = 1024 * 1024
NativeApi.MAX_INSTRUCTIONS = 500000
NativeApi.HOOK_GRANULARITY = 1000
NativeApi.MAX_HOOK_INPUT_BYTES = 512 * 1024
NativeApi.MAX_HOOK_OUTPUT_BYTES = 1024 * 1024
NativeApi.MAX_PIPELINE_OPERATIONS = 1500
NativeApi.MAX_FOREACH_ITEMS = 500
NativeApi.MAX_HTTP_REPLAY_STEPS = 256
NativeApi.MAX_BROWSER_REPLAY_STEPS = 96
NativeApi.MAX_HTTP_REPLAY_CACHE_BYTES = 32 * 1024 * 1024
NativeApi.MAX_BROWSER_REPLAY_CACHE_BYTES = 32 * 1024 * 1024
NativeApi.INSTRUCTION_LIMIT_MARKER = "__NATIVE_SOURCE_INSTRUCTION_LIMIT__"

local function text(value)
  if value == nil then return "" end
  return tostring(value)
end

local function trim(value)
  return text(value):match("^%s*(.-)%s*$") or ""
end

local function isArray(value)
  if type(value) ~= "table" then return false end
  local count = 0
  for key in pairs(value) do
    if type(key) ~= "number" or key < 1 or key % 1 ~= 0 then return false end
    if key > count then count = key end
  end
  for i = 1, count do if value[i] == nil then return false end end
  return true
end

local function pushWarning(warnings, code, path, message)
  warnings[#warnings + 1] = {
    code = text(code),
    path = text(path),
    message = text(message),
  }
end

local function allowedKeyWarnings(value, path, allowed, warnings)
  if type(value) ~= "table" then return end
  for key in pairs(value) do
    if type(key) == "string" and not allowed[key] then
      pushWarning(warnings, "UNKNOWN_FIELD", path .. "." .. key, "Trường này không thuộc hợp đồng Native Source API hiện tại và sẽ bị bỏ qua nếu runtime không biết cách xử lý.")
    end
  end
end

local function validateStringList(value, path, allowEmpty)
  if value == nil then return true end
  if type(value) == "string" then
    if not allowEmpty and trim(value) == "" then return false, path .. " không được rỗng" end
    return true
  end
  if type(value) ~= "table" or not isArray(value) then return false, path .. " phải là chuỗi hoặc danh sách chuỗi" end
  for i, item in ipairs(value) do
    if type(item) ~= "string" or (not allowEmpty and trim(item) == "") then
      return false, path .. "[" .. tostring(i) .. "] phải là chuỗi không rỗng"
    end
  end
  return true
end

function NativeApi.isSupportedVersion(version)
  return NativeApi.SUPPORTED_VERSIONS[tonumber(version) or -1] == true
end

function NativeApi.supportedVersionText()
  local versions = {}
  for version, enabled in pairs(NativeApi.SUPPORTED_VERSIONS) do if enabled then versions[#versions + 1] = tonumber(version) end end
  table.sort(versions)
  local out = {}
  for _, version in ipairs(versions) do out[#out + 1] = "v" .. tostring(version) end
  return table.concat(out, ", ")
end

function NativeApi.normalizeId(value)
  local id = trim(value):lower()
  if id == "" then return "" end
  id = id:gsub("%s+", "-")
  return id
end

function NativeApi.validateId(value)
  local id = NativeApi.normalizeId(value)
  if id == "" then return false, "metadata.id bị thiếu" end
  if #id < 3 or #id > 128 then return false, "metadata.id phải dài từ 3 đến 128 ký tự" end
  if not id:match("^[a-z0-9][a-z0-9%._%-]*[a-z0-9]$") then
    return false, "metadata.id chỉ được dùng chữ thường a-z, số, dấu chấm, gạch dưới và gạch ngang"
  end
  if id:find("..", 1, true) then return false, "metadata.id không được có hai dấu chấm liên tiếp" end
  return true, nil, id
end

function NativeApi.ensureMetadataId(metadata, _, _, warnings)
  warnings = warnings or {}
  metadata = metadata or {}
  local id = NativeApi.normalizeId(metadata.id)
  if id == "" then return nil, false, warnings, "metadata.id bị thiếu" end
  local ok, err, normalized = NativeApi.validateId(id)
  if not ok then return nil, false, warnings, err end
  metadata.id = normalized
  return normalized, false, warnings
end

function NativeApi.normalizeAllowedHosts(value)
  local out, seen = {}, {}
  if type(value) == "string" then value = { value } end
  if type(value) ~= "table" then return out end
  for _, raw in ipairs(value) do
    local host = trim(raw):lower():gsub("^https?://", ""):match("^([^/%?#:]+)") or trim(raw):lower()
    host = host:gsub("^www%.", ""):gsub("%.$", "")
    if host ~= "" and not seen[host] then seen[host] = true; out[#out + 1] = host end
  end
  return out
end


local V2_RESERVED_CONFIG_KEYS = {
  fetch=true,Http=true,Html=true,Document=true,Response=true,Engine=true,UserAgent=true,
  localConfig=true,cacheStorage=true,localCookie=true,Console=true,Log=true,Script=true,sleep=true,
  Qt=true,Graphics=true,WebSocket=true,console=true,load=true,localStorage=true,JSON=true,Math=true,Date=true,URL=true,
  decodeURIComponent=true,encodeURIComponent=true,parseInt=true,parseFloat=true,isNaN=true,document=true,
  __bridge=true,__base64ToUtf8=true,__utf8ToBase64=true,
}

local V2_ACTION_KEYS = { request=true, steps=true, result=true, description=true, on_error=true }
local V2_PIPELINE_KEYS = { steps=true, description=true, on_error=true }
local V2_CONDITION_KEYS = {
  op=true,left=true,right=true,value=true,from=true,path=true,var=true,template=true,
  all=true,any=true,["not"]=true,flags=true,ignore_case=true,
}
local V2_ON_ERROR_KEYS = { mode=true, action=true, fallback=true, steps=true, set=true, into=true, value=true, message=true }
local V2_TRANSFORM_KEYS = {
  from=true,input=true,value=true,into=true,op=true,operation=true,ops=true,operations=true,
  pattern=true,replacement=true,flags=true,plain=true,separator=true,delimiter=true,
  start=true,stop=true,["end"]=true,length=true,fields=true,item=true,items=true,
  condition=true,by=true,desc=true,descending=true,deep=true,compact=true,keep_empty=true,
  as=true,index_as=true,map=true,map_default=true,
}
local V2_HOOK_KEYS = { name=true,input=true,value=true,args=true,into=true,context=true,context_vars=true }
local V2_APPEND_KEYS = { into=true,var=true,value=true,flatten=true }
local V2_FOREACH_KEYS = { from=true,items=true,range=true,steps=true,max=true,into=true,collect=true,flatten=true,as=true,index_as=true }
local V2_STORAGE_KEYS = { op=true,operation=true,scope=true,key=true,value=true,parse=true,json=true,into=true }
local V2_CRYPTO_KEYS = { algorithm=true,alg=true,value=true,key=true,encoding=true,into=true }
local V2_ASSERT_KEYS = { condition=true,value=true,message=true }
local V2_BROWSER_FETCH_KEYS = { id=true,method=true,headers=true,body=true,response=true,type=true,charset=true,timeout=true,retries=true,allow_http_error=true,cookie_mode=true }
local V2_BROWSER_KEYS = {
  op=true,operation=true,session=true,name=true,url=true,timeout=true,wait_timeout=true,
  user_agent=true,block=true,auto_close=true,into=true,selectors=true,selector=true,
  patterns=true,urls=true,match=true,method=true,wait_method=true,main_frame=true,limit=true,latest=true,
  fetch=true,script=true,evaluate=true,response=true,type=true,wait_ms=true,wait=true,
  wait_selector=true,wait_request=true,wait_url=true,allow_timeout=true,allow_wait_timeout=true,
  snapshot=true,use_captured_url=true,names=true,replay=true,replay_key=true,
  dialog_policy=true,alert_policy=true,policy=true,rules=true,default_action=true,default_value=true,dialog_type=true,match_mode=true,after_id=true,direction=true,cookie=true,cookies=true,
}
local V2_BROWSER_OPS = {
  open=true,create=true,launch=true,launch_async=true,open_url=true,wait_selector=true,wait_url=true,wait_request=true,
  capture=true,requests=true,evaluate=true,js=true,call_js=true,evaluate_json=true,evaluate_object=true,call_js_json=true,call_js_object=true,object=true,evaluate_async=true,js_async=true,call_js_async=true,tap=true,tap_selector=true,click=true,click_selector=true,clear_cookies=true,clear_cookie=true,reset_cookies=true,html=true,snapshot=true,urls=true,
  cookies=true,cookie=true,handoff=true,cookie_snapshot=true,session_snapshot=true,sync_session=true,session_sync=true,sync_cookies=true,set_cookies=true,import_cookies=true,
  set_dialog_policy=true,dialog_policy=true,alert_policy=true,js_dialog_policy=true,dialogs=true,dialog_history=true,last_dialog=true,dialog_last=true,wait_dialog=true,dialog_wait=true,block=true,close=true,
}
local V2_CONDITION_OPS = {
  truthy=true,falsy=true,empty=true,not_empty=true,exists=true,
  equals=true,equal=true,eq=true,["=="]=true,not_equals=true,not_equal=true,neq=true,["!="]=true,
  contains=true,not_contains=true,starts_with=true,ends_with=true,matches=true,regex=true,["in"]=true,
  greater=true,gt=true,greater_equal=true,gte=true,less=true,lt=true,less_equal=true,lte=true,
}
local V2_TRANSFORM_OPS = {
  trim=true,lower=true,lowercase=true,upper=true,uppercase=true,replace=true,regex_replace=true,
  split=true,join=true,substring=true,slice_text=true,url_encode=true,url_decode=true,html_decode=true,
  base64_encode=true,base64_decode=true,json_encode=true,json_decode=true,flatten=true,reverse=true,
  first=true,last=true,slice=true,map=true,filter=true,unique=true,sort=true,
}
local V2_REQUEST_KEYS = {
  id=true,url=true,method=true,query=true,queries=true,headers=true,body=true,
  response=true,type=true,charset=true,timeout=true,retries=true,allow_http_error=true,
  transport=true,block=true,user_agent=true,wait_url=true,wait_selector=true,wait_request=true,wait_method=true,wait_timeout=true,wait_ms=true,
  evaluate=true,evaluate_timeout=true,allow_wait_timeout=true,replay=true,replay_key=true,cookie_mode=true,dialog_policy=true,alert_policy=true,
}
local V2_STEP_KEYS = {
  request=true,set=true,append=true,foreach=true,for_each=true,storage=true,crypto=true,
  assert=true,sleep=true,log=true,["when"]=true,on_error=true,label=true,
  transform=true,if_=true,condition=true,call_pipeline=true,hook=true,browser=true,
}
local V2_RESULT_KEYS = {
  type=true,from=true,items=true,item=true,fields=true,next=true,allow_empty=true,
  content=true,body=true,title=true,value=true,
}

local function validateDialogPolicy(policy, path, warnings)
  if policy == nil then return true end
  if type(policy) ~= "table" then return false, path .. " phải là table" end
  allowedKeyWarnings(policy,path,{default_action=true,default_value=true,rules=true},warnings)
  if policy.default_action~=nil and type(policy.default_action)~="string" then return false,path..".default_action phải là chuỗi" end
  if policy.default_value~=nil and type(policy.default_value)~="string" then return false,path..".default_value phải là chuỗi" end
  local action=trim(policy.default_action):lower():gsub("[- ]","_")
  local allowedActions={accept=true,dismiss=true,passthrough=true,ok=true,confirm=true,accepted=true,cancel=true,deny=true,rejected=true,show=true,["default"]=true,none=true}
  if action~="" and not allowedActions[action] then return false,path..".default_action không hỗ trợ: "..tostring(policy.default_action) end
  if policy.rules~=nil then
    if type(policy.rules)~="table" or not isArray(policy.rules) then return false,path..".rules phải là danh sách" end
    if #policy.rules>64 then return false,path..".rules chỉ hỗ trợ tối đa 64 rule" end
    local types={alert=true,confirm=true,prompt=true,any=true}
    local modes={contains=true,equals=true,equal=true,starts_with=true,prefix=true,ends_with=true,suffix=true,regex=true,matches=true}
    for i,rule in ipairs(policy.rules) do
      local rp=path..".rules["..tostring(i).."]"
      if type(rule)~="table" then return false,rp.." phải là table" end
      allowedKeyWarnings(rule,rp,{type=true,match=true,match_mode=true,mode=true,action=true,value=true,ignore_case=true},warnings)
      for _,k in ipairs({"type","match","match_mode","mode","action","value"}) do if rule[k]~=nil and type(rule[k])~="string" then return false,rp.."."..k.." phải là chuỗi" end end
      if rule.ignore_case~=nil and type(rule.ignore_case)~="boolean" then return false,rp..".ignore_case phải là boolean" end
      local typ=trim(rule.type):lower(); if typ=="" then typ="any" end
      if not types[typ] then return false,rp..".type không hỗ trợ: "..tostring(rule.type) end
      local mode=trim(rule.match_mode or rule.mode):lower():gsub("[- ]","_"); if mode=="" then mode="contains" end
      if not modes[mode] then return false,rp..".match_mode không hỗ trợ: "..tostring(rule.match_mode or rule.mode) end
      local act=trim(rule.action):lower():gsub("[- ]","_"); if act=="" then act="passthrough" end
      if not allowedActions[act] then return false,rp..".action không hỗ trợ: "..tostring(rule.action) end
    end
  end
  return true
end

local function isDynamicString(value)
  return type(value) == "string" and ((value:sub(1, 1) == "$" and value:sub(1, 2) ~= "$$") or value:find("{", 1, true) ~= nil)
end

local function validateV2Request(request, path, warnings)
  if type(request) ~= "table" then return false, path .. " phải là table" end
  allowedKeyWarnings(request, path, V2_REQUEST_KEYS, warnings)
  if request.url ~= nil and type(request.url) ~= "string" then return false, path .. ".url phải là chuỗi" end
  if request.method ~= nil and type(request.method) ~= "string" then return false, path .. ".method phải là chuỗi" end
  for _, key in ipairs({"query","queries","headers"}) do
    if request[key] ~= nil and type(request[key]) ~= "table" then return false, path .. "." .. key .. " phải là table" end
  end
  local function validNumberOrTemplate(value)
    if value == nil then return true end
    if type(value) == "number" then return true end
    if type(value) ~= "string" then return false end
    return tonumber(value) ~= nil or isDynamicString(value)
  end
  if not validNumberOrTemplate(request.timeout) then return false, path .. ".timeout phải là số hoặc template" end
  if not validNumberOrTemplate(request.retries) then return false, path .. ".retries phải là số hoặc template" end
  if request.allow_http_error ~= nil and type(request.allow_http_error) ~= "boolean" then return false, path .. ".allow_http_error phải là boolean" end
  if request.replay ~= nil and type(request.replay) ~= "string" then return false, path .. ".replay phải là chuỗi" end
  if request.replay_key ~= nil and type(request.replay_key) ~= "string" then return false, path .. ".replay_key phải là chuỗi" end
  if request.cookie_mode ~= nil and type(request.cookie_mode) ~= "string" then return false, path .. ".cookie_mode phải là chuỗi" end
  if request.dialog_policy ~= nil and type(request.dialog_policy) ~= "table" then return false, path .. ".dialog_policy phải là table" end
  if request.alert_policy ~= nil and type(request.alert_policy) ~= "table" then return false, path .. ".alert_policy phải là table" end
  local dialogOk,dialogErr=validateDialogPolicy(request.dialog_policy or request.alert_policy,path..".dialog_policy",warnings)
  if not dialogOk then return false,dialogErr end
  local cookieMode = trim(request.cookie_mode):lower():gsub("[- ]", "_")
  if cookieMode == "readonly" then cookieMode = "read_only" end
  if cookieMode == "writeonly" then cookieMode = "write_only" end
  if cookieMode ~= "" and cookieMode ~= "shared" and cookieMode ~= "none" and cookieMode ~= "read_only" and cookieMode ~= "write_only" then return false, path .. ".cookie_mode chỉ hỗ trợ shared, none, read_only hoặc write_only" end
  local requestReplay = trim(request.replay):lower():gsub("[- ]", "_")
  if requestReplay ~= "" and requestReplay ~= "auto" and requestReplay ~= "fresh" and requestReplay ~= "keyed" then return false, path .. ".replay chỉ hỗ trợ auto, fresh hoặc keyed" end
  if requestReplay == "keyed" and trim(request.replay_key) == "" then return false, path .. ".replay_key là bắt buộc khi replay = keyed" end
  local transport = trim(request.transport):lower()
  if transport ~= "" and transport ~= "http" and transport ~= "browser" then return false, path .. ".transport chỉ hỗ trợ http hoặc browser" end
  local responseType = trim(request.response or request.type):lower()
  local allowedResponse = {html=true,json=true,text=true,base64=true,bytes=true,response=true,raw=true,auto=true}
  if responseType ~= "" and not allowedResponse[responseType] then return false, path .. ".response không được hỗ trợ: " .. responseType end
  if transport == "browser" then
    if request.wait_url ~= nil then
      local ok, err = validateStringList(request.wait_url, path .. ".wait_url", false)
      if not ok then return false, err end
    end
    if request.block ~= nil then
      local ok, err = validateStringList(request.block, path .. ".block", false)
      if not ok then return false, err end
    end
    if request.wait_selector ~= nil then
      local ok, err = validateStringList(request.wait_selector, path .. ".wait_selector", false)
      if not ok then return false, err end
    end
    if request.wait_request ~= nil then
      local ok, err = validateStringList(request.wait_request, path .. ".wait_request", false)
      if not ok then return false, err end
    end
    if request.wait_method ~= nil and type(request.wait_method) ~= "string" then return false, path .. ".wait_method phải là chuỗi" end
    if request.allow_wait_timeout ~= nil and type(request.allow_wait_timeout) ~= "boolean" then return false, path .. ".allow_wait_timeout phải là boolean" end
  end
  return true
end

local function validateV2Browser(browser, path, warnings)
  if type(browser) ~= "table" then return false, path .. " phải là table" end
  allowedKeyWarnings(browser, path, V2_BROWSER_KEYS, warnings)
  local op = trim(browser.op or browser.operation):lower():gsub("[- ]", "_")
  if op ~= "" and not V2_BROWSER_OPS[op] then return false, path .. ".op không được hỗ trợ: " .. tostring(browser.op or browser.operation) end
  for _, key in ipairs({"session","name","url","user_agent","method","wait_method","script","evaluate","response","type","replay","replay_key","default_action","default_value","dialog_type","match_mode","direction","cookie"}) do
    if browser[key] ~= nil and type(browser[key]) ~= "string" then return false, path .. "." .. key .. " phải là chuỗi" end
  end
  local browserReplay = trim(browser.replay):lower():gsub("[- ]", "_")
  if browserReplay ~= "" and browserReplay ~= "auto" and browserReplay ~= "fresh" and browserReplay ~= "keyed" then return false, path .. ".replay chỉ hỗ trợ auto, fresh hoặc keyed" end
  if browserReplay == "keyed" and trim(browser.replay_key) == "" then return false, path .. ".replay_key là bắt buộc khi replay = keyed" end
  for _, key in ipairs({"block","selectors","selector","patterns","urls","match","wait_selector","wait_request","wait_url","names"}) do
    if browser[key] ~= nil then
      local ok, err = validateStringList(browser[key], path .. "." .. key, false)
      if not ok then return false, err end
    end
  end
  for _, key in ipairs({"auto_close","main_frame","latest","allow_timeout","allow_wait_timeout","snapshot","use_captured_url"}) do
    if browser[key] ~= nil and type(browser[key]) ~= "boolean" then return false, path .. "." .. key .. " phải là boolean" end
  end
  if browser.auto_close == false then pushWarning(warnings, "AUTO_CLOSE_STABLE", path .. ".auto_close", "API 2 Stable luôn đóng Browser session khi action kết thúc; auto_close=false được giữ để đọc nguồn cũ nhưng không còn vô hiệu hóa cleanup cuối action.") end
  for _, key in ipairs({"timeout","wait_timeout","limit","wait_ms","wait"}) do
    if browser[key] ~= nil and type(browser[key]) ~= "number" and type(browser[key]) ~= "string" then return false, path .. "." .. key .. " phải là số hoặc template" end
  end
  if browser.dialog_policy ~= nil and type(browser.dialog_policy) ~= "table" then return false, path .. ".dialog_policy phải là table" end
  if browser.alert_policy ~= nil and type(browser.alert_policy) ~= "table" then return false, path .. ".alert_policy phải là table" end
  if browser.policy ~= nil and type(browser.policy) ~= "table" then return false, path .. ".policy phải là table" end
  if browser.rules ~= nil and type(browser.rules) ~= "table" then return false, path .. ".rules phải là table" end
  local browserDialogPolicy=browser.dialog_policy or browser.alert_policy or browser.policy
  if browserDialogPolicy==nil and (op=="set_dialog_policy" or op=="dialog_policy" or op=="alert_policy" or op=="js_dialog_policy") then
    browserDialogPolicy={default_action=browser.default_action,default_value=browser.default_value,rules=browser.rules}
  end
  local dialogOk,dialogErr=validateDialogPolicy(browserDialogPolicy,path..".dialog_policy",warnings)
  if not dialogOk then return false,dialogErr end
  if (op=="sync_session" or op=="session_sync" or op=="sync_cookies") and browser.direction~=nil then
    local direction=trim(browser.direction):lower():gsub("[- ]","_")
    if direction=="browser_to_http" then direction="browser_to_native" end
    if direction=="http_to_browser" then direction="native_to_browser" end
    if direction~="both" and direction~="browser_to_native" and direction~="native_to_browser" then return false,path..".direction không hợp lệ" end
  end
  if (op=="wait_dialog" or op=="dialog_wait") then
    if browser.match~=nil and type(browser.match)~="string" then return false,path..".match phải là chuỗi khi dùng wait_dialog" end
    if browser.dialog_type~=nil then
      local typ=trim(browser.dialog_type):lower(); if typ~="" and typ~="any" and typ~="alert" and typ~="confirm" and typ~="prompt" then return false,path..".dialog_type không hợp lệ" end
    end
    if browser.match_mode~=nil then
      local mode=trim(browser.match_mode):lower():gsub("[- ]","_")
      local modes={contains=true,equals=true,equal=true,starts_with=true,prefix=true,ends_with=true,suffix=true,regex=true,matches=true}
      if mode~="" and not modes[mode] then return false,path..".match_mode không hợp lệ" end
    end
  end
  if browser.cookies ~= nil and type(browser.cookies) ~= "table" and type(browser.cookies) ~= "string" then return false, path .. ".cookies phải là chuỗi hoặc danh sách chuỗi" end
  if browser.fetch ~= nil and type(browser.fetch) ~= "boolean" and type(browser.fetch) ~= "table" then return false, path .. ".fetch phải là boolean hoặc table" end
  if browser.fetch ~= nil and op ~= "capture" and op ~= "requests" then return false, path .. ".fetch chỉ hợp lệ với op = capture hoặc requests" end
  if type(browser.fetch) == "table" then
    allowedKeyWarnings(browser.fetch, path .. ".fetch", V2_BROWSER_FETCH_KEYS, warnings)
    if browser.fetch.method ~= nil and type(browser.fetch.method) ~= "string" then return false, path .. ".fetch.method phải là chuỗi" end
    if browser.fetch.headers ~= nil and type(browser.fetch.headers) ~= "table" then return false, path .. ".fetch.headers phải là table" end
    if browser.fetch.charset ~= nil and type(browser.fetch.charset) ~= "string" then return false, path .. ".fetch.charset phải là chuỗi" end
    if browser.fetch.id ~= nil and type(browser.fetch.id) ~= "string" then return false, path .. ".fetch.id phải là chuỗi" end
    if browser.fetch.cookie_mode ~= nil and type(browser.fetch.cookie_mode) ~= "string" then return false, path .. ".fetch.cookie_mode phải là chuỗi" end
    local fetchCookieMode = trim(browser.fetch.cookie_mode):lower():gsub("[- ]", "_")
    if fetchCookieMode == "readonly" then fetchCookieMode = "read_only" end
    if fetchCookieMode == "writeonly" then fetchCookieMode = "write_only" end
    if fetchCookieMode ~= "" and fetchCookieMode ~= "shared" and fetchCookieMode ~= "none" and fetchCookieMode ~= "read_only" and fetchCookieMode ~= "write_only" then return false, path .. ".fetch.cookie_mode không hợp lệ" end
    if browser.fetch.allow_http_error ~= nil and type(browser.fetch.allow_http_error) ~= "boolean" then return false, path .. ".fetch.allow_http_error phải là boolean" end
    for _, key in ipairs({"timeout", "retries"}) do
      if browser.fetch[key] ~= nil and type(browser.fetch[key]) ~= "number" and type(browser.fetch[key]) ~= "string" then return false, path .. ".fetch." .. key .. " phải là số hoặc template" end
    end
    local fetchResponse = trim(browser.fetch.response or browser.fetch.type):lower()
    local allowedFetchResponse = {html=true,json=true,text=true,base64=true,bytes=true,response=true,raw=true,auto=true}
    if fetchResponse ~= "" and not allowedFetchResponse[fetchResponse] then return false, path .. ".fetch.response không được hỗ trợ: " .. fetchResponse end
  end
  if op == "evaluate" or op == "js" or op == "call_js" or op == "evaluate_json" or op == "evaluate_object" or op == "call_js_json" or op == "call_js_object" or op == "object" or browser.evaluate ~= nil or browser.script ~= nil then
    local evalType = trim(browser.response or browser.type):lower()
    local allowedEvalType = {value=true,json=true,object=true,html=true,text=true,raw=true,auto=true}
    if evalType ~= "" and not allowedEvalType[evalType] then return false, path .. ".response không được hỗ trợ cho evaluate: " .. evalType end
  end
  return true
end

local function validateV2Result(result, path, warnings)
  if type(result) ~= "table" then return false, path .. " phải là table" end
  allowedKeyWarnings(result, path, V2_RESULT_KEYS, warnings)
  if result.type ~= nil and type(result.type) ~= "string" then return false, path .. ".type phải là chuỗi" end
  if result.type ~= nil then
    local resultType = trim(result.type):lower()
    local supported = {items=true,list=true,categories=true,detail=true,content=true,value=true,raw=true}
    if resultType ~= "" and not supported[resultType] then return false, path .. ".type không được hỗ trợ: " .. tostring(result.type) end
  end
  if result.fields ~= nil and type(result.fields) ~= "table" then return false, path .. ".fields phải là table" end
  if result.allow_empty ~= nil and type(result.allow_empty) ~= "boolean" then return false, path .. ".allow_empty phải là boolean" end
  return true
end

local validateV2Steps

local function validateV2Condition(condition, path, warnings, depth)
  depth = tonumber(depth or 0) or 0
  if depth > 8 then return false, path .. " lồng điều kiện quá sâu (tối đa 8 cấp)" end
  if condition == nil or type(condition) == "boolean" or type(condition) == "number" or type(condition) == "string" then return true end
  if type(condition) ~= "table" then return false, path .. " phải là boolean, chuỗi hoặc table" end
  allowedKeyWarnings(condition, path, V2_CONDITION_KEYS, warnings)
  for _, key in ipairs({"all", "any"}) do
    if condition[key] ~= nil then
      if type(condition[key]) ~= "table" or not isArray(condition[key]) then return false, path .. "." .. key .. " phải là danh sách điều kiện" end
      for i, child in ipairs(condition[key]) do
        local ok, err = validateV2Condition(child, path .. "." .. key .. "[" .. tostring(i) .. "]", warnings, depth + 1)
        if not ok then return false, err end
      end
    end
  end
  if condition["not"] ~= nil then
    local ok, err = validateV2Condition(condition["not"], path .. ".not", warnings, depth + 1)
    if not ok then return false, err end
  end
  if condition.op ~= nil then
    if type(condition.op) ~= "string" then return false, path .. ".op phải là chuỗi" end
    local op = trim(condition.op):lower():gsub("[- ]", "_")
    if op ~= "" and not V2_CONDITION_OPS[op] then return false, path .. ".op không được hỗ trợ: " .. tostring(condition.op) end
  end
  return true
end

local function validateV2OnError(policy, path, warnings, depth)
  if policy == nil then return true end
  if type(policy) == "string" then
    local mode = trim(policy):lower()
    if mode ~= "continue" and mode ~= "stop" and mode ~= "rethrow" then return false, path .. " chỉ hỗ trợ continue, stop hoặc rethrow" end
    return true
  end
  if type(policy) ~= "table" then return false, path .. " phải là chuỗi hoặc table" end
  allowedKeyWarnings(policy, path, V2_ON_ERROR_KEYS, warnings)
  local mode = trim(policy.mode or policy.action):lower()
  if mode ~= "" and mode ~= "continue" and mode ~= "stop" and mode ~= "rethrow" and mode ~= "fallback" then
    return false, path .. ".mode không được hỗ trợ"
  end
  if policy.set ~= nil and type(policy.set) ~= "table" then return false, path .. ".set phải là table" end
  local fallback = policy.fallback or policy.steps
  if fallback ~= nil then
    local ok, err = validateV2Steps(fallback, path .. ".fallback", warnings, (depth or 0) + 1)
    if not ok then return false, err end
  end
  return true
end

validateV2Steps = function(steps, path, warnings, depth)
  depth = tonumber(depth or 0) or 0
  if depth > 8 then return false, path .. " lồng quá sâu (tối đa 8 cấp)" end
  if type(steps) ~= "table" or not isArray(steps) then return false, path .. " phải là danh sách" end
  for i, step in ipairs(steps) do
    local stepPath = path .. "[" .. tostring(i) .. "]"
    if type(step) ~= "table" then return false, stepPath .. " phải là table" end
    allowedKeyWarnings(step, stepPath, V2_STEP_KEYS, warnings)
    local operationCount = 0
    for _, key in ipairs({"request","set","append","foreach","for_each","storage","crypto","assert","sleep","log","transform","if_","condition","call_pipeline","hook","browser"}) do if step[key] ~= nil then operationCount = operationCount + 1 end end
    if operationCount ~= 1 then return false, stepPath .. " phải có đúng một thao tác pipeline" end
    if step.request ~= nil then
      local ok, err = validateV2Request(step.request, stepPath .. ".request", warnings)
      if not ok then return false, err end
    end
    if step.set ~= nil and type(step.set) ~= "table" then return false, stepPath .. ".set phải là table" end
    if step.append ~= nil then
      if type(step.append) ~= "table" then return false, stepPath .. ".append phải là table" end
      allowedKeyWarnings(step.append, stepPath .. ".append", V2_APPEND_KEYS, warnings)
      if trim(step.append.into or step.append.var) == "" then return false, stepPath .. ".append cần into" end
      if step.append.flatten ~= nil and type(step.append.flatten) ~= "boolean" then return false, stepPath .. ".append.flatten phải là boolean" end
    end
    if step.storage ~= nil then
      if type(step.storage) ~= "table" then return false, stepPath .. ".storage phải là table" end
      allowedKeyWarnings(step.storage, stepPath .. ".storage", V2_STORAGE_KEYS, warnings)
      local op = trim(step.storage.op or step.storage.operation):lower()
      if op ~= "" and op ~= "get" and op ~= "set" and op ~= "remove" and op ~= "clear" then return false, stepPath .. ".storage.op không được hỗ trợ" end
      local scope = trim(step.storage.scope):lower()
      if scope ~= "" and scope ~= "local" and scope ~= "cache" then return false, stepPath .. ".storage.scope chỉ hỗ trợ local hoặc cache" end
      if step.storage.parse ~= nil and trim(step.storage.parse):lower() ~= "json" then return false, stepPath .. ".storage.parse hiện chỉ hỗ trợ json" end
      if step.storage.json ~= nil and type(step.storage.json) ~= "boolean" then return false, stepPath .. ".storage.json phải là boolean" end
      if step.storage.into ~= nil and type(step.storage.into) ~= "string" then return false, stepPath .. ".storage.into phải là chuỗi" end
    end
    if step.crypto ~= nil then
      if type(step.crypto) ~= "table" then return false, stepPath .. ".crypto phải là table" end
      allowedKeyWarnings(step.crypto, stepPath .. ".crypto", V2_CRYPTO_KEYS, warnings)
      local alg = trim(step.crypto.algorithm or step.crypto.alg):lower():gsub("[-_]", "")
      local allowedCrypto = {md5=true,sha1=true,sha256=true,sha512=true,hmacmd5=true,hmacsha1=true,hmacsha256=true,hmacsha512=true}
      if alg ~= "" and not allowedCrypto[alg] then return false, stepPath .. ".crypto.algorithm không được hỗ trợ" end
      local encoding = trim(step.crypto.encoding):lower()
      if encoding ~= "" and encoding ~= "hex" and encoding ~= "base64" then return false, stepPath .. ".crypto.encoding chỉ hỗ trợ hex hoặc base64" end
      if step.crypto.into ~= nil and type(step.crypto.into) ~= "string" then return false, stepPath .. ".crypto.into phải là chuỗi" end
    end
    local loop = step.foreach or step.for_each
    if loop ~= nil then
      if type(loop) ~= "table" then return false, stepPath .. ".foreach phải là table" end
      allowedKeyWarnings(loop, stepPath .. ".foreach", V2_FOREACH_KEYS, warnings)
      if loop.range ~= nil and type(loop.range) ~= "table" then return false, stepPath .. ".foreach.range phải là table" end
      if loop.max ~= nil and tonumber(loop.max) == nil and not isDynamicString(loop.max) then return false, stepPath .. ".foreach.max phải là số, reference hoặc template" end
      if loop.flatten ~= nil and type(loop.flatten) ~= "boolean" then return false, stepPath .. ".foreach.flatten phải là boolean" end
      for _, key in ipairs({"into","as","index_as"}) do if loop[key] ~= nil and type(loop[key]) ~= "string" then return false, stepPath .. ".foreach." .. key .. " phải là chuỗi" end end
      if loop.steps ~= nil then
        local ok, err = validateV2Steps(loop.steps, stepPath .. ".foreach.steps", warnings, depth + 1)
        if not ok then return false, err end
      end
      if trim(loop.into) == "" and loop.collect ~= nil then pushWarning(warnings, "FOREACH_COLLECT_WITHOUT_INTO", stepPath, "foreach.collect không có tác dụng nếu thiếu into.") end
    end
    if step.transform ~= nil then
      if type(step.transform) ~= "table" then return false, stepPath .. ".transform phải là table" end
      allowedKeyWarnings(step.transform, stepPath .. ".transform", V2_TRANSFORM_KEYS, warnings)
      local rawOps = step.transform.operations or step.transform.ops
      if rawOps == nil and (step.transform.op ~= nil or step.transform.operation ~= nil) then rawOps = {step.transform} end
      if rawOps == nil then return false, stepPath .. ".transform cần op hoặc operations" end
      if type(rawOps) ~= "table" or not isArray(rawOps) then return false, stepPath .. ".transform.operations phải là danh sách" end
      for opIndex, opSpec in ipairs(rawOps) do
        if type(opSpec) == "table" then allowedKeyWarnings(opSpec, stepPath .. ".transform.operations[" .. tostring(opIndex) .. "]", V2_TRANSFORM_KEYS, warnings) end
        local opName = type(opSpec) == "string" and opSpec or (type(opSpec) == "table" and (opSpec.op or opSpec.operation) or nil)
        if type(opName) ~= "string" or trim(opName) == "" then return false, stepPath .. ".transform.operations[" .. tostring(opIndex) .. "] thiếu op" end
        local normalizedOp = trim(opName):lower():gsub("[- ]", "_")
        if not V2_TRANSFORM_OPS[normalizedOp] then return false, stepPath .. ".transform operation không được hỗ trợ: " .. tostring(opName) end
        if type(opSpec) == "table" and opSpec.condition ~= nil then
          local ok, err = validateV2Condition(opSpec.condition, stepPath .. ".transform.operations[" .. tostring(opIndex) .. "].condition", warnings, depth + 1)
          if not ok then return false, err end
        end
      end
    end
    local branch = step.if_ or step.condition
    if branch ~= nil then
      if type(branch) ~= "table" then return false, stepPath .. ".if_ phải là table" end
      local conditionValue = branch.condition ~= nil and branch.condition or branch["when"]
      if conditionValue == nil then return false, stepPath .. ".if_ cần condition" end
      local ok, err = validateV2Condition(conditionValue, stepPath .. ".if_.condition", warnings, depth + 1)
      if not ok then return false, err end
      for _, pair in ipairs({{"then_steps", "then_steps"}, {"then", "then"}, {"else_steps", "else_steps"}, {"else_", "else_"}}) do
        local branchSteps = branch[pair[1]]
        if branchSteps ~= nil then
          local ok2, err2 = validateV2Steps(branchSteps, stepPath .. ".if_." .. pair[2], warnings, depth + 1)
          if not ok2 then return false, err2 end
        end
      end
    end
    if step.call_pipeline ~= nil then
      if type(step.call_pipeline) ~= "string" and type(step.call_pipeline) ~= "table" then return false, stepPath .. ".call_pipeline phải là chuỗi hoặc table" end
      if type(step.call_pipeline) == "table" and trim(step.call_pipeline.name or step.call_pipeline.pipeline) == "" then return false, stepPath .. ".call_pipeline cần name" end
    end
    if step.hook ~= nil then
      if type(step.hook) ~= "string" and type(step.hook) ~= "table" then return false, stepPath .. ".hook phải là chuỗi hoặc table" end
      if type(step.hook) == "table" then
        allowedKeyWarnings(step.hook, stepPath .. ".hook", V2_HOOK_KEYS, warnings)
        if trim(step.hook.name) == "" then return false, stepPath .. ".hook cần name" end
        local hookContext=step.hook.context~=nil and step.hook.context or step.hook.context_vars
        if hookContext~=nil and type(hookContext)~="table" and type(hookContext)~="string" and type(hookContext)~="boolean" then return false, stepPath .. ".hook.context phải là danh sách, table cấu hình, chuỗi none hoặc false" end
      end
    end
    if step.assert ~= nil then
      if type(step.assert) ~= "table" then return false, stepPath .. ".assert phải là table" end
      allowedKeyWarnings(step.assert, stepPath .. ".assert", V2_ASSERT_KEYS, warnings)
      if step.assert.condition ~= nil then
        local ok, err = validateV2Condition(step.assert.condition, stepPath .. ".assert.condition", warnings, depth + 1)
        if not ok then return false, err end
      end
    end
    if step.browser ~= nil then
      local ok, err = validateV2Browser(step.browser, stepPath .. ".browser", warnings)
      if not ok then return false, err end
      if type(step.browser.fetch) == "table" then
        allowedKeyWarnings(step.browser.fetch, stepPath .. ".browser.fetch", V2_BROWSER_FETCH_KEYS, warnings)
        if step.browser.fetch.method ~= nil and type(step.browser.fetch.method) ~= "string" then return false, stepPath .. ".browser.fetch.method phải là chuỗi" end
        if step.browser.fetch.headers ~= nil and type(step.browser.fetch.headers) ~= "table" then return false, stepPath .. ".browser.fetch.headers phải là table" end
        for _, key in ipairs({"timeout","retries"}) do
          local value = step.browser.fetch[key]
          if value ~= nil and type(value) ~= "number" and not (type(value) == "string" and (tonumber(value) ~= nil or isDynamicString(value))) then return false, stepPath .. ".browser.fetch." .. key .. " phải là số hoặc template" end
        end
        if step.browser.fetch.allow_http_error ~= nil and type(step.browser.fetch.allow_http_error) ~= "boolean" then return false, stepPath .. ".browser.fetch.allow_http_error phải là boolean" end
      end
    end
    if step["when"] ~= nil then
      local ok, err = validateV2Condition(step["when"], stepPath .. ".when", warnings, depth + 1)
      if not ok then return false, err end
    end
    if step.on_error ~= nil then
      local ok, err = validateV2OnError(step.on_error, stepPath .. ".on_error", warnings, depth + 1)
      if not ok then return false, err end
    end
  end
  return true
end

local function stepsContainForeach(steps)
  for _,step in ipairs(type(steps)=="table" and steps or {}) do
    if step.foreach~=nil or step.for_each~=nil then return true end
    local loop=step.foreach or step.for_each
    if type(loop)=="table" and stepsContainForeach(loop.steps) then return true end
    local branch=step.if_ or step.condition
    if type(branch)=="table" then
      if stepsContainForeach(branch.then_steps or branch["then"]) or stepsContainForeach(branch.else_steps or branch["else"]) then return true end
    end
  end
  return false
end

local function validateV2Action(action, name, warnings)
  local path = "actions." .. tostring(name)
  if type(action) ~= "table" then return false, path .. " phải là table" end
  allowedKeyWarnings(action, path, V2_ACTION_KEYS, warnings)
  if action.request ~= nil then
    local ok, err = validateV2Request(action.request, path .. ".request", warnings)
    if not ok then return false, err end
  end
  if action.steps ~= nil then
    local ok, err = validateV2Steps(action.steps, path .. ".steps", warnings, 0)
    if not ok then return false, err end
  end
  if action.on_error ~= nil then
    local ok, err = validateV2OnError(action.on_error, path .. ".on_error", warnings, 0)
    if not ok then return false, err end
  end
  if type(action.result) ~= "table" then return false, path .. " cần result" end
  if tostring(name)=="chapters" and stepsContainForeach(action.steps) then
    pushWarning(warnings,"CHAPTERS_EAGER_PAGINATION",path..".steps","Danh sách chương đang dùng foreach. Với truyện dài, nên trả result.next để ứng dụng tải từng trang khi người dùng cuộn thay vì tải toàn bộ mục lục trong một lần.")
  end
  return validateV2Result(action.result, path .. ".result", warnings)
end

local function validateV2References(source)
  local pipelines = type(source.native_pipelines) == "table" and source.native_pipelines or {}
  local hooks = type(source.native_hooks) == "table" and source.native_hooks or {}
  local function walkSteps(steps, path)
    for i, step in ipairs(type(steps) == "table" and steps or {}) do
      local stepPath = path .. "[" .. tostring(i) .. "]"
      if step.hook ~= nil then
        local name = type(step.hook) == "string" and step.hook or step.hook.name
        if type(name) == "string" and not isDynamicString(name) and type(hooks[name]) ~= "function" then
          return false, stepPath .. ".hook tham chiếu hook không tồn tại: " .. tostring(name)
        end
      end
      if step.call_pipeline ~= nil then
        local name = type(step.call_pipeline) == "string" and step.call_pipeline or (step.call_pipeline.name or step.call_pipeline.pipeline)
        if type(name) == "string" and not isDynamicString(name) and pipelines[name] == nil then
          return false, stepPath .. ".call_pipeline tham chiếu pipeline không tồn tại: " .. tostring(name)
        end
      end
      local loop = step.foreach or step.for_each
      if type(loop) == "table" then
        local ok, err = walkSteps(loop.steps, stepPath .. ".foreach.steps")
        if not ok then return false, err end
      end
      local branch = step.if_ or step.condition
      if type(branch) == "table" then
        for _, pair in ipairs({{"then_steps", "then_steps"}, {"then", "then"}, {"else_steps", "else_steps"}, {"else_", "else_"}}) do
          local ok, err = walkSteps(branch[pair[1]], stepPath .. ".if_." .. pair[2])
          if not ok then return false, err end
        end
      end
      local policy = step.on_error
      if type(policy) == "table" then
        local ok, err = walkSteps(policy.fallback or policy.steps, stepPath .. ".on_error.fallback")
        if not ok then return false, err end
      end
    end
    return true
  end
  for name, action in pairs(type(source.native_actions) == "table" and source.native_actions or {}) do
    local ok, err = walkSteps(action.steps, "actions." .. tostring(name) .. ".steps")
    if not ok then return false, err end
    if type(action.on_error) == "table" then
      ok, err = walkSteps(action.on_error.fallback or action.on_error.steps, "actions." .. tostring(name) .. ".on_error.fallback")
      if not ok then return false, err end
    end
  end
  for name, pipeline in pairs(pipelines) do
    local steps = isArray(pipeline) and pipeline or pipeline.steps
    local ok, err = walkSteps(steps, "source.pipelines." .. tostring(name) .. ".steps")
    if not ok then return false, err end
    if type(pipeline) == "table" and not isArray(pipeline) and type(pipeline.on_error) == "table" then
      ok, err = walkSteps(pipeline.on_error.fallback or pipeline.on_error.steps, "source.pipelines." .. tostring(name) .. ".on_error.fallback")
      if not ok then return false, err end
    end
  end
  return true
end


local function validateUiVisibilityValue(value, path)
  if value == nil or type(value) == "boolean" then return true end
  if type(value) ~= "string" then return false, path .. " phải là boolean hoặc chuỗi trạng thái" end
  local mode=trim(value):lower():gsub("[- ]","_")
  local allowed={auto=true,["default"]=true,visible=true,show=true,on=true,enabled=true,hidden=true,hide=true,off=true,none=true,disabled=true,["true"]=true,["false"]=true}
  if not allowed[mode] then return false,path.." có trạng thái không hỗ trợ: "..tostring(value) end
  return true
end

local function validateUiActionList(list, path, warnings)
  if list == nil then return true end
  if type(list) ~= "table" or not isArray(list) then return false, path .. " phải là danh sách action" end
  if #list > 24 then return false, path .. " chỉ hỗ trợ tối đa 24 action" end
  local allowedKinds={builtin=true,load=true,open_url=true,external=true,toast=true,reload=true,category=true}
  local seenIds={}
  for i,item in ipairs(list) do
    local p=path.."["..tostring(i).."]"
    if type(item)~="table" then return false,p.." phải là table" end
    allowedKeyWarnings(item,p,{id=true,label=true,content_description=true,description=true,type=true,action=true,command=true,builtin=true,url=true,target=true,state=true,message=true,color=true,text_color=true,visible=true,enabled=true,contexts=true,confirm=true},warnings)
    local label=trim(item.label)
    if label=="" then return false,p..".label không được rỗng" end
    if #label>120 then return false,p..".label quá dài" end
    local id=trim(item.id)
    if id~="" then
      if seenIds[id] then return false,p..".id bị trùng trong cùng surface: "..id end
      seenIds[id]=true
    end
    local kind=trim(item.type or item.action):lower():gsub("[- ]","_")
    if kind=="" then kind="builtin" end
    if not allowedKinds[kind] then return false,p..".type không hỗ trợ: "..tostring(kind) end
    for _,k in ipairs({"id","content_description","description","command","builtin","url","target","state","message","color","text_color","confirm"}) do
      if item[k]~=nil and type(item[k])~="string" then return false,p.."."..k.." phải là chuỗi" end
    end
    if item.contexts~=nil then
      local ok,err=validateStringList(item.contexts,p..".contexts",true)
      if not ok then return false,err end
    end
    for _,k in ipairs({"visible","enabled"}) do if item[k]~=nil and type(item[k])~="boolean" then return false,p.."."..k.." phải là boolean" end end
  end
  return true
end

local function validateUiSpec(ui, warnings)
  if ui==nil then return true end
  if type(ui)~="table" then return false,"source.ui phải là table" end
  allowedKeyWarnings(ui,"source.ui",{accessibility=true,explore=true,chrome=true,labels=true,actions=true},warnings)

  if ui.accessibility~=nil then
    if type(ui.accessibility)~="table" then return false,"source.ui.accessibility phải là table" end
    allowedKeyWarnings(ui.accessibility,"source.ui.accessibility",{selected_category=true,selected_category_mode=true,selected_suffix=true},warnings)
    local mode=trim(ui.accessibility.selected_category or ui.accessibility.selected_category_mode):lower():gsub("[- ]","_")
    if mode~="" and mode~="announce" and mode~="label_only" and mode~="hidden" and mode~="hide" and mode~="silent" then return false,"source.ui.accessibility.selected_category không hợp lệ" end
    if ui.accessibility.selected_suffix~=nil and type(ui.accessibility.selected_suffix)~="string" then return false,"source.ui.accessibility.selected_suffix phải là chuỗi" end
  end

  if ui.explore~=nil then
    if type(ui.explore)~="table" then return false,"source.ui.explore phải là table" end
    allowedKeyWarnings(ui.explore,"source.ui.explore",{home_shortcuts=true,show_source_selector=true,show_category_bar=true},warnings)
    local hs=ui.explore.home_shortcuts
    if hs~=nil and type(hs)~="string" and type(hs)~="table" then return false,"source.ui.explore.home_shortcuts phải là chuỗi hoặc table" end
    if type(hs)=="table" then
      allowedKeyWarnings(hs,"source.ui.explore.home_shortcuts",{latest=true,stories=true},warnings)
      for _,k in ipairs({"latest","stories"}) do if hs[k]~=nil and type(hs[k])~="boolean" then return false,"source.ui.explore.home_shortcuts."..k.." phải là boolean" end end
    end
    for _,k in ipairs({"show_source_selector","show_category_bar"}) do local ok,err=validateUiVisibilityValue(ui.explore[k],"source.ui.explore."..k); if not ok then return false,err end end
  end

  if ui.chrome~=nil then
    if type(ui.chrome)~="table" then return false,"source.ui.chrome phải là table" end
    allowedKeyWarnings(ui.chrome,"source.ui.chrome",{search_button=true,diagnostics=true,story_header=true,story_tab_bar=true,content_options=true,story_tabs=true,story_actions=true,reader_controls=true},warnings)
    for _,k in ipairs({"search_button","diagnostics","story_header","story_tab_bar","content_options"}) do local ok,err=validateUiVisibilityValue(ui.chrome[k],"source.ui.chrome."..k); if not ok then return false,err end end
    local groups={story_tabs={intro=true,chapters=true,comments=true,source=true},story_actions={read_first=true,download=true,options=true},reader_controls={prev=true,rewind=true,play=true,forward=true,next=true}}
    for group,keys in pairs(groups) do
      local value=ui.chrome[group]
      if value~=nil then
        if type(value)~="table" then return false,"source.ui.chrome."..group.." phải là table" end
        allowedKeyWarnings(value,"source.ui.chrome."..group,keys,warnings)
        for k in pairs(keys) do local ok,err=validateUiVisibilityValue(value[k],"source.ui.chrome."..group.."."..k); if not ok then return false,err end end
      end
    end
  end

  if ui.labels~=nil then
    if type(ui.labels)~="table" then return false,"source.ui.labels phải là table" end
    local allowedLabels={source_selector=true,search=true,content_options=true,story_intro=true,story_chapters=true,story_comments=true,story_source=true,download_story=true,story_options=true,reader_prev=true,reader_rewind=true,reader_forward=true,reader_next=true}
    allowedKeyWarnings(ui.labels,"source.ui.labels",allowedLabels,warnings)
    for k,v in pairs(ui.labels) do if type(k)=="string" and v~=nil and type(v)~="string" then return false,"source.ui.labels."..k.." phải là chuỗi" end end
  end

  if ui.actions~=nil then
    if type(ui.actions)~="table" then return false,"source.ui.actions phải là table" end
    allowedKeyWarnings(ui.actions,"source.ui.actions",{explore=true,list=true,story=true,reader=true},warnings)
    for _,surface in ipairs({"explore","list","story","reader"}) do
      local ok,err=validateUiActionList(ui.actions[surface],"source.ui.actions."..surface,warnings)
      if not ok then return false,err end
    end
  end
  return true
end
local function validateV2NormalizedSource(source, warnings)
  local actions = source.native_actions
  if type(actions) ~= "table" then return false, "Native Source API 2 thiếu source.actions", warnings end
  allowedKeyWarnings(actions, "actions", {categories=true,stories=true,latest=true,search=true,detail=true,chapters=true,content=true,comments=true}, warnings)
  if not actions.search and not actions.stories and not actions.latest then return false, "Native Source API 2 cần ít nhất actions.search, actions.stories hoặc actions.latest", warnings end
  if type(actions.chapters) ~= "table" then return false, "Native Source API 2 cần actions.chapters", warnings end
  if type(actions.content) ~= "table" then return false, "Native Source API 2 cần actions.content", warnings end
  if not actions.detail then pushWarning(warnings, "DETAIL_ACTION_MISSING", "actions.detail", "Nguồn vẫn chạy được nhưng trang giới thiệu sẽ chỉ có dữ liệu tối thiểu.") end
  for name, action in pairs(actions) do
    if type(name) == "string" then
      local ok, err = validateV2Action(action, name, warnings)
      if not ok then return false, err, warnings end
    end
  end
  local pipelines = source.native_pipelines
  if pipelines ~= nil then
    if type(pipelines) ~= "table" or (next(pipelines) ~= nil and isArray(pipelines)) then return false, "source.pipelines phải là table ánh xạ tên pipeline", warnings end
    for name, pipeline in pairs(pipelines) do
      if type(name) ~= "string" or trim(name) == "" then return false, "source.pipelines có tên không hợp lệ", warnings end
      local steps = pipeline
      if type(pipeline) == "table" and not isArray(pipeline) then
        allowedKeyWarnings(pipeline, "source.pipelines." .. name, V2_PIPELINE_KEYS, warnings)
        steps = pipeline.steps
        if pipeline.on_error ~= nil then
          local ok, err = validateV2OnError(pipeline.on_error, "source.pipelines." .. name .. ".on_error", warnings, 0)
          if not ok then return false, err, warnings end
        end
      end
      local ok, err = validateV2Steps(steps, "source.pipelines." .. name .. ".steps", warnings, 0)
      if not ok then return false, err, warnings end
    end
  end
  local hooks = source.native_hooks
  if hooks ~= nil then
    if type(hooks) ~= "table" or (next(hooks) ~= nil and isArray(hooks)) then return false, "source.hooks phải là table ánh xạ tên hook", warnings end
    for name, hook in pairs(hooks) do
      if type(name) ~= "string" or trim(name) == "" then return false, "source.hooks có tên không hợp lệ", warnings end
      if type(hook) ~= "function" then return false, "source.hooks." .. tostring(name) .. " phải là function Lua", warnings end
    end
  end
  local uiOk,uiErr=validateUiSpec(source.native_ui,warnings)
  if not uiOk then return false,uiErr,warnings end
  local refsOk, refsErr = validateV2References(source)
  if not refsOk then return false, refsErr, warnings end
  if source.allowed_hosts ~= nil then
    if type(source.allowed_hosts) ~= "table" or not isArray(source.allowed_hosts) then return false, "source.allowed_hosts phải là danh sách host", warnings end
    for i, host in ipairs(source.allowed_hosts) do
      if type(host) ~= "string" or trim(host) == "" then return false, "source.allowed_hosts[" .. tostring(i) .. "] phải là chuỗi host không rỗng", warnings end
    end
  end
  if source.native_config ~= nil then
    if type(source.native_config) ~= "table" then return false, "source.config phải là table", warnings end
    for key, descriptor in pairs(source.native_config) do
      if type(key) ~= "string" or trim(key) == "" then return false, "source.config chỉ chấp nhận key chuỗi không rỗng", warnings end
      if V2_RESERVED_CONFIG_KEYS[key] then return false, "source.config." .. tostring(key) .. " trùng tên API hệ thống và không được phép", warnings end
      if type(descriptor) ~= "table" then return false, "source.config." .. tostring(key) .. " phải là table", warnings end
      allowedKeyWarnings(descriptor, "source.config." .. tostring(key), {title=true,subtitle=true,["default"]=true,values=true,mode=true,format=true}, warnings)
      if descriptor.values ~= nil then
        local ok, err = validateStringList(descriptor.values, "source.config." .. tostring(key) .. ".values", true)
        if not ok then return false, err, warnings end
      end
    end
  end
  if source.native_permissions ~= nil then
    if type(source.native_permissions) ~= "table" then return false, "source.permissions phải là table", warnings end
    allowedKeyWarnings(source.native_permissions, "source.permissions", {browser=true,storage=true,network_capture=true,hosts=true}, warnings)
    if source.native_permissions.browser ~= nil and type(source.native_permissions.browser) ~= "boolean" then return false, "source.permissions.browser phải là boolean", warnings end
    if source.native_permissions.storage ~= nil and type(source.native_permissions.storage) ~= "boolean" then return false, "source.permissions.storage phải là boolean", warnings end
    if source.native_permissions.network_capture ~= nil and type(source.native_permissions.network_capture) ~= "boolean" then return false, "source.permissions.network_capture phải là boolean", warnings end
    if source.native_permissions.network_capture == true and source.native_permissions.browser ~= true then return false, "permissions.network_capture yêu cầu permissions.browser = true", warnings end
    if source.native_permissions.hosts ~= nil then
      local ok, err = validateStringList(source.native_permissions.hosts, "source.permissions.hosts", false)
      if not ok then return false, err, warnings end
    end
  end
  local permissions = type(source.native_permissions) == "table" and source.native_permissions or {}
  local usesBrowser, usesCapture, usesStorage = false, false, false
  local function scanSteps(steps)
    if type(steps) ~= "table" then return end
    for _, step in ipairs(steps) do
      if type(step) == "table" then
        if step.storage ~= nil then usesStorage = true end
        if type(step.request) == "table" and trim(step.request.transport):lower() == "browser" then
          usesBrowser = true
          if step.request.wait_request ~= nil then usesCapture = true end
        end
        if type(step.browser) == "table" then
          usesBrowser = true
          local op = trim(step.browser.op or step.browser.operation):lower():gsub("[- ]", "_")
          if op == "wait_request" or op == "capture" or op == "requests" or op == "urls" or step.browser.wait_request ~= nil or step.browser.fetch ~= nil then usesCapture = true end
        end
        local loop = step.foreach or step.for_each
        if type(loop) == "table" then scanSteps(loop.steps) end
        local branch = step.if_ or step.condition
        if type(branch) == "table" then scanSteps(branch.then_steps or branch["then"]); scanSteps(branch.else_steps or branch.else_) end
        if type(step.on_error) == "table" then scanSteps(step.on_error.fallback or step.on_error.steps) end
      end
    end
  end
  for _, action in pairs(actions) do if type(action) == "table" then if type(action.request) == "table" and trim(action.request.transport):lower() == "browser" then usesBrowser = true; if action.request.wait_request ~= nil then usesCapture = true end end; scanSteps(action.steps); if type(action.on_error) == "table" then scanSteps(action.on_error.fallback or action.on_error.steps) end end end
  for _, pipeline in pairs(type(source.native_pipelines) == "table" and source.native_pipelines or {}) do
    local isObjectPipeline = type(pipeline) == "table" and not isArray(pipeline)
    local steps = isObjectPipeline and pipeline.steps or pipeline
    scanSteps(steps)
    if isObjectPipeline and type(pipeline.on_error) == "table" then scanSteps(pipeline.on_error.fallback or pipeline.on_error.steps) end
  end
  if usesBrowser and permissions.browser ~= true then return false, "Nguồn sử dụng Browser nhưng chưa khai báo permissions.browser = true", warnings end
  if usesCapture and permissions.network_capture ~= true then return false, "Nguồn sử dụng network capture nhưng chưa khai báo permissions.network_capture = true", warnings end
  if usesStorage and permissions.storage ~= true then return false, "Nguồn sử dụng Storage nhưng chưa khai báo permissions.storage = true", warnings end
  if permissions.browser == true and not usesBrowser then pushWarning(warnings, "UNUSED_PERMISSION", "source.permissions.browser", "Nguồn cấp quyền Browser nhưng pipeline hiện không sử dụng Browser.") end
  if permissions.network_capture == true and not usesCapture then pushWarning(warnings, "UNUSED_PERMISSION", "source.permissions.network_capture", "Nguồn cấp quyền network capture nhưng pipeline hiện không sử dụng capture.") end
  if permissions.storage == true and not usesStorage then pushWarning(warnings, "UNUSED_PERMISSION", "source.permissions.storage", "Nguồn cấp quyền Storage nhưng pipeline hiện không sử dụng Storage.") end
  return true, nil, warnings
end

function NativeApi.validateNormalizedSource(source, metadata)
  local warnings = {}
  if type(source) ~= "table" then return false, "Native Source source phải là table", warnings end
  metadata = type(metadata) == "table" and metadata or {}
  local api = tonumber(source.native_api_version or 0) or 0
  if api ~= NativeApi.CURRENT_VERSION then
    return false, "Native Source chỉ hỗ trợ API v" .. tostring(NativeApi.CURRENT_VERSION) .. "; nguồn yêu cầu v" .. tostring(api), warnings
  end
  local base = trim(source.base_url or source.home_url)
  if base == "" or not base:match("^https?://") then return false, "Native Source cần base_url hoặc home_url HTTP/HTTPS hợp lệ", warnings end
  local idOk, idErr = NativeApi.validateId(metadata.id)
  if not idOk then return false, idErr, warnings end

  local ok, err = validateV2NormalizedSource(source, warnings)
  if not ok then return false, err, warnings end
  for _, key in ipairs({"home_url","latest_url","search_url"}) do
    local value = trim(source[key])
    if value ~= "" and not value:match("^https?://") then return false, key .. " không hợp lệ", warnings end
  end
  return true, nil, warnings
end

function NativeApi.validatePackage(package, options)
  options = options or {}
  local warnings = {}
  if type(package) ~= "table" or type(package.source) ~= "table" then return false, "Gói Native Source phải có source", warnings end
  allowedKeyWarnings(package, "package", {api_version=true,native_source_api=true,metadata=true,source=true,name=true}, warnings)
  local api = tonumber(package.api_version or package.native_source_api or package.source.api_version or 0) or 0
  if not NativeApi.isSupportedVersion(api) then
    return false, "Native Source API yêu cầu v" .. tostring(api) .. ", ứng dụng hỗ trợ " .. NativeApi.supportedVersionText(), warnings
  end
  local metadata = type(package.metadata) == "table" and package.metadata or {}
  allowedKeyWarnings(metadata, "metadata", {
    id=true,name=true,version=true,author=true,website=true,description=true,updated=true,
    locale=true,regexp=true,nsfw=true,type=true,
    min_app_version=true,checksum=true,update_sha256=true,update_url=true,
    repository_url=true,repository_name=true,
  }, warnings)
  local source = package.source
  local name = trim(metadata.name or package.name or source.name)
  if name == "" then return false, "Native Source thiếu metadata.name", warnings end
  local base = trim(source.base_url or source.home_url or metadata.website)
  if base == "" then return false, "Native Source thiếu source.base_url", warnings end
  if trim(metadata.id) == "" then return false, "Native Source bắt buộc khai báo metadata.id cố định", warnings end
  local id, _, _, idErr = NativeApi.ensureMetadataId(metadata, name, base, warnings)
  if not id then return false, idErr, warnings end

  allowedKeyWarnings(source, "source", {
    name=true, api_version=true, base_url=true, home_url=true, latest_url=true,
    search_url=true, allowed_hosts=true,
    actions=true, config=true, permissions=true, pipelines=true, hooks=true, ui=true,
  }, warnings)
  if api ~= NativeApi.CURRENT_VERSION then
    return false, "Native Source chỉ hỗ trợ api_version = " .. tostring(NativeApi.CURRENT_VERSION), warnings
  end
  if type(source.actions) ~= "table" then return false, "Native Source API 2 thiếu source.actions", warnings end
  if source.config ~= nil and type(source.config) ~= "table" then return false, "source.config phải là table", warnings end
  if source.permissions ~= nil and type(source.permissions) ~= "table" then return false, "source.permissions phải là table", warnings end
  if source.pipelines ~= nil and type(source.pipelines) ~= "table" then return false, "source.pipelines phải là table", warnings end
  if source.hooks ~= nil and type(source.hooks) ~= "table" then return false, "source.hooks phải là table", warnings end
  if source.ui ~= nil and type(source.ui) ~= "table" then return false, "source.ui phải là table", warnings end
  local normalized={
    native_api_version=api,
    base_url=base,
    home_url=source.home_url,
    latest_url=source.latest_url,
    search_url=source.search_url,
    allowed_hosts=source.allowed_hosts,
    native_actions=source.actions,
    native_config=source.config,
    native_permissions=source.permissions,
    native_pipelines=source.pipelines,
    native_hooks=source.hooks,
    native_ui=source.ui,
  }
  local fullOk,fullErr=validateV2NormalizedSource(normalized,warnings)
  if not fullOk then return false,fullErr,warnings end
  for _,key in ipairs({"home_url","latest_url","search_url"}) do
    local value=trim(source[key])
    if value~="" and not value:match("^https?://") then return false,key.." không hợp lệ",warnings end
  end
  return true, nil, warnings
end

function NativeApi.isInstructionLimitError(value)
  return text(value):find(NativeApi.INSTRUCTION_LIMIT_MARKER, 1, true) ~= nil
end


function NativeApi.createSandbox()
  local unpackFn = table.unpack or unpack
  local function pack(...) return {n=select("#",...),...} end
  local function cloneLibrary(lib, blocked)
    local out = {}
    for key, value in pairs(type(lib) == "table" and lib or {}) do
      if not (blocked and blocked[key]) then out[key] = value end
    end
    return out
  end
  local safeString = cloneLibrary(string, {dump=true})
  local safeTable = cloneLibrary(table)
  local safeMath = cloneLibrary(math, {randomseed=true})
  local safeBit32 = cloneLibrary(rawget(_G, "bit32"))
  local function guardedPcall(fn,...)
    local results=pack(pcall(fn,...))
    if not results[1] and NativeApi.isInstructionLimitError(results[2]) then error(results[2],0) end
    return unpackFn(results,1,results.n)
  end
  local function guardedXpcall(fn,handler,...)
    local args=pack(...)
    local function wrapped() return fn(unpackFn(args,1,args.n)) end
    local function guardedHandler(err)
      if NativeApi.isInstructionLimitError(err) then error(err,0) end
      return handler(err)
    end
    return xpcall(wrapped,guardedHandler)
  end
  return {
    assert=assert,error=error,pairs=pairs,ipairs=ipairs,next=next,
    tostring=tostring,tonumber=tonumber,type=type,select=select,
    pcall=guardedPcall,xpcall=guardedXpcall,string=safeString,table=safeTable,
    math=safeMath,bit32=safeBit32,
  }
end

function NativeApi.runChunkSafely(loader, options)
  options = options or {}
  if type(loader) ~= "function" then return false, nil, "Loader không hợp lệ" end
  local instructionLimit = tonumber(options.instructionLimit) or NativeApi.MAX_INSTRUCTIONS
  local granularity = math.max(100, tonumber(options.granularity) or NativeApi.HOOK_GRANULARITY)
  local debugLib = rawget(_G, "debug")
  if type(debugLib) ~= "table" or type(debugLib.sethook) ~= "function" then
    return false, nil, "Runtime Lua không có debug.sethook nên không thể chạy Native Source trong giới hạn an toàn", false
  end

  local oldHook, oldMask, oldCount = nil, nil, nil
  if type(debugLib.gethook) == "function" then pcall(function() oldHook, oldMask, oldCount = debugLib.gethook() end) end
  local used = 0
  local marker = NativeApi.INSTRUCTION_LIMIT_MARKER
  local function hook()
    used = used + granularity
    if used > instructionLimit then error(marker, 0) end
  end
  local hookOk, hookErr = pcall(function() debugLib.sethook(hook, "", granularity) end)
  if not hookOk then
    return false, nil, "Không kích hoạt được giới hạn thực thi Native Source: " .. tostring(hookErr), false, tostring(hookErr)
  end

  local ok, result = pcall(loader)
  pcall(function()
    if oldHook then debugLib.sethook(oldHook, oldMask or "", oldCount or 0) else debugLib.sethook() end
  end)
  if not ok then
    local err = tostring(result)
    if err:find(marker, 1, true) then return false, nil, "Native Source vượt giới hạn thực thi an toàn", true end
    return false, nil, err, true
  end
  return true, result, nil, true
end


function NativeApi.runHookSafely(fn, ...)
  if type(fn) ~= "function" then return false, nil, "Hook không tồn tại hoặc không phải function" end
  local args = { n = select("#", ...), ... }
  local unpackFn = table.unpack or unpack
  local instructionLimit = math.max(10000, math.min(NativeApi.MAX_INSTRUCTIONS, 150000))
  local granularity = math.max(100, NativeApi.HOOK_GRANULARITY)
  local debugLib = rawget(_G, "debug")
  local function invoke() return fn(unpackFn(args, 1, args.n)) end
  if type(debugLib) ~= "table" or type(debugLib.sethook) ~= "function" then
    return false, nil, "Runtime Lua không có debug.sethook nên không thể chạy Lua hook an toàn"
  end
  local oldHook, oldMask, oldCount = nil, nil, nil
  if type(debugLib.gethook) == "function" then pcall(function() oldHook, oldMask, oldCount = debugLib.gethook() end) end
  local used = 0
  local marker = NativeApi.INSTRUCTION_LIMIT_MARKER
  local function hook()
    used = used + granularity
    if used > instructionLimit then error(marker, 0) end
  end
  local hookOk = pcall(function() debugLib.sethook(hook, "", granularity) end)
  if not hookOk then
    return false, nil, "Không kích hoạt được giới hạn thực thi Lua hook"
  end
  local ok, result = pcall(invoke)
  pcall(function()
    if oldHook then debugLib.sethook(oldHook, oldMask or "", oldCount or 0) else debugLib.sethook() end
  end)
  if not ok then
    local err = tostring(result)
    if err:find(marker, 1, true) then return false, nil, "Lua hook vượt giới hạn thực thi an toàn" end
    return false, nil, err
  end
  return true, result, nil
end

return NativeApi
