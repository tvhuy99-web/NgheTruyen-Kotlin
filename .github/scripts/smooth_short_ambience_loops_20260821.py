from pathlib import Path
import runpy

runpy.run_path(
    str(Path('.github/scripts/finalize_file_adaptive_ambience_20260821.py')),
    run_name='__main__',
)
