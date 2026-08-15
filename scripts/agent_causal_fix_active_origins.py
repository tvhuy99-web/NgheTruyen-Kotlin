from pathlib import Path

path = Path("source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt")
text = path.read_text()
old = '''        screenRotationEventsDiscarded = (events.size - retained.size).coerceAtLeast(0).toLong()
        screenHandoffEventsRetained = retained.size.toLong()
        screenGeneration = nextGeneration
        events.clear()
        retained.forEach(events::addLast)
        selected.forEach { traceId -> rememberOrigin(traceOrigins, traceId, nextGeneration, overwrite = true) }
        selected
'''
new = '''        val activeOperationIds = linkedSetOf<String>()
        retained.forEach { event ->
            val operationId = DiagnosticOperationContract.id(event) ?: return@forEach
            val state = DiagnosticOperationContract.state(event)
            val upper = event.name.uppercase(Locale.ROOT)
            if (state in TERMINAL_STATES || state == null && isLegacyTerminal(upper)) {
                activeOperationIds.remove(operationId)
            } else {
                activeOperationIds.add(operationId)
            }
        }

        screenRotationEventsDiscarded = (events.size - retained.size).coerceAtLeast(0).toLong()
        screenHandoffEventsRetained = retained.size.toLong()
        screenGeneration = nextGeneration
        events.clear()
        retained.forEach(events::addLast)
        selected.forEach { traceId -> rememberOrigin(traceOrigins, traceId, nextGeneration, overwrite = true) }
        activeOperationIds.forEach { operationId ->
            rememberOrigin(operationOrigins, operationId, nextGeneration, overwrite = true)
        }
        selected
'''
if text.count(old) != 1:
    raise SystemExit(f"active-origin exact-match failed: {text.count(old)}")
path.write_text(text.replace(old, new, 1))
print("active operation origins migrate with causal handoff")
