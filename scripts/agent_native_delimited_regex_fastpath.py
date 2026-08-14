from pathlib import Path


def replace_exact(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match in {path}, found {count}")
    file.write_text(text.replace(old, new))


adapter = "source-lua/src/main/resources/vn/nghetruyen/source/lua/native_v2_adapter.lua"
replace_exact(
    adapter,
    '''function regexMatchText(value,pattern,flags){var re=cachedRegExp(pattern,flags);re.lastIndex=0;var result=String(value==null?"":value).match(re);re.lastIndex=0;return result}
function regexTestText(value,pattern,flags){var re=cachedRegExp(pattern,flags);re.lastIndex=0;var result=re.test(String(value==null?"":value));re.lastIndex=0;return result}''',
    '''var DELIMITED_FIELD_PATTERNS={
 "^[\\s\\S]*?-/-[\\s\\S]*?-/-\\s*([\\s\\S]*?)(?:-/-[\\s\\S]*)?$":3,
 "^[\\s\\S]*?-/-\\s*([^/]+?)\\s*-/-":2
};
function fastDelimitedRegexMatch(value,pattern,flags){
 if(flags||!own(DELIMITED_FIELD_PATTERNS,pattern))return null;
 var text=String(value==null?"":value),delimiter="-/-",first=text.indexOf(delimiter);if(first<0)return {handled:true,result:null};
 var second=text.indexOf(delimiter,first+delimiter.length);if(second<0)return {handled:true,result:null};
 var field=DELIMITED_FIELD_PATTERNS[pattern],start,end;
 if(field===2){start=first+delimiter.length;end=second}
 else{var third=text.indexOf(delimiter,second+delimiter.length);start=second+delimiter.length;end=third<0?text.length:third}
 var captured=text.slice(start,end).replace(/^\\s*/,"");
 if(field===2){captured=captured.replace(/\\s*$/,"");if(!captured||captured.indexOf("/")>=0)return {handled:true,result:null}}
 return {handled:true,result:[text,captured]};
}
function regexMatchText(value,pattern,flags){var fast=fastDelimitedRegexMatch(value,String(pattern),String(flags||""));if(fast&&fast.handled)return fast.result;var re=cachedRegExp(pattern,flags);re.lastIndex=0;var result=String(value==null?"":value).match(re);re.lastIndex=0;return result}
function regexTestText(value,pattern,flags){var re=cachedRegExp(pattern,flags);re.lastIndex=0;var result=re.test(String(value==null?"":value));re.lastIndex=0;return result}''',
)

test = "source-lua/src/test/kotlin/vn/nghetruyen/source/lua/SangTacVietTocBudgetRegressionTest.kt"
replace_exact(
    test,
    'assertTrue("expected last chapter in normalized output", encoded.contains("Chương 100"))',
    'assertTrue("expected last chapter in normalized output", encoded.contains("Chương 1000"))',
)

print("delimiter regex fast path applied")
