from pathlib import Path
import runpy
import subprocess

runpy.run_path(str(Path('.github/scripts/fix_all_runtime_20260821.py')), run_name='__main__')
runpy.run_path(str(Path('.github/scripts/prepare_audio_continuity_patch_20260821.py')), run_name='__main__')
runpy.run_path(str(Path('.github/scripts/escape_audio_continuity_patch_20260821.py')), run_name='__main__')
runpy.run_path(str(Path('.github/scripts/improve_audio_selection_continuity_20260821.py')), run_name='__main__')

# The legacy verifier stages app/src/main itself only after tests succeed. Keep the two
# non-app runtime fixes in the index so the verifier's final success commit includes them.
# Its failure path runs `git reset --hard HEAD`, so failed verification cannot publish them.
subprocess.run([
    'git', 'add', '--',
    'source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt',
    'source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt',
], check=True)
print('Prepared runtime + audio-continuity fixes for verified commit.')