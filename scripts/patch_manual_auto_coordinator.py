#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt")
source = path.read_text(encoding="utf-8")
old = '''        viewModelScope.launch {
            if (includeMusic && container.libraryRepository.listEnabledSceneMusicTracks().isEmpty()) {
                showMessage("Hãy thêm ít nhất một tệp nhạc cảnh đang bật.")
                return@launch
            }
            mutableState.update {'''
new = '''        viewModelScope.launch {
            mutableState.update {'''
if source.count(old) != 1:
    raise SystemExit(f"Expected legacy scene-music precheck once, found {source.count(old)}")
path.write_text(source.replace(old, new, 1), encoding="utf-8")
print("PATCH_MANUAL_AUTO_COORDINATOR_OK")
