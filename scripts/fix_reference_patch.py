#!/usr/bin/env python3
from pathlib import Path
import re

path = Path(__file__).with_name("reference_ui_parity_all.py")
text = path.read_text(encoding="utf-8")

helper_marker = '''def regex_once(text, pattern, repl, label):
    out, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"regex {label} matched {count}")
    return out
'''
helper = helper_marker + '''

def replace_kotlin_function(text, signature, replacement, label):
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"missing Kotlin function for {label}")
    brace = text.find("{", start + len(signature))
    if brace < 0:
        raise SystemExit(f"missing opening brace for {label}")
    depth = 0
    end = None
    for index in range(brace, len(text)):
        ch = text[index]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                end = index + 1
                break
    if end is None:
        raise SystemExit(f"missing closing brace for {label}")
    if end < len(text) and text[end] == "\\n":
        end += 1
    return text[:start] + replacement + text[end:]
'''
if helper_marker not in text:
    raise SystemExit("missing helper insertion marker")
text = text.replace(helper_marker, helper, 1)

pattern = re.compile(
    r'''t = replace_once\(\n'''
    r'''    t,\n'''
    r'''    ''' + "'''" + r'''    private fun observePlayback\(\) \{.*?'''+ "'''" + r''',\n'''
    r'''    ''' + "'''" + r'''    private fun observePlayback\(\) \{.*?'''+ "'''" + r''',\n'''
    r'''    "observe playback sleep chapters",\n'''
    r'''\)\n''',
    re.S,
)

replacement = r'''t = replace_kotlin_function(
    t,
    "    private fun observePlayback()",
    ''' + "'''" + r'''    private fun observePlayback() {
        viewModelScope.launch {
            PlaybackQueueStore.state.collect { playback ->
                val previousChapterId = chapterSleepLastChapterId
                val currentChapterId = playback.chapterId
                val remaining = chapterSleepRemaining
                if (
                    remaining != null &&
                    previousChapterId.isNotBlank() &&
                    currentChapterId.isNotBlank() &&
                    currentChapterId != previousChapterId
                ) {
                    val nextRemaining = remaining - 1
                    if (nextRemaining <= 0) {
                        chapterSleepRemaining = null
                        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_PAUSE)
                        mutableState.update {
                            it.copy(
                                playback = playback,
                                sleepTimerStatus = "Đang tắt",
                                message = "Đã dừng đọc theo hẹn giờ chương.",
                            )
                        }
                    } else {
                        chapterSleepRemaining = nextRemaining
                        mutableState.update {
                            it.copy(
                                playback = playback,
                                sleepTimerStatus = "Còn $nextRemaining chương",
                            )
                        }
                    }
                } else {
                    mutableState.update { it.copy(playback = playback) }
                }
                if (currentChapterId.isNotBlank()) chapterSleepLastChapterId = currentChapterId
            }
        }
    }
''' + "'''" + r''',
    "observe playback sleep chapters",
)
'''

new_text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit(f"fixer matched observePlayback block {count} times")
path.write_text(new_text, encoding="utf-8")
print("reference patch script normalized with balanced function replacement")
