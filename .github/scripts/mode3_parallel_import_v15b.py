from pathlib import Path

base = Path('.github/scripts/mode3_parallel_import_v15.py')
source = base.read_text(encoding='utf-8')

needle = '''def replace_once(path: str, old: str, new: str) -> None:\n    p = ROOT / path\n    text = p.read_text(encoding='utf-8')\n    count = text.count(old)\n    if count != 1:\n        raise SystemExit(f'{path}: expected exactly one match, got {count}')\n    p.write_text(text.replace(old, new, 1), encoding='utf-8')\n'''
replacement = needle + '''\n\ndef replace_first(path: str, old: str, new: str) -> None:\n    p = ROOT / path\n    text = p.read_text(encoding='utf-8')\n    count = text.count(old)\n    if count < 1:\n        raise SystemExit(f'{path}: expected at least one match, got {count}')\n    p.write_text(text.replace(old, new, 1), encoding='utf-8')\n'''
if source.count(needle) != 1:
    raise SystemExit('Unable to inject replace_first helper')
source = source.replace(needle, replacement, 1)

ambiguous = '''replace_once(\n    resolver,\n    \'\'\'                "parallelSearchLimit" to FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES.toString(),\n\'\'\','''
if source.count(ambiguous) != 1:
    raise SystemExit(f'Unable to locate ambiguous diagnostics patch: {source.count(ambiguous)}')
source = source.replace(ambiguous, ambiguous.replace('replace_once(', 'replace_first(', 1), 1)

exec(compile(source, str(base), 'exec'), {'__name__': '__main__'})
