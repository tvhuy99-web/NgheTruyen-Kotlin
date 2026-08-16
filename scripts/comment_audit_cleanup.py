#!/usr/bin/env python3
import argparse, json, os, re
from pathlib import Path
from collections import Counter

ROOT = Path(__file__).resolve().parents[1]

SLASH_EXT = {'.kt','.kts','.java','.groovy','.gradle','.c','.cc','.cpp','.h','.hpp','.js','.jsx','.ts','.tsx','.rs','.swift'}
XML_EXT = {'.xml','.html','.htm','.svg'}
HASH_EXT = {'.yml','.yaml','.properties','.toml','.env','.pro','.cfg','.ini','.sh','.bash','.zsh','.py','.rb','.pl'}
POWERSHELL_EXT = {'.ps1','.psm1','.psd1'}
LUA_EXT = {'.lua'}
SQL_EXT = {'.sql'}
HASH_NAMES = {'Dockerfile','Makefile','.gitignore','.gitattributes','.env.example'}
EXCLUDE_DIRS = {'.git','build','.gradle','.idea','.kotlin','node_modules','dist','out'}
LICENSE_HINTS = (
    'copyright', 'spdx-license', 'licensed under', 'apache license',
    'permission is hereby granted', 'gnu general public license',
    'mozilla public license', 'eclipse public license', 'all rights reserved',
    'http://www.apache.org/licenses', 'https://www.apache.org/licenses'
)

def line_no(text, pos):
    return text.count('\n', 0, pos) + 1

def is_license(text):
    low = text.lower()
    return any(h in low for h in LICENSE_HINTS)

def blank_like(s):
    return ''.join('\n' if c == '\n' else ('\r' if c == '\r' else ' ') for c in s)

def is_escaped(text, pos):
    backslashes = 0
    j = pos - 1
    while j >= 0 and text[j] == '\\':
        backslashes += 1
        j -= 1
    return (backslashes % 2) == 1

def scan_slash(text):
    spans=[]
    i=0; n=len(text)
    state=None
    while i<n:
        if state is None:
            if text.startswith('"""', i):
                state=('triple','"""'); i+=3; continue
            c=text[i]
            if c in ('"', "'", '`'):
                state=('string',c); i+=1; continue
            if text.startswith('//', i) and not is_escaped(text, i):
                j=text.find('\n', i+2)
                if j<0: j=n
                spans.append((i,j,'line',text[i:j]))
                i=j; continue
            if text.startswith('/*', i) and not is_escaped(text, i):
                start=i; i+=2; depth=1
                while i<n and depth:
                    if text.startswith('/*',i) and not is_escaped(text,i):
                        depth+=1; i+=2
                    elif text.startswith('*/',i) and not is_escaped(text,i):
                        depth-=1; i+=2
                    else:
                        i+=1
                spans.append((start,i,'block',text[start:i]))
                continue
            i+=1
        else:
            typ, quote=state
            if typ=='triple':
                if text.startswith(quote,i):
                    i+=3; state=None
                else:
                    i+=1
            else:
                c=text[i]
                if c=='\\':
                    i+=2
                elif c==quote:
                    i+=1; state=None
                else:
                    i+=1
    return spans

def scan_xml(text):
    spans=[]; i=0
    while True:
        s=text.find('<!--',i)
        if s<0: break
        e=text.find('-->',s+4)
        if e<0: e=len(text)
        else: e+=3
        spans.append((s,e,'xml',text[s:e]))
        i=e
    return spans

def scan_hash(text, path):
    spans=[]
    i=0; n=len(text); state=None
    ext=path.suffix.lower()
    markers=['#']
    if ext=='.ini': markers=['#',';']
    if ext=='.properties': markers=['#','!']
    while i<n:
        if state is None:
            if text.startswith('"""', i):
                state=('triple','"""'); i+=3; continue
            c=text[i]
            if c in ('"', "'", '`'):
                state=('string',c); i+=1; continue
            hit=None
            for m in markers:
                if text.startswith(m,i):
                    prev=text[i-1] if i>0 else '\n'
                    if i==0 and text.startswith('#!',0):
                        continue
                    if i==0 or prev.isspace():
                        hit=m; break
            if hit:
                j=text.find('\n', i+len(hit))
                if j<0: j=n
                spans.append((i,j,'hash',text[i:j]))
                i=j; continue
            i+=1
        else:
            typ, quote=state
            if typ=='triple':
                if text.startswith(quote,i):
                    i+=3; state=None
                else:
                    i+=1
            else:
                c=text[i]
                if quote=="'" and c==quote:
                    if i+1<n and text[i+1]==quote:
                        i+=2
                    else:
                        i+=1; state=None
                elif c=='\\' and quote!="'":
                    i+=2
                elif c==quote:
                    i+=1; state=None
                else:
                    i+=1
    return spans

def scan_powershell(text):
    spans=[]
    i=0; n=len(text); state=None
    while i<n:
        if state is None:
            line_start = i == 0 or text[i-1] == '\n'
            if line_start and text.startswith("@'", i):
                end=text.find("\n'@", i+2)
                i=n if end<0 else end+3
                continue
            if line_start and text.startswith('@"', i):
                end=text.find('\n"@', i+2)
                i=n if end<0 else end+3
                continue
            if text.startswith('<#',i):
                e=text.find('#>',i+2)
                if e<0: e=n
                else: e+=2
                spans.append((i,e,'ps-block',text[i:e])); i=e; continue
            c=text[i]
            if c in ('"',"'"):
                state=c; i+=1; continue
            if c=='#':
                ls=text.rfind('\n',0,i)+1
                prefix=text[ls:i].strip()
                line=text[ls:text.find('\n',i) if text.find('\n',i)>=0 else n]
                if prefix=='' and line.lstrip().lower().startswith('#requires'):
                    j=text.find('\n',i)
                    i=n if j<0 else j
                    continue
                j=text.find('\n',i+1)
                if j<0: j=n
                spans.append((i,j,'ps-line',text[i:j])); i=j; continue
            i+=1
        else:
            c=text[i]
            if state=='"' and c=='`':
                i+=2
            elif c==state:
                if state=="'" and i+1<n and text[i+1]=="'":
                    i+=2
                else:
                    i+=1; state=None
            else:
                i+=1
    return spans

