#!/usr/bin/env python3
"""Run every bundled Wattpad vBook action against deterministic replay snapshots.

This is deliberately independent from Android/Gradle. Node executes the actual
JavaScript files with a tiny host API, then Python applies the same normalized
contract used by VBookJsRuntime and compares expected JSON as a subset.
"""
from __future__ import annotations

import hashlib
import html
import json
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "examples/sourcepacks/wattpad"


def stable_id(raw: str) -> str:
    return hashlib.sha256(raw.encode("utf-8")).digest()[:12].hex()


def absolute(host: str | None, value: str | None) -> str | None:
    if not value:
        return None
    value = value.strip()
    if value.startswith(("http://", "https://")):
        return value
    if not host:
        return None
    return host.rstrip("/") + (value if value.startswith("/") else "/" + value)


def normalize_story(item: dict) -> dict:
    url = absolute(item.get("host"), item.get("link") or item.get("url"))
    assert url, "story URL missing"
    title = item.get("title") or item.get("name")
    assert title, "story title missing"
    return {
        "id": stable_id(url),
        "title": title,
        "author": item.get("author") or item.get("description") or "",
        "coverUrl": item.get("coverUrl") or item.get("cover"),
        "description": item.get("description") or "",
        "url": url,
    }


def normalize_chapter(item: dict, index: int, story_url: str) -> dict:
    url = absolute(item.get("host"), item.get("url") or item.get("link"))
    assert url, "chapter URL missing"
    return {
        "id": stable_id(url),
        "storyId": stable_id(story_url),
        "index": int(item.get("index", index)),
        "title": item.get("title") or item.get("name") or f"Chương {index + 1}",
        "url": url,
    }


def paragraphs_from_html(raw: str) -> list[str]:
    raw = re.sub(r"(?is)<script.*?</script>|<style.*?</style>", "", raw)
    pieces = re.findall(r"(?is)<(?:p|div)[^>]*>(.*?)</(?:p|div)>", raw)
    if not pieces:
        pieces = [raw]
    result: list[str] = []
    for piece in pieces:
        text = re.sub(r"(?i)<br\s*/?>", "\n", piece)
        text = re.sub(r"(?s)<[^>]+>", " ", text)
        text = html.unescape(text)
        text = re.sub(r"\s+", " ", text).strip()
        if text and text not in result:
            result.append(text)
    return result


def normalize(action: str, data, data2, input_obj: dict):
    if action in {"HOME", "GENRE", "SEARCH"}:
        items = data if isinstance(data, list) else data.get("items", []) if isinstance(data, dict) else []
        return {"items": [normalize_story(x) for x in items], "nextPageUrl": str(data2) if data2 not in (None, "") else None}
    if action == "SUGGESTIONS":
        items = data if isinstance(data, list) else data.get("items", []) if isinstance(data, dict) else []
        values = []
        for item in items:
            value = item if isinstance(item, str) else item.get("query") or item.get("title") or item.get("name") or item.get("text") or item.get("input")
            if value and value.strip().lower() not in {x.lower() for x in values}:
                values.append(value.strip())
        return {"items": values[:20], "nextPageUrl": str(data2) if data2 not in (None, "") else None}
    if action == "DETAIL":
        obj = data or {}
        url = absolute(obj.get("host"), obj.get("url") or obj.get("link") or input_obj.get("url")) or input_obj.get("url", "")
        genres = []
        for item in obj.get("genres", []):
            genres.append(item if isinstance(item, str) else item.get("title"))
        return {
            "id": stable_id(url), "title": obj.get("title") or obj.get("name"),
            "author": obj.get("author", ""), "coverUrl": obj.get("coverUrl") or obj.get("cover"),
            "description": obj.get("description", ""), "url": url, "genres": [x for x in genres if x],
            "status": obj.get("status") or ("Đang ra" if obj.get("ongoing") is True else "Hoàn thành"),
        }
    if action in {"TOC", "TOC_PAGES"}:
        items = data if isinstance(data, list) else data.get("chapters", data.get("items", [])) if isinstance(data, dict) else []
        return {"chapters": [normalize_chapter(x, i, input_obj.get("url", "")) for i, x in enumerate(items)], "nextPageUrl": str(data2) if data2 not in (None, "") else None}
    if action == "LATEST_CHAPTER":
        return normalize_chapter(data or {}, int((data or {}).get("index", 0)), input_obj.get("url", ""))
    if action == "CHAPTER":
        obj = data if isinstance(data, dict) else {}
        raw = data if isinstance(data, str) else obj.get("content") or obj.get("html") or ""
        url = input_obj.get("url", "")
        return {
            "id": stable_id(url), "storyId": "", "index": 0,
            "title": obj.get("title") or obj.get("name") or "Chương", "url": url,
            "paragraphs": paragraphs_from_html(raw),
            "previousChapterUrl": obj.get("previousChapterUrl"), "nextChapterUrl": obj.get("nextChapterUrl"),
        }
    return data


