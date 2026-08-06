#!/usr/bin/env python3
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")


def write(root: Path, relative: str, content: str) -> Path:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def main() -> None:
    if not KOTLINC:
        print("AI_SETTINGS_STATIC_SKIPPED")
        return
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        files = [
            write(root, "android/content/Context.kt", """package android.content
open class Context
"""),
            write(root, "kotlinx/coroutines/flow/Flow.kt", """package kotlinx.coroutines.flow
interface Flow<T>
fun <T, R> Flow<T>.map(transform: suspend (T) -> R): Flow<R> = object : Flow<R> {}
suspend fun <T> Flow<T>.first(): T = error("stub")
"""),
            write(root, "androidx/datastore/core/DataStore.kt", """package androidx.datastore.core
import kotlinx.coroutines.flow.Flow
class DataStore<T>(val data: Flow<T>)
"""),
            write(root, "androidx/datastore/preferences/core/Preferences.kt", """package androidx.datastore.preferences.core
import androidx.datastore.core.DataStore

open class Preferences {
    class Key<T>(val name: String)
    private val values = mutableMapOf<Key<*>, Any?>()
    @Suppress("UNCHECKED_CAST") operator fun <T> get(key: Key<T>): T? = values[key] as T?
    internal fun <T> put(key: Key<T>, value: T) { values[key] = value }
    internal fun removeKey(key: Key<*>) { values.remove(key) }
}
class MutablePreferences : Preferences() {
    operator fun <T> set(key: Key<T>, value: T) = put(key, value)
    fun <T> remove(key: Key<T>) = removeKey(key)
}
fun stringPreferencesKey(name: String) = Preferences.Key<String>(name)
fun booleanPreferencesKey(name: String) = Preferences.Key<Boolean>(name)
fun floatPreferencesKey(name: String) = Preferences.Key<Float>(name)
fun intPreferencesKey(name: String) = Preferences.Key<Int>(name)
suspend fun DataStore<Preferences>.edit(transform: suspend (MutablePreferences) -> Unit) { transform(MutablePreferences()) }
"""),
            write(root, "androidx/datastore/preferences/PreferencesDataStore.kt", """package androidx.datastore.preferences
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
private class EmptyFlow<T> : Flow<T>
class PreferencesDataStoreDelegate : ReadOnlyProperty<Context, DataStore<Preferences>> {
    override fun getValue(thisRef: Context, property: KProperty<*>): DataStore<Preferences> = DataStore(EmptyFlow())
}
fun preferencesDataStore(name: String): PreferencesDataStoreDelegate = PreferencesDataStoreDelegate()
"""),
            write(root, "vn/nghetruyen/app/core/model/Models.kt", """package vn.nghetruyen.app.core.model
enum class AudioInterruptionMode { PAUSE, CONTINUE_DUCKED }
enum class ReaderLayoutMode { SCROLL, PAGED }
enum class ReaderThemeMode { SYSTEM, LIGHT, DARK, SEPIA }
enum class SceneMusicPlaybackMode { SEQUENTIAL, SHUFFLE, SMART_AVOID_REPEAT }
data class ReaderDisplaySettings(
    val theme: ReaderThemeMode = ReaderThemeMode.SYSTEM,
    val layoutMode: ReaderLayoutMode = ReaderLayoutMode.SCROLL,
    val fontSizeSp: Int = 20,
    val lineHeightPercent: Int = 155,
    val horizontalPaddingDp: Int = 12,
    val paragraphSpacingDp: Int = 8,
    val keepScreenOn: Boolean = false,
    val volumeKeysNavigate: Boolean = false,
)
"""),
            ROOT / "app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt",
        ]
        result = subprocess.run(
            [KOTLINC, *map(str, files), "-d", str(root / "out.jar")],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        if result.returncode:
            print(result.stdout)
            print(result.stderr)
            raise SystemExit(result.returncode)
    print("AI_SETTINGS_STATIC_COMPILE_OK")


if __name__ == "__main__":
    main()
