#!/usr/bin/env python3
from __future__ import annotations

import shutil
import struct
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")


def write_wave(path: Path, sample_rate: int, payload: bytes, junk: bool = False) -> None:
    fmt = struct.pack("<HHIIHH", 1, 1, sample_rate, sample_rate * 2, 2, 16)
    chunks = []
    if junk:
        chunks.append(b"JUNK" + struct.pack("<I", 3) + b"abc" + b"\0")
    chunks.append(b"fmt " + struct.pack("<I", len(fmt)) + fmt)
    chunks.append(b"data" + struct.pack("<I", len(payload)) + payload + (b"\0" if len(payload) % 2 else b""))
    body = b"WAVE" + b"".join(chunks)
    path.write_bytes(b"RIFF" + struct.pack("<I", len(body)) + body)


def main() -> None:
    if not KOTLINC:
        print("WAVE_ASSEMBLER_CHECK_SKIPPED: kotlinc not found")
        return
    with tempfile.TemporaryDirectory(prefix="nghe_wave_") as name:
        temp = Path(name)
        a = temp / "a.wav"
        b = temp / "b.wav"
        bad = temp / "bad.wav"
        out = temp / "joined.wav"
        write_wave(a, 22050, b"\x01\x02" * 100, junk=True)
        write_wave(b, 22050, b"\x03\x04" * 80)
        write_wave(bad, 16000, b"\x05\x06" * 10)
        harness = temp / "Harness.kt"
        harness.write_text(
            '''
import java.io.File
import vn.nghetruyen.app.audio.WaveFileAssembler

fun main(args: Array<String>) {
    val a = File(args[0]); val b = File(args[1]); val bad = File(args[2]); val out = File(args[3])
    val sa = WaveFileAssembler.inspect(a)
    check(sa.sampleRate == 22050L)
    check(sa.dataLength == 200L)
    val bytes = WaveFileAssembler.assemble(listOf(a, b), out)
    check(bytes == out.length())
    val joined = WaveFileAssembler.inspect(out)
    check(joined.dataLength == 360L)
    check(joined.sampleRate == 22050L)
    var rejected = false
    try { WaveFileAssembler.assemble(listOf(a, bad), File(out.parentFile, "bad-out.wav")) }
    catch (_: java.io.IOException) { rejected = true }
    check(rejected)
    println("WAVE_ASSEMBLER_CHECK_OK")
}
''',
            encoding="utf-8",
        )
        jar = temp / "wave.jar"
        subprocess.run(
            [KOTLINC, str(ROOT / "app/src/main/java/vn/nghetruyen/app/audio/WaveFileAssembler.kt"), str(harness), "-include-runtime", "-d", str(jar)],
            check=True,
            cwd=ROOT,
        )
        result = subprocess.run(
            ["java", "-jar", str(jar), str(a), str(b), str(bad), str(out)],
            text=True,
            capture_output=True,
            timeout=30,
        )
        if result.stdout:
            print(result.stdout.strip())
        if result.returncode:
            print(result.stderr)
            raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
