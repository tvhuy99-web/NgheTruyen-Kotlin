from pathlib import Path


def replace_exact(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match in {path}, found {count}")
    file.write_text(text.replace(old, new))


runtime = "source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt"
replace_exact(
    runtime,
    '''                    "effectiveMemoryBudgetBytes" to (if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) maxOf(manifest.runtime.memoryBudgetBytes, 64 * 1024 * 1024) else manifest.runtime.memoryBudgetBytes).toString(),
                    "hardInstructionLimit" to (manifest.runtime.instructionBudget.toLong() * if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) 64L else 16L).toString(),''',
    '''                    "effectiveMemoryBudgetBytes" to effectiveRhinoHeapBudgetBytes(manifest).toString(),
                    "nativeLuaHostHeapOverheadBytes" to (if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) NATIVE_LUA_HOST_HEAP_OVERHEAD_BYTES else 0).toString(),
                    "hardInstructionLimit" to (manifest.runtime.instructionBudget.toLong() * if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) 64L else 16L).toString(),''',
)
replace_exact(
    runtime,
    '''    private fun sandboxExecutor(manifest: SourceManifest, timeoutMs: Long): SafeRhinoExecutor = SafeRhinoExecutor(
        policy = JsSandboxPolicy(
            maxInstructions = manifest.runtime.instructionBudget.toLong(),
            wallClockTimeoutMs = timeoutMs,
            instructionObserverThreshold = 1_000,
            maxHeapGrowthBytes = (if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) maxOf(manifest.runtime.memoryBudgetBytes, 64 * 1024 * 1024) else manifest.runtime.memoryBudgetBytes).toLong(),
            maxResultUnits = (if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) maxOf(manifest.runtime.memoryBudgetBytes, 64 * 1024 * 1024) else manifest.runtime.memoryBudgetBytes).coerceAtLeast(1),''',
    '''    private fun effectiveRhinoHeapBudgetBytes(manifest: SourceManifest): Int {
        if (manifest.runtime.mode != SourceRuntimeMode.NATIVE_LUA_COMPAT) return manifest.runtime.memoryBudgetBytes
        val sourceBudget = maxOf(manifest.runtime.memoryBudgetBytes, NATIVE_LUA_MIN_SOURCE_HEAP_BYTES)
        return (sourceBudget.toLong() + NATIVE_LUA_HOST_HEAP_OVERHEAD_BYTES)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun sandboxExecutor(manifest: SourceManifest, timeoutMs: Long): SafeRhinoExecutor = SafeRhinoExecutor(
        policy = JsSandboxPolicy(
            maxInstructions = manifest.runtime.instructionBudget.toLong(),
            wallClockTimeoutMs = timeoutMs,
            instructionObserverThreshold = 1_000,
            maxHeapGrowthBytes = effectiveRhinoHeapBudgetBytes(manifest).toLong(),
            // Host-adapter overhead must not enlarge the source result budget.
            maxResultUnits = (if (manifest.runtime.mode == SourceRuntimeMode.NATIVE_LUA_COMPAT) maxOf(manifest.runtime.memoryBudgetBytes, NATIVE_LUA_MIN_SOURCE_HEAP_BYTES) else manifest.runtime.memoryBudgetBytes).coerceAtLeast(1),''',
)
replace_exact(
    runtime,
    '''    companion object {
''',
    '''    companion object {
        private const val NATIVE_LUA_MIN_SOURCE_HEAP_BYTES = 64 * 1024 * 1024
        // NativeV2 is host-generated JS layered on top of the source. Keep its transient Rhino
        // allocations outside the source's own 64 MiB budget while preserving result/output caps.
        private const val NATIVE_LUA_HOST_HEAP_OVERHEAD_BYTES = 32 * 1024 * 1024
''',
)

test = "source-lua/src/test/kotlin/vn/nghetruyen/source/lua/SangTacVietTocBudgetRegressionTest.kt"
replace_exact(
    test,
    'assertTrue("expected last chapter in normalized output", encoded.contains("Chương 100"))',
    'assertTrue("expected last chapter in normalized output", encoded.contains("Chương 1000"))',
)

print("Native Lua host heap overhead patch applied")
