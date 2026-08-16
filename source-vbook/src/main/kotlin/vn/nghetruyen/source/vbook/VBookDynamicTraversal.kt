package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonValue





object VBookDynamicActionCollector {
    fun collect(
        root: JsonValue,
        maxDepth: Int = 24,
        maxNodes: Int = 50_000,
        maxActions: Int = 4_096,
    ): List<VBookDynamicAction> {
        require(maxDepth in 1..128)
        require(maxNodes in 1..500_000)
        require(maxActions in 1..50_000)
        val actions = mutableListOf<VBookDynamicAction>()
        var nodes = 0

        fun visit(value: JsonValue, depth: Int) {
            require(depth <= maxDepth) { "VBOOK_DYNAMIC_RESULT_DEPTH_LIMIT" }
            nodes++
            require(nodes <= maxNodes) { "VBOOK_DYNAMIC_RESULT_NODE_LIMIT" }
            VBookDynamicActionParser.parse(value)?.let { action ->
                actions += action
                require(actions.size <= maxActions) { "VBOOK_DYNAMIC_ACTION_LIMIT" }
            }
            when (value) {
                is JsonValue.Arr -> value.values.forEach { visit(it, depth + 1) }
                is JsonValue.Obj -> value.values.values.forEach { visit(it, depth + 1) }
                else -> Unit
            }
        }

        visit(root, 0)
        return actions.distinctBy {
            listOf(
                it.scriptPath,
                it.input,
                it.title,
                it.data,
                it.hasDataArgument.toString(),
                it.type.orEmpty(),
            )
        }
    }
}
