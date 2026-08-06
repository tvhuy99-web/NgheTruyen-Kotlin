# VietPhrase profiler baseline

Status: **PASS**

This baseline belongs to roadmap Milestone 3 source-side acceptance. Android runtime and physical-device measurements remain deferred until the post-Milestone-9 validation phase.

## Accepted workload

- Rules indexed: 100,000
- Lookup sample: rule at the end of the generated dictionary
- Required build-and-lookup budget: under 20,000 ms
- Latest accepted offline run: 889 ms on the available JVM environment
- Data structure: prefix trie with deterministic longest-match and layer-priority selection
- Cache: bounded LRU; diagnostic traces are never served from a non-trace cache entry

## Correctness workloads

The profiler gate is executed together with the XPK golden fixtures covering:

- longest match and dictionary-layer priority;
- Names and Pronouns captures in Luật Nhân templates;
- global and per-story scope;
- one-meaning and multi-meaning output;
- Chinese-to-Vietnamese punctuation normalization;
- a non-cascading base pass;
- exactly one final AIReplace pass;
- trace limits and cache/trace isolation.

## Safety budgets

- At most 1,000,000 persisted/imported rules.
- Bounded archive, entry and decompressed sizes.
- Bounded dictionary-state and snapshot counts.
- AIReplace cycles are audited and never executed recursively.
- Template placeholders are validated before commit.

The executable source of truth is `scripts/check_roadmap_milestone3_vietphrase_complete.py`.
