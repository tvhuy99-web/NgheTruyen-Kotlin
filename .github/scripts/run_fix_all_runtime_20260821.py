from pathlib import Path
import runpy
import subprocess

runpy.run_path(str(Path('.github/scripts/fix_all_runtime_20260821.py')), run_name='__main__')
runpy.run_path(str(Path('.github/scripts/prepare_audio_continuity_patch_20260821.py')), run_name='__main__')
runpy.run_path(str(Path('.github/scripts/escape_audio_continuity_patch_20260821.py')), run_name='__main__')
runpy.run_path(str(Path('.github/scripts/improve_audio_selection_continuity_20260821.py')), run_name='__main__')
runpy.run_path(str(Path('.github/scripts/smooth_short_ambience_loops_20260821.py')), run_name='__main__')

# The legacy verifier stages app/src/main itself only after tests succeed. Keep non-app fixes and
# the focused loop-policy test in the index so the final verified commit includes them as well.
# Its failure path resets the worktree, and the runner is ephemeral.
subprocess.run([
    'git', 'add', '--',
    'source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt',
    'source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt',
    'app/src/test/java/vn/nghetruyen/app/playback/AmbienceLoopTransitionPolicyTest.kt',
], check=True)
print('Prepared runtime + audio-continuity + short ambience loop fixes for verified commit.')