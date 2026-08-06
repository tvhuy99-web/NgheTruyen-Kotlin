#!/usr/bin/env python3
"""Replay the 12 new HOME/GENRE declarative fixtures without Android/Gradle."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
from urllib.parse import urljoin
from bs4 import BeautifulSoup

ROOT=Path(__file__).resolve().parents[1]
PACKS=('truyenfull','truyencv','truyencom','truyenyy','wikidich','sangtacviet')


def text_of(node)->str:
    return ' '.join(node.get_text(' ',strip=True).split())


def project_field(container, spec:dict, base_url:str):
    selected=container.select_one(spec.get('select','')) if spec.get('select') else container
    if selected is None:
        return ''
    if 'attr' in spec:
        value=selected.get(spec['attr'],'').strip()
        if spec.get('absolute'):
            value=urljoin(base_url,value)
    else:
        value=text_of(selected)
    if spec.get('sha256'):
        value=hashlib.sha256(value.encode('utf-8')).hexdigest()[:32]
    return value


def replay(pack:str, action_name:str)->None:
    root=ROOT/'examples/sourcepacks'/pack
    action=json.loads((root/'actions'/f'{action_name}.json').read_text())
    fixture=json.loads((root/'fixtures'/f'{action_name}.http.json').read_text())
    expected=json.loads((root/'fixtures'/f'{action_name}.expected.json').read_text())
    input_data=json.loads((root/'fixtures'/f'{action_name}.input.json').read_text())
    steps=action['steps']
    fetch=next(step for step in steps if step['op']=='fetch')
    response=fixture['responses'][0]
    assert response['method']=='GET'
    assert response['url']==fetch['url'], (pack,action_name,response['url'],fetch['url'])
    select=next(step for step in steps if step['op']=='selectHtmlArray')
    soup=BeautifulSoup(response['bodyText'],'html.parser')
    rows=[]
    for container in soup.select(select['selector'])[:select.get('limit',300)]:
        item={name:project_field(container,spec,response['url']) for name,spec in select['fields'].items()}
        if item.get('title') and item.get('url'):
            rows.append(item)
    page=max(1,int(input_data.get('page',1)))
    paginate=next(step for step in steps if step['op']=='paginate')
    size=int(paginate['pageSize']); start=(page-1)*size
    actual={'items':rows[start:start+size],'nextPage':page+1 if start+size < len(rows) else None}
    assert actual==expected, f'{pack}/{action_name}:\nactual={actual}\nexpected={expected}'
    print(f'PRIORITY1_FIXTURE_OK {pack}/{action_name}')


def main()->None:
    for pack in PACKS:
        replay(pack,'home')
        replay(pack,'genre')
    print('PRIORITY1_HOME_GENRE_FIXTURES_OK cases=12')

if __name__=='__main__': main()