def scan_lua(text):
    spans=[]
    i=0; n=len(text); state=None
    while i<n:
        if state is None:
            if text.startswith('--[[',i):
                e=text.find(']]',i+4)
                if e<0: e=n
                else: e+=2
                spans.append((i,e,'lua-block',text[i:e])); i=e; continue
            if text.startswith('--',i):
                j=text.find('\n',i+2)
                if j<0: j=n
                spans.append((i,j,'lua-line',text[i:j])); i=j; continue
            if text.startswith('[[',i):
                e=text.find(']]',i+2)
                i=n if e<0 else e+2
                continue
            c=text[i]
            if c in ('"',"'"):
                state=c; i+=1; continue
            i+=1
        else:
            c=text[i]
            if c=='\\': i+=2
            elif c==state: i+=1; state=None
            else: i+=1
    return spans

def scan_sql(text):
    spans=[]
    i=0; n=len(text); state=None
    while i<n:
        if state is None:
            if text.startswith('--',i):
                j=text.find('\n',i+2)
                if j<0: j=n
                spans.append((i,j,'sql-line',text[i:j])); i=j; continue
            if text.startswith('/*',i):
                e=text.find('*/',i+2)
                if e<0: e=n
                else: e+=2
                spans.append((i,e,'sql-block',text[i:e])); i=e; continue
            c=text[i]
            if c in ('"',"'"):
                state=c; i+=1; continue
            i+=1
        else:
            c=text[i]
            if c==state:
                if i+1<n and text[i+1]==state:
                    i+=2
                else:
                    i+=1; state=None
            else: i+=1
    return spans

def file_mode(path):
    ext=path.suffix.lower()
    if ext in SLASH_EXT: return 'slash'
    if ext in XML_EXT: return 'xml'
    if ext in HASH_EXT or path.name in HASH_NAMES: return 'hash'
    if ext in POWERSHELL_EXT: return 'powershell'
    if ext in LUA_EXT: return 'lua'
    if ext in SQL_EXT: return 'sql'
    return None

def iter_files(root):
    for p in root.rglob('*'):
        if not p.is_file(): continue
        rel=p.relative_to(root)
        if any(part in EXCLUDE_DIRS for part in rel.parts): continue
        mode=file_mode(p)
        if mode: yield p,mode

def scan_for_mode(text, path, mode):
    if mode=='slash': return scan_slash(text)
    if mode=='xml': return scan_xml(text)
    if mode=='hash': return scan_hash(text,path)
    if mode=='powershell': return scan_powershell(text)
    if mode=='lua': return scan_lua(text)
    if mode=='sql': return scan_sql(text)
    return []

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--write', action='store_true')
    ap.add_argument('--json', default='comment-audit.json')
    ap.add_argument('--tsv', default='comment-audit.tsv')
    args=ap.parse_args()

    records=[]
    changed=[]
    scanned=0
    for p,mode in iter_files(ROOT):
        scanned+=1
        try:
            raw=p.read_bytes()
            if b'\0' in raw: continue
            text=raw.decode('utf-8')
        except Exception:
            continue
        spans=scan_for_mode(text,p,mode)
        if not spans: continue
        repl=list(text)
        removable=0
        for s,e,kind,body in spans:
            lic=is_license(body)
            rec={
                'path':str(p.relative_to(ROOT)).replace(os.sep,'/'),
                'line_start':line_no(text,s),
                'line_end':line_no(text,max(s,e-1)),
                'kind':kind,
                'license':lic,
                'text':re.sub(r'\s+',' ',body).strip()[:240],
            }
            records.append(rec)
            if args.write and not lic:
                replacement=blank_like(text[s:e])
                repl[s:e]=list(replacement)
                removable+=1
        if args.write and removable:
            new=''.join(repl)
            if new!=text:
                p.write_text(new,encoding='utf-8',newline='')
                changed.append(str(p.relative_to(ROOT)).replace(os.sep,'/'))

    removable=[r for r in records if not r['license']]
    protected=[r for r in records if r['license']]
    by_file=Counter(r['path'] for r in removable)
    by_ext=Counter(Path(r['path']).suffix.lower() or Path(r['path']).name for r in removable)
    summary={
        'scanned_files':scanned,
        'files_with_any_comments':len(set(r['path'] for r in records)),
        'files_with_removable_comments':len(by_file),
        'comment_occurrences_total':len(records),
        'removable_comment_occurrences':len(removable),
        'protected_license_occurrences':len(protected),
        'changed_files':changed,
        'top_files':by_file.most_common(50),
        'by_extension':dict(by_ext.most_common()),
    }
    out={'summary':summary,'comments':records}
    (ROOT/args.json).write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    with (ROOT/args.tsv).open('w',encoding='utf-8',newline='') as f:
        f.write('path\tline_start\tline_end\tkind\tlicense\ttext\n')
        for r in records:
            txt=r['text'].replace('\t',' ').replace('\n',' ')
            f.write(f"{r['path']}\t{r['line_start']}\t{r['line_end']}\t{r['kind']}\t{str(r['license']).lower()}\t{txt}\n")
    print(json.dumps(summary,ensure_ascii=False,indent=2))

if __name__=='__main__':
    main()
