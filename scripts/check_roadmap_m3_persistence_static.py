#!/usr/bin/env python3
"""Compile Room/repository M3 wiring and execute the exact 13->14 SQL on sqlite3."""
from __future__ import annotations
import re, shutil, sqlite3, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]; K=shutil.which('kotlinc')
def w(r,p,t): q=r/p;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(t,encoding='utf-8');return q

def run(cmd,timeout=240):
 cp=subprocess.run(cmd,cwd=ROOT,text=True,capture_output=True,timeout=timeout)
 if cp.stdout: print(cp.stdout.strip())
 if cp.returncode:
  if cp.stderr: print(cp.stderr)
  raise SystemExit(cp.returncode)

def compile_kotlin(temp:Path):
 stubs=temp/'stubs'; files=[
  w(stubs,'android/content/Context.kt','package android.content\nopen class Context { open val applicationContext:Context get()=this }\n'),
  w(stubs,'androidx/room/Room.kt','''package androidx.room
import android.content.Context
annotation class Dao
annotation class ColumnInfo(val name:String="[field-name]",val typeAffinity:Int=1,val index:Boolean=false,val defaultValue:String="[value-unspecified]",val collate:Int=1)
annotation class Database(val entities:Array<kotlin.reflect.KClass<*>>,val version:Int,val exportSchema:Boolean=true)
annotation class Entity(val tableName:String="",val indices:Array<Index> = [])
annotation class Index(val value:Array<String>,val unique:Boolean=false)
annotation class Insert(val onConflict:Int=0)
object OnConflictStrategy { const val REPLACE:Int=1 }
annotation class PrimaryKey(val autoGenerate:Boolean=false)
annotation class Query(val value:String)
open class RoomDatabase
object Room { fun <T:RoomDatabase> databaseBuilder(c:Context,k:Class<T>,n:String)=Builder<T>(); class Builder<T>{ fun addMigrations(vararg m:androidx.room.migration.Migration)=this; fun build():T=error("stub") } }
suspend fun <T> RoomDatabase.withTransaction(block:suspend()->T):T=block()
'''),
  w(stubs,'androidx/room/migration/Migration.kt','package androidx.room.migration\nopen class Migration(val startVersion:Int,val endVersion:Int){open fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){}}\n'),
  w(stubs,'androidx/sqlite/db/SupportSQLiteDatabase.kt','package androidx.sqlite.db\ninterface SupportSQLiteDatabase{fun execSQL(sql:String)}\n'),
  ROOT/'app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt',
  ROOT/'app/src/main/java/vn/nghetruyen/app/core/model/Models.kt',
  *sorted((ROOT/'app/src/main/java/vn/nghetruyen/app/ai/vietphrase').glob('*.kt')),
  ROOT/'app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt',
  ROOT/'app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt',
 ]
 lib=Path(K).resolve().parents[1]/'lib/kotlinx-coroutines-core-jvm.jar'
 cmd=[K,*map(str,files)]
 if lib.is_file(): cmd += ['-cp',str(lib)]
 cmd += ['-d',str(temp/'persistence.jar')]
 run(cmd,300)
 print('M3_PERSISTENCE_KOTLIN_STATIC_OK')

def exact_migration_sql()->list[str]:
 text=(ROOT/'app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt').read_text(encoding='utf-8')
 start=text.index('val MIGRATION_13_14')
 end=text.index('\n        private fun normalizeFollowingDefaults',start)
 block=text[start:end]
 statements=[]
 for m in re.finditer(r'db\.execSQL\(\s*(?:"""(.*?)"""\.trimIndent\(\)|"((?:[^"\\]|\\.)*)")\s*,?\s*\)',block,re.S):
  sql=m.group(1) if m.group(1) is not None else bytes(m.group(2),'utf-8').decode('unicode_escape')
  statements.append(sql.strip())
 if len(statements)<10: raise AssertionError(f'Chỉ tách được {len(statements)} câu SQL migration')
 return statements

def execute_migration():
 db=sqlite3.connect(':memory:')
 db.executescript('''
 CREATE TABLE viet_phrase_rules(
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,source TEXT NOT NULL,target TEXT NOT NULL,
  priority INTEGER NOT NULL,enabled INTEGER NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL
 );
 CREATE UNIQUE INDEX index_viet_phrase_rules_source ON viet_phrase_rules(source);
 INSERT INTO viet_phrase_rules(id,source,target,priority,enabled,createdAt,updatedAt) VALUES(7,'天道','Thiên Đạo',12,1,100,101);
 ''')
 for sql in exact_migration_sql(): db.execute(sql)
 cols={row[1]:(row[2],row[3],row[4]) for row in db.execute('PRAGMA table_info(viet_phrase_rules)')}
 for name in ('kind','scope','storyId','matchMode','ignoreCase'): assert name in cols,name
 row=db.execute('SELECT id,source,target,kind,scope,storyId,matchMode,ignoreCase FROM viet_phrase_rules').fetchone()
 assert row==(7,'天道','Thiên Đạo','VIET_PHRASE','GLOBAL','','LITERAL',0),row
 tables={r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
 assert {'viet_phrase_snapshots','viet_phrase_dictionary_state','viet_phrase_suggestions'}<=tables,tables
 indexes={r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='index'")}
 assert 'index_viet_phrase_rules_kind_scope_storyId_source_matchMode' in indexes
 print('M3_MIGRATION_13_14_SQLITE_OK statements=%d'%len(exact_migration_sql()))

def main():
 if not K: raise SystemExit('M3_PERSISTENCE_STATIC_BLOCKED: thiếu kotlinc')
 with tempfile.TemporaryDirectory(prefix='nghe-m3-persistence-') as td: compile_kotlin(Path(td))
 execute_migration()
 print('ROADMAP_M3_PERSISTENCE_GATE=PASS')
if __name__=='__main__': main()
