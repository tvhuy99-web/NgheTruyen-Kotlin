package vn.nghetruyen.app.diagnostics

import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock
import org.json.JSONObject
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.sources.ChapterCatalogIndex
import kotlin.system.measureNanoTime


object PerformanceDiagnostics {
    data class Snapshot(
        val createdAt: Long,
        val uptimeMillis: Long,
        val pssKiB: Int,
        val javaHeapUsedBytes: Long,
        val batteryPercent: Int,
        val charging: Boolean,
        val chapterIndexBuildMillis: Double,
        val chapterSearchP95Millis: Double,
        val chapterCount: Int,
    ) {
        fun toJson(): String = JSONObject()
            .put("createdAt", createdAt)
            .put("uptimeMillis", uptimeMillis)
            .put("pssKiB", pssKiB)
            .put("javaHeapUsedBytes", javaHeapUsedBytes)
            .put("batteryPercent", batteryPercent)
            .put("charging", charging)
            .put("chapterIndexBuildMillis", chapterIndexBuildMillis)
            .put("chapterSearchP95Millis", chapterSearchP95Millis)
            .put("chapterCount", chapterCount)
            .toString(2)
    }

    fun run(context: Context, chapterCount: Int = 10_000): Snapshot {
        val chapters = List(chapterCount.coerceIn(1_000, 20_000)) { index ->
            ChapterSummary(
                id = "benchmark-$index",
                storyId = "benchmark",
                index = index,
                title = "Chương ${index + 1}: Hành trình số ${index % 97}",
                url = "offline://benchmark/$index",
            )
        }
        lateinit var catalog: ChapterCatalogIndex
        val buildNanos = measureNanoTime { catalog = ChapterCatalogIndex(chapters) }
        val samples = List(40) { run ->
            measureNanoTime { catalog.search(if (run % 2 == 0) "chuong 999" else "hanh trinh 42") } / 1_000_000.0
        }.sorted()
        val p95 = samples[((samples.size - 1) * 0.95).toInt()]
        val runtime = Runtime.getRuntime()
        val battery = context.getSystemService(BatteryManager::class.java)
        val batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val chargingStatus = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
        return Snapshot(
            createdAt = System.currentTimeMillis(),
            uptimeMillis = SystemClock.elapsedRealtime(),
            pssKiB = Debug.getPss().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            javaHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            batteryPercent = batteryPercent,
            charging = chargingStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                chargingStatus == BatteryManager.BATTERY_STATUS_FULL,
            chapterIndexBuildMillis = buildNanos / 1_000_000.0,
            chapterSearchP95Millis = p95,
            chapterCount = chapters.size,
        )
    }
}
