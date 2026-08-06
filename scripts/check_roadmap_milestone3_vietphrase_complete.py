#!/usr/bin/env python3
"""Executable source-side completion gate for roadmap Milestone 3 VietPhrase."""
from __future__ import annotations
import hashlib
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")
JAVA = shutil.which("java")


def write(path: Path, text: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def run(cmd: list[str], timeout: int = 240) -> None:
    result = subprocess.run(cmd, cwd=ROOT, text=True, capture_output=True, timeout=timeout)
    if result.stdout:
        print(result.stdout.strip())
    if result.returncode:
        if result.stderr:
            print(result.stderr)
        raise SystemExit(result.returncode)


def verify_contract() -> None:
    contract = json.loads((ROOT / "docs/xpk_reference/vietphrase_v34_contract.json").read_text(encoding="utf-8"))
    assert contract["engine_revision"] == "vp-r9.4-ai-final-replace-20260725"
    assert contract["import_parser_revision"] == "vp-import-safe-dat-v1"
    assert contract["dictionary_order"] == [
        "LUAT_NHAN", "PRONOUNS", "PHIEN_AM", "LAC_VIET", "VIET_PHRASE", "NAMES", "AI_REPLACE",
    ]
    model = (ROOT / "app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseModels.kt").read_text(encoding="utf-8")
    import re
    for kind, priority in contract["base_priorities"].items():
        assert re.search(rf"\b{re.escape(kind)}\(\"[^\"]+\",\s*{priority}\)[,;]", model), (kind, priority)
    optional_xpk = Path("/mnt/data/Nghe_20260804_sua_phan_trang_chuong_tu_truyen_v34 (1).xpk")
    if optional_xpk.is_file():
        import zipfile
        with zipfile.ZipFile(optional_xpk) as archive:
            source = archive.read(contract["source_file"])
        actual = hashlib.sha256(source).hexdigest()
        assert actual == contract["source_sha256"], (actual, contract["source_sha256"])
    print("M3_XPK_V34_CONTRACT_OK")


def main() -> None:
    if not KOTLINC or not JAVA:
        raise SystemExit("M3_VIETPHRASE_COMPLETE_BLOCKED: thiếu kotlinc/java")
    verify_contract()
    with tempfile.TemporaryDirectory(prefix="nghe-m3-vp-complete-") as name:
        temp = Path(name)
        entity = write(temp / "vn/nghetruyen/app/data/local/VietPhraseEntity.kt", '''package vn.nghetruyen.app.data.local

data class VietPhraseEntity(
 val id:Long=0,
 val source:String,
 val target:String,
 val priority:Int=0,
 val enabled:Boolean=true,
 val kind:String="VIET_PHRASE",
 val scope:String="GLOBAL",
 val storyId:String="",
 val matchMode:String="LITERAL",
 val ignoreCase:Boolean=false,
 val createdAt:Long=0,
 val updatedAt:Long=0,
)
''')
        runner = write(temp / "M3VietPhraseCompleteSmoke.kt", r'''import vn.nghetruyen.app.ai.VietPhraseProcessor
import vn.nghetruyen.app.ai.vietphrase.*
import vn.nghetruyen.app.data.local.VietPhraseEntity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun rule(id:String, source:String, target:String, kind:VietPhraseDictionaryKind=VietPhraseDictionaryKind.VIET_PHRASE, priority:Int=0, scope:VietPhraseScope=VietPhraseScope.GLOBAL, storyId:String?=null, enabled:Boolean=true): VietPhraseRule =
    VietPhraseRule(id, source, target, kind, priority, enabled, scope, storyId)

fun write7(stream:DataOutputStream, number:Int) { var value=number; while(value>=0x80){stream.writeByte((value and 0x7f) or 0x80);value=value ushr 7};stream.writeByte(value) }
fun writeString(stream:DataOutputStream, codec:String, value:String) { val b=value.toByteArray(Charsets.UTF_8); when(codec){"java"->stream.writeUTF(value);"dotnet"->{write7(stream,b.size);stream.write(b)};"u32be"->{stream.writeInt(b.size);stream.write(b)};"u32le"->{stream.writeInt(Integer.reverseBytes(b.size));stream.write(b)}} }
fun dic(codec:String, paired:Boolean):ByteArray { val out=ByteArrayOutputStream(); DataOutputStream(out).use{ s->s.writeInt(2); val k=listOf("天道","叶凡");val v=listOf("Thiên Đạo","Diệp Phàm"); if(paired){repeat(2){writeString(s,codec,k[it]);writeString(s,codec,v[it])}}else{k.forEach{writeString(s,codec,it)};v.forEach{writeString(s,codec,it)}}}; return out.toByteArray() }

fun main(args:Array<String>) {
    check(VietPhraseProcessor.apply("Thiên Đạo và RAIL AI", listOf(
        VietPhraseEntity(1,"Thiên Đạo","Đạo Trời",10,true,createdAt=0,updatedAt=0),
        VietPhraseEntity(2,"Đạo Trời","không cascade",100,true,createdAt=0,updatedAt=0),
        VietPhraseEntity(3,"AI","ây ai",0,true,createdAt=0,updatedAt=0),
    )) == "Đạo Trời và RAIL ây ai")

    val rules = listOf(
        rule("vp-short", "天", "thiên"), rule("vp-long", "天道", "thiên đạo"),
        rule("name", "天道", "Thiên Đạo", VietPhraseDictionaryKind.NAMES),
        rule("person", "叶凡", "Diệp Phàm", VietPhraseDictionaryKind.NAMES),
        rule("pronoun", "他", "hắn", VietPhraseDictionaryKind.PRONOUNS),
        rule("law", "{0}看着{1}", "{0} nhìn {1}", VietPhraseDictionaryKind.LUAT_NHAN),
        rule("story", "宗门", "thánh địa", priority=100, scope=VietPhraseScope.STORY, storyId="s1"),
        rule("global", "宗门", "tông môn"),
        rule("meaning", "道", "đạo/lối/con đường", VietPhraseDictionaryKind.LAC_VIET),
        rule("said", "他说", "hắn nói"),
        rule("ai-1", "Thiên Đạo", "Đạo Trời", VietPhraseDictionaryKind.AI_REPLACE),
        rule("ai-2", "Đạo Trời", "không cascade", VietPhraseDictionaryKind.AI_REPLACE),
    )
    val engine = VietPhraseEngine(rules)
    check(engine.translate("叶凡看着他。天道") == "Diệp Phàm nhìn hắn. Đạo Trời")
    check(engine.translate("宗门", VietPhraseOptions(storyId="s1")) == "Thánh địa")
    check(engine.translate("宗门", VietPhraseOptions(storyId="s2")) == "Tông môn")
    check(engine.translate("道", VietPhraseOptions(oneMeaning=true)) == "Đạo")
    check(engine.translate("道", VietPhraseOptions(oneMeaning=false)) == "Đạo / lối / con đường")
    check(engine.translate("“叶凡”，他说！") == "“Diệp Phàm”, hắn nói!")
    engine.translate("叶凡")
    check(engine.translateWithTrace("叶凡").trace.size == 1)
    val limited=engine.translateWithTrace("叶凡叶凡",VietPhraseOptions(traceLimit=1));check(limited.trace.size==1&&limited.traceTruncated)

    val golden = File(args[0]).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    golden.forEach { line ->
        val f=line.split('\t'); check(f.size==3)
        val options=when(f[0]){"multi_meaning"->VietPhraseOptions(oneMeaning=false);else->VietPhraseOptions()}
        val actual=engine.translate(f[1],options); check(actual==f[2]) { "golden ${f[0]}: <$actual> != <${f[2]}>" }
    }

    val utf16=byteArrayOf(0xff.toByte(),0xfe.toByte())+"叶凡\tDiệp Phàm\n".toByteArray(Charsets.UTF_16LE)
    check(VietPhraseDictionaryCodec.decode(utf16,"Names.txt").rules.single().target=="Diệp Phàm")
    listOf("java","dotnet","u32be","u32le").forEach { codec -> listOf(false,true).forEach { paired ->
        val decoded=VietPhraseBinaryDictionaryCodec.decode(dic(codec,paired),"Names.dic")
        check(decoded.rules.map{it.source}==listOf("天道","叶凡"))
    }}

    val nodeCount=22828;val child=22827;val base=IntArray(nodeCount);val check=IntArray(nodeCount)
    base[0]=1;base[child]=2;check[child]=1;base[2]=-1;check[2]=2
    val values="thiên\n".toByteArray();val dat=ByteBuffer.allocate(4+nodeCount*4+4+nodeCount*4+4+values.size).order(ByteOrder.BIG_ENDIAN)
    dat.putInt(nodeCount);base.forEach(dat::putInt);dat.putInt(nodeCount);check.forEach(dat::putInt);dat.putInt(1);dat.put(values)
    check(VietPhraseBinaryDictionaryCodec.decode(dat.array(),"HV.dat").rules.single().target=="thiên")

    val archiveRules=listOf(rule("n","叶凡","Diệp Phàm",VietPhraseDictionaryKind.NAMES,scope=VietPhraseScope.STORY,storyId="s1",enabled=false))
    check(VietPhraseArchiveCodec.decode(VietPhraseArchiveCodec.encode(archiveRules)).rules==archiveRules)
    val states=listOf(VietPhrasePersistenceArchiveCodec.DictionaryState("NAMES:STORY:s1",VietPhraseDictionaryKind.NAMES,VietPhraseScope.STORY,"s1",false,"Names.dic","DIC", "abc",1,2,3))
    val persisted=VietPhrasePersistenceArchiveCodec.decode(VietPhrasePersistenceArchiveCodec.encode(archiveRules,states))
    check(persisted.rules==archiveRules&&persisted.dictionaryStates==states&&!persisted.legacyRuleOnly)
    check(VietPhrasePersistenceArchiveCodec.decodeCompatible(VietPhraseArchiveCodec.encode(archiveRules)).legacyRuleOnly)

    val bundle=VietPhraseBundleCodec.decodeZip(VietPhraseBundleCodec.encodeZip(archiveRules+rules.take(2),states))
    check(bundle.rules.size==3&&bundle.dictionaryStates==states&&!bundle.legacyRuleOnly)
    val malicious=ByteArrayOutputStream();ZipOutputStream(malicious).use{z->z.putNextEntry(ZipEntry("../Names.txt"));z.write("叶凡=Diệp Phàm".toByteArray());z.closeEntry()}
    check(runCatching{VietPhraseBundleCodec.decodeZip(malicious.toByteArray())}.isFailure)

    val bad=rule("bad","{0}说","{1} nói",VietPhraseDictionaryKind.LUAT_NHAN)
    val ca=rule("ca","A","B",VietPhraseDictionaryKind.AI_REPLACE);val cb=rule("cb","B","A",VietPhraseDictionaryKind.AI_REPLACE)
    val audit=VietPhraseAudit.inspect(listOf(bad,ca,cb));check(audit.any{it.code=="INVALID_TEMPLATE_SLOT"&&it.severity==VietPhraseConflict.Severity.ERROR});check(audit.any{it.code=="AI_REPLACE_CYCLE"})
    val plan=VietPhraseImportPlanner.plan(listOf(rule("old","天道","cũ")),listOf(rule("new","天道","mới")),createdAt=123)
    check(plan.canCommit&&plan.diff.changed.size==1&&VietPhraseImportPlanner.rollback(plan).single().target=="cũ")

    val large=ArrayList<VietPhraseRule>(100000);repeat(100000){i->large+=rule("r$i","词$i","nghĩa$i")}
    val report=VietPhraseProfiler.profile(large,listOf("large" to "词99999"))
    check(report.samples.single().outputChars>0&&report.ruleCount==100000&&report.buildNanos>0)
    check(report.buildNanos/1_000_000<20000){report.asText()}
    println("M3_VIETPHRASE_COMPLETE_RUNTIME_OK rules=${report.ruleCount} buildMs=${report.buildNanos/1_000_000}")
}
''')
        sources = sorted((ROOT / "app/src/main/java/vn/nghetruyen/app/ai/vietphrase").glob("*.kt"))
        sources += [ROOT / "app/src/main/java/vn/nghetruyen/app/ai/VietPhraseProcessor.kt", entity, runner]
        jar = temp / "m3-complete.jar"
        run([KOTLINC, *map(str, sources), "-include-runtime", "-d", str(jar)], timeout=240)
        run([JAVA, "-Xmx1g", "-jar", str(jar), str(ROOT / "app/src/test/resources/vietphrase/xpk_golden.tsv")], timeout=240)

        junit = write(temp / "org/junit/Junit.kt", '''package org.junit
annotation class Test(val expected: kotlin.reflect.KClass<out Throwable> = None::class)
class None: Throwable()
object Assert {
 @JvmStatic fun assertEquals(expected:Any?, actual:Any?) {}
 @JvmStatic fun assertTrue(value:Boolean) {}
 @JvmStatic fun assertFalse(value:Boolean) {}
}
''')
        tests = sorted((ROOT / "app/src/test/java/vn/nghetruyen/app/ai/vietphrase").glob("*.kt"))
        test_jar = temp / "m3-tests.jar"
        run([KOTLINC, "-classpath", str(jar), *map(str, tests), str(junit), "-d", str(test_jar)], timeout=180)
        print("M3_VIETPHRASE_JUNIT_SOURCE_COMPILE_OK")
    print("ROADMAP_MILESTONE3_VIETPHRASE_COMPLETE_GATE=PASS")


if __name__ == "__main__":
    main()
