# vBook S2-S4 implementation status

This document records implementation truth for the vBook compatibility subsystem. It is not a marketing compatibility claim.

## S2 — corpus, contract detection and feature matrix

Implemented:

- multi-repository `repository.json` aggregation without hard-coded authors or website domains;
- strict offline corpus acquisition that preserves original `plugin.zip` bytes and catalog provenance;
- tolerant catalog/plugin parsing, including missing/legacy metadata;
- separate legacy/current contract profiles;
- script-level corpus scanning and evidence for host APIs, dynamic scripts and restricted Rhino syntax;
- granular feature rows for fetch, DOM mutation/collections, Browser request waiting, WebSocket frames/headers and Quick Translator subfeatures;
- implementation state kept separate from differential certification state.

Corpus acquisition is strict by default. Upstream/catalog failures can be recorded with `--allow-errors`, but such a run is not a complete corpus proof.

## S3 — ABI

Implemented or materially wired:

- exact string positional arguments for declared and dynamic scripts;
- opaque current `data2` continuation tokens; no page-number arithmetic;
- dynamic `{script,input,data}` first-call semantics and subsequent `data2` replacement;
- legacy/current Response envelope handling;
- descriptor and legacy config parsing plus host connection settings (`thread_num`, `timeout`, `delay`);
- config service keyed by stable repository/package identity so package updates do not reset user values;
- `load('crypto.js')` built-in behavior and CryptoJS compatibility surface;
- exact raw fetch bytes, charset conversion, base64/blob, status text and final request metadata without replaying POSTs;
- vBook-only public Internet policy with private/LAN address blocking and scoped legacy HTTP;
- DOM collection callbacks, attributes and observable removals;
- Browser `loadHtml(html, baseUrl)` compatibility and `waitUrl()` over captured request URLs;
- base offline `Qt.translate(text, 'vp'|'hv')` routing separated from generic AI translation;
- transport-level text/binary WebSocket frame preservation.

Still partial:

- JavaScript WebSocket object is not yet fully reference-shaped: constructor headers and `{type,data}` frame objects still require final bridge wiring;
- Quick Translator advanced extras and exact segment-offset output are not yet reference-certified;
- `metadata.encrypt` is preserved, but no undocumented encrypted-script payload codec is invented. Readable ZIPs are valid even when the flag is present;
- app text-reader adapter currently surfaces novel/chinese-novel. Comic/video/audio/TTS/translate roles are executable and fixture-tested but need their product-specific provider/UI adapters.

## S4 — differential proof and lifecycle

Implemented:

- immutable original artifact archive;
- candidate → active / quarantined transitions;
- previous-known-good tracking and atomic rollback;
- restart recovery by enumerating only ACTIVE identity pointers;
- update planner based on stable repository/package identity;
- non-mutating install preview with profile/state/blocking feature output;
- candidates requiring PARTIAL ABI features are quarantined without replacing the active known-good source;
- deterministic current novel ABI fixtures plus comic/TTS/translate role fixtures;
- official vBook `/extension/test` capture tooling;
- differential feature-coverage gate separating implementation blockers, uncovered implementations and reference-server failures;
- one-command compatibility lab orchestration.

Not yet certified in this branch:

- the full live repository corpus has not yet been fetched and audited in the final environment;
- official reference captures have not yet been produced for all required feature rows;
- legacy reference capture mode still needs a proven official tester endpoint/path;
- consolidated Gradle/Android build and tests are intentionally deferred until the implementation pass is complete.

## Product wiring

A separate persistent `VBookSourcePlatform` now exists beside the older/native Source Platform path. Active vBook novel sources are restored from their own artifact store and merged only at `SourceRegistry`/`StorySource` normalization.

The legacy UI import flow still calls the old vBook-to-SourcePack importer. New repository/install transaction APIs exist, but redirecting the large `AppViewModel` import flow is intentionally deferred to the final consolidated wiring pass so the old migration path is not half-replaced.

## Full compatibility claim rule

Do not claim full vBook corpus parity unless all of the following are true:

1. strict corpus acquisition is complete;
2. no corpus-required feature is `PARTIAL` or package-blocked;
3. every corpus-required implemented feature has at least one valid official reference case;
4. semantic differential comparison passes;
5. consolidated project tests/build pass;
6. upstream-dead/auth-required sources are reported separately from engine incompatibility.
