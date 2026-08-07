#!/usr/bin/env python3
from pathlib import Path
import re

path = Path(__file__).with_name("reference_ui_parity_all.py")
text = path.read_text(encoding="utf-8")

pattern = re.compile(
    r'''t = replace_once\(\n'''
    r'''    t,\n'''
    r'''    ''' + "'''" + r'''    private fun observePlayback\(\) \{.*?'''+ "'''" + r''',\n'''
    r'''    ''' + "'''" + r'''    private fun observePlayback\(\) \{.*?'''+ "'''" + r''',\n'''
    r'''    "observe playback sleep chapters",\n'''
    r'''\)\n''',
    re.S,
)

replacement = r'''t = regex_once(
    t,
    r''' + "'''" + r'''    private fun observePlayback\(\) \{.*?\n    \}\n(?=\n    private fun )''' + "'''" + r''',
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
print("reference patch script normalized")
