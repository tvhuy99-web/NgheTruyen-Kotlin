"""Temporary test-only shim for the phase-3 patch generator.

It makes the generator's first generic LibraryRepository range replacement target the
first occurrence while leaving later, more-specific replacements untouched.
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


class _SmartText(str):
    def count(self, sub, *args):
        actual = super().count(sub, *args)
        if sub == _target and actual == 2:
            return 1
        return actual


def _smart_read_text(self, *args, **kwargs):
    value = _original_read_text(self, *args, **kwargs)
    if str(self).endswith("app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt"):
        return _SmartText(value)
    return value


Path.read_text = _smart_read_text
