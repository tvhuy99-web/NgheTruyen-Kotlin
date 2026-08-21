from pathlib import Path

path = Path('.github/scripts/mode3_ai_unbounded_audio_v17.py')
source = path.read_text(encoding='utf-8')
start_marker = "replace_regex_once(\n    director,\n    r'''\\n            val signature = listOf"
start = source.find(start_marker)
if start < 0:
    raise SystemExit('V17 runner could not locate faulty signature selector')
end_marker = "\n\n# ---------------------------------------------------------------------------\n# Mode 2/local validator"
end = source.find(end_marker, start)
if end < 0:
    raise SystemExit('V17 runner could not locate selector end')
replacement = '''replace_once(
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
)'''
patched = source[:start] + replacement + source[end:]
compiled = compile(patched, str(path), 'exec')
exec(compiled, {'__name__': '__main__', '__file__': str(path)})