def subset(expected, actual, path="$" ) -> None:
    if isinstance(expected, dict):
        assert isinstance(actual, dict), f"{path}: expected object"
        for key, value in expected.items():
            assert key in actual, f"{path}.{key}: missing"
            subset(value, actual[key], f"{path}.{key}")
    elif isinstance(expected, list):
        assert isinstance(actual, list), f"{path}: expected list"
        assert len(actual) >= len(expected), f"{path}: too short"
        for i, value in enumerate(expected):
            subset(value, actual[i], f"{path}[{i}]")
    else:
        assert expected == actual, f"{path}: {actual!r} != {expected!r}"


def main() -> None:
    manifest = json.loads((PACK / "source.json").read_text(encoding="utf-8"))
    node_program = r'''
const fs=require('fs'), vm=require('vm'), path=require('path');
const payload=JSON.parse(fs.readFileSync(0,'utf8'));
const root=payload.root, replay=payload.replay;
const responses=(replay.responses||[]).slice();
const context={console:console};
context.Response={
 success:(data,data2)=>JSON.stringify({code:0,data:data,data2:data2===undefined?null:data2}),
 error:(message)=>JSON.stringify({code:1,data:String(message||'VBook error')})
};
context.fetch=function(url,options){
 const method=String(options&&options.method||'GET').toUpperCase();
 const idx=responses.findIndex(x=>String(x.method||'GET').toUpperCase()===method&&x.url===String(url));
 if(idx<0) throw new Error('REPLAY_REQUEST_NOT_FOUND:'+method+':'+url);
 const r=responses.splice(idx,1)[0]; const body=String(r.bodyText||'');
 return {ok:r.status>=200&&r.status<300,status:r.status,url:r.finalUrl||r.url,
   json:()=>JSON.parse(body),text:()=>body,html:()=>{throw new Error('HTML_NOT_NEEDED_BY_WATTPAD_FIXTURE')}};
};
context.load=function(name){
 const target=path.join(root,'src',String(name).replace(/^src\//,''));
 vm.runInContext(fs.readFileSync(target,'utf8'),context,{filename:target});
};
vm.createContext(context);
const actionFile=path.join(root,payload.entry);
vm.runInContext(fs.readFileSync(actionFile,'utf8'),context,{filename:actionFile});
const i=payload.input, p=String(Math.max(0,(Number(i.page||1)-1)*30));
let args=[];
switch(payload.action){
 case 'HOME': args=[i.input!==undefined?i.input:(i.category||''),p];break;
 case 'GENRE': args=[i.category||'',p];break;
 case 'SEARCH': args=[i.query||'',p];break;
 case 'SUGGESTIONS': args=[i.query||i.url||'',p];break;
 case 'TOC_PAGES': args=[i.url||'',i.pageToken||p];break;
 default: args=[i.url||''];
}
const raw=context.execute.apply(context,args);
process.stdout.write(typeof raw==='string'?raw:JSON.stringify(raw));
'''
    passed = 0
    with tempfile.TemporaryDirectory(prefix="wattpad-fixtures-") as temp:

        runner = Path(temp) / "runner.cjs"
        runner.write_text(node_program, encoding="utf-8")
        for fixture in manifest["fixtures"]:
            action = fixture["action"]
            input_obj = json.loads((PACK / fixture["input"]).read_text(encoding="utf-8"))
            replay = json.loads((PACK / fixture["fixture"]).read_text(encoding="utf-8"))
            expected = json.loads((PACK / fixture["expected"]).read_text(encoding="utf-8"))
            entry = manifest["actions"][{
                "LATEST_CHAPTER": "latest_chapter", "TOC_PAGES": "tocPages"
            }.get(action, action.lower())]["entry"]
            payload = {"root": str(PACK), "entry": entry, "action": action, "input": input_obj, "replay": replay}
            result = subprocess.run(["node", str(runner)], input=json.dumps(payload, ensure_ascii=False), text=True, capture_output=True, timeout=30)
            if result.returncode:
                raise AssertionError(f"{fixture['name']}: {result.stderr.strip()}")
            root = json.loads(result.stdout)
            if isinstance(root, dict) and root.get("code") is not None:
                assert root["code"] == 0, f"{fixture['name']}: {root}"
                data, data2 = root.get("data"), root.get("data2")
            else:
                data, data2 = root, None
            actual = normalize(action, data, data2, input_obj)
            subset(expected, actual)
            passed += 1
    print(f"PRIORITY1_WATTPAD_FIXTURES_OK cases={passed}")


if __name__ == "__main__":
    main()
