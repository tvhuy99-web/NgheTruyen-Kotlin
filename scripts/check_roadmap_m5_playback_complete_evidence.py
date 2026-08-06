#!/usr/bin/env python3
"""Verify immutable source acceptance evidence for Roadmap Milestone 5 playback."""
from __future__ import annotations
import hashlib, json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
EVIDENCE=ROOT/'docs/ROADMAP_M5_PLAYBACK_COMPLETE_EVIDENCE.json'
REQUIRED_GATES={
 'check_roadmap_milestone5_playback_complete.py','validate_release.py',
 'check_milestone1_foundation.py','check_milestone1_reader_core.py','check_p1_ui_static.py','check_p1_features.py',
 'check_milestone2_comments.py','check_comment_fixtures.py','check_legacy_source_audit.py','check_p2_sources.py',
 'check_source_platform_android_static.py','check_source_platform_foundation.py','check_milestone2_source_platform.py',
 'check_milestone2_complete.py','check_vbook_static.py','check_p3_features.py','check_p4_features.py',
 'check_roadmap_milestone3_vietphrase_complete.py','check_roadmap_m3_persistence_static.py','check_p4_transfer_static.py',
 'check_p2_ui_static.py','check_milestone3_foundation.py','check_milestone3_ui_static.py',
 'check_milestone4_foundation.py','check_milestone4_complete.py','check_milestone5_foundation.py',
 'check_milestones_0_2_source_complete.py','check_roadmap_m3_vietphrase_complete_evidence.py',
}
def sha256(path:Path)->str:
 h=hashlib.sha256()
 with path.open('rb') as stream:
  for chunk in iter(lambda:stream.read(1024*1024),b''): h.update(chunk)
 return h.hexdigest()
def main()->None:
 data=json.loads(EVIDENCE.read_text(encoding='utf-8'))
 assert data['schemaVersion']==1
 assert data['sourceStatus']=='COMPLETE'
 assert data['version']=='2.1.0-milestone-5-playback-complete'
 assert data['databaseSchemaVersion']==16
 assert data['backupFormatVersion']==12
 gates={x['gate']:x['result'] for x in data['gates']}
 missing=REQUIRED_GATES-gates.keys(); assert not missing, f'Thiếu gate M5: {sorted(missing)}'
 failed={g:gates[g] for g in REQUIRED_GATES if gates[g]!='PASS'}; assert not failed, f'Gate M5 chưa PASS: {failed}'
 tracked=data['trackedFiles']; assert tracked
 current=json.loads((ROOT/'REWRITE_STATUS.json').read_text(encoding='utf-8'))
 strict=current.get('version')==data['version']
 evolved=[]
 for rel,expected in tracked.items():
  p=ROOT/rel; assert p.is_file(),f'Thiếu tệp M5: {rel}'
  actual=sha256(p)
  if actual!=expected:
   if strict: raise AssertionError(f'Tệp M5 thay đổi sau nghiệm thu: {rel}')
   evolved.append(rel)
 assert data.get('deferredChecks'), 'Phải ghi rõ chứng nhận Android đã hoãn'
 suffix=f' evolved={len(evolved)}' if evolved else ''
 print(f"ROADMAP_M5_PLAYBACK_COMPLETE_EVIDENCE_OK files={len(tracked)} gates={len(REQUIRED_GATES)}{suffix}")
if __name__=='__main__': main()
