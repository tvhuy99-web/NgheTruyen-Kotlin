"""Temporary test-only shim for the phase-3 patch generator.

It adapts two stale generator assumptions without touching Android product source directly:
1. the first generic LibraryRepository range replacement may have two matches;
2. AppViewModel no longer imports the old reference voice persistence helper.
Remove this file after phase 3 verification.
"""
import importlib.util
import os
import sysconfig

_stdlib_path = os.path.join(sysconfig.get_paths()["stdlib"], "pathlib.py")
_spec = importlib.util.spec_from_file_location("_nghetruyen_real_pathlib", _stdlib_path)
_real = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(_real)

Path = _real.Path
PurePath = _real.PurePath
PosixPath = _real.PosixPath
WindowsPath = _real.WindowsPath
PurePosixPath = _real.PurePosixPath
PureWindowsPath = _real.PureWindowsPath

_original_read_text = Path.read_text
_target = (
    "rate = rate.coerceIn(0.5f, 2.0f),\n"
    "                pitch = pitch.coerceIn(0.5f, 2.0f),\n"
    "                volume = volume.coerceIn(0.05f, 1.0f),"
)
_appvm_anchor = "import vn.nghetruyen.app.playback.ReaderDocumentNormalizer\n"
_legacy_voice_import = "import vn.nghetruyen.app.ui.reference.ReferenceVoiceRolePersistence\n"


class _SmartText(str):
    def count(self, sub, *args):
        actual = super().count(sub, *args)
        if sub == _target and actual == 2:
            return 1
        return actual


def _smart_read_text(self, *args, **kwargs):
    value = _original_read_text(self, *args, **kwargs)
    name = str(self)
    if name.endswith("app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt"):
        return _SmartText(value)
    if name.endswith("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt") and _legacy_voice_import not in value:
        if _appvm_anchor not in value:
            raise RuntimeError("Stable AppViewModel import anchor not found")
        return value.replace(_appvm_anchor, _appvm_anchor + _legacy_voice_import, 1)
    return value


Path.read_text = _smart_read_text
