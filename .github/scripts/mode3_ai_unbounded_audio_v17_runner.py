from pathlib import Path

path = Path('.github/scripts/mode3_ai_unbounded_audio_v17.py')
source = path.read_text(encoding='utf-8')
old = '''replace_regex_once(
    director,
    r'''\\n            val signature = listOf\\(\\n                unitId,\\n                effectId,\\n                stopUnitId\\.orEmpty\\(\\),\\n                repeatCount\\.toString\\(\\),\\n                cadence\\.name,\\n                loopUntilStop\\.toString\\(\\),\\n            \\)\\.joinToString\\("\\\\\\|"\\)\\n            require\\(usedSignatures\\.add\\(signature\\)\\) \\{ "sfx_cues\\[\\$index\\] lặp lại đúng cùng một cue\\." \\}''',
    '',
)
'''
new = '''replace_once(
    director,
    """            val signature = listOf(
                unitId,
                effectId,
                stopUnitId.orEmpty(),
                repeatCount.toString(),
                cadence.name,
                loopUntilStop.toString(),
            ).joinToString("|")
            require(usedSignatures.add(signature)) { "sfx_cues[$index] lặp lại đúng cùng một cue." }
""",
    '',
)
'''
if source.count(old) != 1:
    raise SystemExit(f'V17 runner expected one faulty selector, got {source.count(old)}')
patched = source.replace(old, new, 1)
compiled = compile(patched, str(path), 'exec')
exec(compiled, {'__name__': '__main__', '__file__': str(path)})
