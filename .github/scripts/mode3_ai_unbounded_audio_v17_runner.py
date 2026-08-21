from pathlib import Path

path = Path('.github/scripts/mode3_ai_unbounded_audio_v17.py')
source = path.read_text(encoding='utf-8')
start_marker = "replace_regex_once(\n    director,\n    r'''\\n            val signature = listOf"
start = source.find(start_marker)
if start < 0:
    raise SystemExit('V17 runner could not locate faulty signature selector')
end_marker = "\nreplace_once(\n    director,\n    '''    private fun maxSfxForUnits"
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
# The runtime intentionally has two cooldown-history clear sites. The base patch already performs
# a final replace-all after the targeted edits, so remove the earlier ambiguous replace_once call.
ambiguous = "replace_once(runtime, '        lastEffectAtMillis.clear()\\n', '')\n"
if patched.count(ambiguous) != 1:
    raise SystemExit(f'V17 runner expected one ambiguous history patch call, got {patched.count(ambiguous)}')
patched = patched.replace(ambiguous, '', 1)
# The generated 100-row stress test must use tokens that survive canonicalization. A bare one-digit
# suffix is intentionally discarded by query normalization and would make distinct rows merge.
stress_query = '.put("query", "impact hit $index")'
if patched.count(stress_query) != 1:
    raise SystemExit(f'V17 runner expected one stress query template, got {patched.count(stress_query)}')
patched = patched.replace(stress_query, '.put("query", "hit code$index")', 1)
compiled = compile(patched, str(path), 'exec')
exec(compiled, {'__name__': '__main__', '__file__': str(path)})
