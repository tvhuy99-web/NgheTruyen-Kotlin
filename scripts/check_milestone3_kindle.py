#!/usr/bin/env python3
"""Compile and execute bounded PalmDOC, KF8-only and HUFF/CDIC importer smoke tests."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")


def main() -> None:
    if not KOTLINC:
        print("MILESTONE3_KINDLE_SMOKE_SKIPPED_NO_KOTLINC")
        return
    with tempfile.TemporaryDirectory(prefix="nghe_m3_kindle_") as temp_name:
        temp = Path(temp_name)
        smoke = temp / "KindleSmoke.kt"
        smoke.write_text(r'''import vn.nghetruyen.app.importers.MobiParser
import vn.nghetruyen.app.importers.PalmDocCompression

private fun put16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 8).toByte(); bytes[offset + 1] = value.toByte()
}
private fun put32(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte(); bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte(); bytes[offset + 3] = value.toByte()
}
private fun pdb(records: List<ByteArray>): ByteArray {
    val tableEnd = 78 + records.size * 8
    val bytes = ByteArray(tableEnd + records.sumOf(ByteArray::size))
    put16(bytes, 76, records.size)
    var offset = tableEnd
    records.forEachIndexed { index, record ->
        put32(bytes, 78 + index * 8, offset)
        record.copyInto(bytes, offset)
        offset += record.size
    }
    return bytes
}
private fun palmDoc(text: ByteArray, encryption: Int = 0): ByteArray {
    val record0 = ByteArray(16)
    put16(record0, 0, 1); put32(record0, 4, text.size); put16(record0, 8, 1)
    put16(record0, 10, 4096); put16(record0, 12, encryption)
    return pdb(listOf(record0, text))
}
private fun mobi(text: ByteArray, version: Int): ByteArray {
    val record0 = ByteArray(120)
    put16(record0, 0, 1); put32(record0, 4, text.size); put16(record0, 8, 1)
    put16(record0, 10, 4096)
    "MOBI".toByteArray(Charsets.US_ASCII).copyInto(record0, 16)
    put32(record0, 20, 104); put32(record0, 28, 65001); put32(record0, 36, version)
    return pdb(listOf(record0, text))
}
private fun huffCdicMobi(): ByteArray {
    val record0 = ByteArray(120)
    put16(record0, 0, 17480); put32(record0, 4, 8); put16(record0, 8, 1)
    put16(record0, 10, 4096)
    "MOBI".toByteArray(Charsets.US_ASCII).copyInto(record0, 16)
    put32(record0, 20, 104); put32(record0, 28, 65001); put32(record0, 36, 8)
    put32(record0, 112, 2); put32(record0, 116, 2)
    val text = byteArrayOf(0)
    val huff = ByteArray(16 + 256 * 4 + 32 * 8)
    "HUFF".toByteArray(Charsets.US_ASCII).copyInto(huff, 0)
    put32(huff, 4, 24); put32(huff, 8, 16); put32(huff, 12, 16 + 256 * 4)
    repeat(256) { put32(huff, 16 + it * 4, 0x81) }
    val cdic = ByteArray(21)
    "CDIC".toByteArray(Charsets.US_ASCII).copyInto(cdic, 0)
    put32(cdic, 4, 16); put32(cdic, 8, 1); put32(cdic, 12, 1)
    put16(cdic, 16, 2); put16(cdic, 18, 0x8001); cdic[20] = 'A'.code.toByte()
    return pdb(listOf(record0, text, huff, cdic))
}
fun main() {
    check(PalmDocCompression.decode(byteArrayOf(0xC8.toByte(), 0x65, 0x6c, 0x6c, 0x6f), 100).toString(Charsets.UTF_8) == " Hello")
    check(MobiParser.parse(palmDoc("<p>Xin chào.</p>".toByteArray()), "PalmDOC").text.contains("Xin chào"))
    val kf8 = MobiParser.parse(mobi("<p>Văn bản KF8.</p>".toByteArray(), 8), "KF8")
    check(kf8.formatVersion == 8 && kf8.text.contains("KF8"))
    val huff = MobiParser.parse(huffCdicMobi(), "HUFF")
    check(huff.compression == 17480 && huff.text == "AAAAAAAA")
    var rejected = false
    try { MobiParser.parse(palmDoc("Khóa".toByteArray(), encryption = 1), "DRM") }
    catch (_: IllegalArgumentException) { rejected = true }
    check(rejected)
    println("MILESTONE3_KINDLE_SMOKE_OK")
}
''', encoding="utf-8")
        jar = temp / "kindle-smoke.jar"
        result = subprocess.run(
            [
                KOTLINC,
                str(ROOT / "app/src/main/java/vn/nghetruyen/app/importers/MobiParser.kt"),
                str(ROOT / "app/src/main/java/vn/nghetruyen/app/importers/HuffCdicDecoder.kt"),
                str(smoke), "-include-runtime", "-d", str(jar),
            ],
            cwd=ROOT, text=True, capture_output=True, timeout=120,
        )
        if result.returncode:
            print(result.stdout); print(result.stderr)
            raise SystemExit(result.returncode)
        run = subprocess.run(["java", "-jar", str(jar)], cwd=ROOT, text=True, capture_output=True, timeout=30)
        if run.returncode:
            print(run.stdout); print(run.stderr)
            raise SystemExit(run.returncode)
        print(run.stdout.strip())


if __name__ == "__main__":
    main()
