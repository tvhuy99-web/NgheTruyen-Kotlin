from pathlib import Path


def replace_exact(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match in {path}, found {count}")
    file.write_text(text.replace(old, new))


# Generic NativeV2 split limit. The limit can be dynamic ($vars.*), while an omitted limit keeps
# legacy String.split behavior unchanged for every other source.
adapter = "source-lua/src/main/resources/vn/nghetruyen/source/lua/native_v2_adapter.lua"
replace_exact(
    adapter,
    ' if(op==="split")return String(value==null?"":value).split(String(opSpec.separator!==undefined?opSpec.separator:(opSpec.delimiter||",")));',
    ''' if(op==="split"){
  var splitText=String(value==null?"":value),splitSep=String(opSpec.separator!==undefined?opSpec.separator:(opSpec.delimiter||","));
  if(opSpec.limit===undefined)return splitText.split(splitSep);
  var resolvedLimit=Number(resolveDynamic(ctx,opSpec.limit,value));
  if(!isFinite(resolvedLimit))fail(stage,"split limit không hợp lệ",String(opSpec.limit));
  resolvedLimit=Math.max(0,Math.min(20000,Math.floor(resolvedLimit)));
  return splitText.split(splitSep,resolvedLimit);
 }''',
)

# Trusted compatibility migration after source validation: STV's page window is already calculated
# before the chapter transform, so only the first probe_end records are required. Avoid creating and
# mapping ~1000 chapter records merely to return 100 current-page items plus one next-page probe.
importer = "source-lua/src/main/kotlin/vn/nghetruyen/source/lua/NativeLuaSourceImporter.kt"
replace_exact(
    importer,
    '''        val metadata = packageTable.get("metadata").checktable()
        val source = sourceTable
        val adapterSandbox = LuaSandbox(''',
    '''        val metadata = packageTable.get("metadata").checktable()
        val source = sourceTable
        val compatibilityMigrations = applyHostCompatibilityMigrations(source)
        val adapterSandbox = LuaSandbox(''',
)
replace_exact(
    importer,
    '''        val warnings = buildList {
            addAll(vBook.warnings)''',
    '''        val warnings = buildList {
            addAll(vBook.warnings)
            if (compatibilityMigrations.isNotEmpty()) {
                add("Host compatibility migrations: ${compatibilityMigrations.joinToString()}")
            }''',
)
replace_exact(
    importer,
    '''    private fun promoteFullInternalAuthority(source: LuaTable) {
        val permissions = source.get("permissions").let { if (it.istable()) it.checktable() else LuaTable() }
        permissions.set("browser", LuaValue.TRUE)
        permissions.set("storage", LuaValue.TRUE)
        permissions.set("network_capture", LuaValue.TRUE)
        source.set("permissions", permissions)
    }

    private data class ModuleBundle(''',
    '''    private fun promoteFullInternalAuthority(source: LuaTable) {
        val permissions = source.get("permissions").let { if (it.istable()) it.checktable() else LuaTable() }
        permissions.set("browser", LuaValue.TRUE)
        permissions.set("storage", LuaValue.TRUE)
        permissions.set("network_capture", LuaValue.TRUE)
        source.set("permissions", permissions)
    }

    /**
     * Applies narrowly-scoped host compatibility migrations to the sanitized source table after
     * package validation. Original Lua/package bytes remain untouched and export provenance stays exact.
     */
    private fun applyHostCompatibilityMigrations(source: LuaTable): List<String> {
        val hooks = source.get("hooks")
        if (!hooks.istable() || !hooks.get("stv_page_window").isfunction()) return emptyList()
        val actions = source.get("actions")
        if (!actions.istable()) return emptyList()
        val chapters = actions.get("chapters")
        if (!chapters.istable()) return emptyList()
        val steps = chapters.get("steps")
        if (!steps.istable()) return emptyList()

        val stepTable = steps.checktable()
        for (stepIndex in 1..stepTable.length()) {
            val transform = stepTable.get(stepIndex).get("transform")
            if (!transform.istable()) continue
            val operations = transform.get("operations")
            if (!operations.istable()) continue
            val operationTable = operations.checktable()
            for (operationIndex in 1..operationTable.length()) {
                val operation = operationTable.get(operationIndex)
                if (!operation.istable()) continue
                val spec = operation.checktable()
                if (spec.get("op").optjstring("") != "split") continue
                if (spec.get("separator").optjstring("") != "-//-") continue
                if (!spec.get("limit").isnil()) return emptyList()
                spec.set("limit", LuaValue.valueOf("\$vars.window.probe_end"))
                return listOf("stv-chapter-split-probe-window-v1")
            }
        }
        return emptyList()
    }

    private data class ModuleBundle(''',
)

# Regression proves the full ~168 KiB response is supplied but only page 1 + probe are materialized.
test = "source-lua/src/test/kotlin/vn/nghetruyen/source/lua/SangTacVietTocBudgetRegressionTest.kt"
replace_exact(
    test,
    '''        assertTrue("expected first chapter in normalized output", encoded.contains("Chương 1"))
        assertTrue("expected last chapter in normalized output", encoded.contains("Chương 100"))
        assertTrue("expected chapter URL rooted at the story", encoded.contains(storyUrl))''',
    '''        assertTrue("expected first chapter in normalized output", encoded.contains("Chương 1"))
        assertTrue("expected last chapter on page 1", encoded.contains("Chương 100"))
        assertTrue("page 1 must not eagerly materialize chapter 101", !encoded.contains("Chương 101"))
        assertTrue("expected next page token", encoded.contains("__vbook_stv_toc=2"))
        assertTrue("expected chapter URL rooted at the story", encoded.contains(storyUrl))''',
)

# Import-level assertion makes the migration observable without depending only on runtime timing.
compat = "source-lua/src/test/kotlin/vn/nghetruyen/source/lua/XpkDefaultSourceCompatibilityTest.kt"
replace_exact(
    compat,
    '''        assertTrue(toc.contains("page: url || \\\"\\\""))
    }''',
    '''        assertTrue(toc.contains("page: url || \\\"\\\""))
        val core = requireNotNull(pack.entries["src/native_v2_core.js"]).toString(Charsets.UTF_8)
        assertTrue("STV chapter split should be bounded by the page probe window", core.contains("\\\"limit\\\":\\\"\\$vars.window.probe_end\\\""))
    }''',
)

print("STV bounded split migration applied")
