# VBook compatibility: S2–S4

This document records the implementation contract for vBook compatibility. It is intentionally stricter than a feature checklist: an API can be implemented without being certified, and a dead upstream website is not an engine incompatibility.

## Goal

NgheTruyen hosts unmodified vBook extensions. Website-specific behavior stays in the extension. The host changes only when the vBook compatibility contract changes or the host implementation is wrong.

The host must never add per-site branches such as `if (domain == ...)` to make one extension work.

## S2: corpus audit and contract detection

### Repository corpus

`scripts/fetch_vbook_corpus.py` starts from the official repository index and treats every catalog entry as data. It does not hard-code repository authors, extension names or source domains.

Acquisition is deliberately separate from execution. The script:

- records repository/catalog/package provenance and SHA-256 hashes;
- limits index, catalog, ZIP and expanded sizes;
- rejects ZIP traversal, symlinks, excessive entries and suspicious compression ratios;
- extracts only `plugin.json` and JavaScript source files used by the offline analyzer;
- never executes extension code.

`VBookCorpusAuditMain` consumes the extracted corpus offline and emits a feature matrix.

### Legacy/current profile detector

`VBookContractDetector` is deterministic and evidence-based. It uses multiple schema signals and returns `UNKNOWN` instead of guessing when legacy/current evidence is ambiguous.

Legacy signals include old `metadata.language`, legacy `tag`, primitive config values and flat 200/403 response-code usage. Current signals include descriptor config, current-only action/content types, `explore`, and current-schema fields such as `nsfw`.

The detector intentionally does not classify an extension as current merely because it calls `Response.success`: both generations may use a helper with that name.

### Feature matrix

The corpus analyzer records evidence for:

- contract/content types;
- legacy cleartext HTTP;
- config styles;
- dynamic script references and `load()`;
- response contracts;
- fetch query/timeout/header/status/charset/base64/blob/request metadata;
- DOM, storage, cookies, Browser, Graphics, WebSocket, Qt and Crypto APIs;
- JavaScript syntax rejected by the reference Rhino contract.

`VBookEngineFeatureMatrix` keeps two independent dimensions:

1. **Implementation**: whether NgheTruyen has code for a feature.
2. **Certification**: whether differential cases prove semantic parity with the reference runtime.

`IMPLEMENTED` never means `CERTIFIED` automatically.

## S3: compatibility ABI

### Positional string arguments

`VBookCompatibilityRuntime` invokes extension scripts through `Script.execute()` using the exact positional string arguments required by the selected contract profile.

The old NgheTruyen page conversion (`(page - 1) * 30`) is bypassed. Current vBook `data2` is an opaque continuation string and is passed back unchanged.

### Dynamic scripts

`VBookDynamicActionCollector` finds `{title,input,script}` descriptors throughout nested result trees with depth/node/action limits. Arbitrary package-local `.js` scripts are executable through `executeDynamic`; known action names are only convenience wrappers.

All script paths go through the package path-safety rules.

### Config

Current descriptor config values are resolved from defaults, persisted values and runtime overrides, then injected as safe JavaScript constants. Values are encoded as JSON strings; raw values are never interpolated into JavaScript source.

Legacy primitive config remains a separate contract signal.

### Response profiles

Current extension responses use code `0` for success and `1` for error. Legacy extension responses use flat `200`/`403` semantics.

The outer official `/extension/test` REST response also uses `200`/`403`; the differential adapter keeps that wrapper distinct from the nested extension Response object.

### `load('crypto.js')`

`crypto.js` is treated as a built-in compatibility resource. Package-local loads remain path-safe, and recursive loads from loaded scripts are rejected to match the documented current-engine restriction.

### Fetch parity

The generic network layer already preserves raw `ByteArray` response bodies. `VBookRawNetworkBroker` adds a VBook-only short-lived raw-response cache so the text-oriented JavaScript host can expose byte-exact operations without replaying requests.

The vBook fetch wrapper supports:

- query objects;
- per-request timeout;
- object-body JSON encoding;
- `header(name)`;
- transport `statusText`;
- exact `text(charset)` and `html(charset)` decoding from the captured bytes;
- exact `base64()` and `blob()` from the captured bytes;
- actual final `response.request.url` and request headers after transport defaults, cookies and redirect handling.

Cached representation requests are internal operations and never leave the process. Internal `X-Nghe-VBook-*` control headers are removed before an upstream request and hidden from extension code.

### Browser, crypto and related host APIs

The existing mature `VBookJsRuntime` remains the implementation for Browser, cookie/session, storage, Crypto/CryptoJS, Graphics, WebSocket, Script and Qt bridges. The compatibility facade owns contract semantics instead of duplicating those brokers.

### Public Internet sandbox

vBook extensions commonly use a primary site plus API/CDN hosts. `SourceNetworkCapability.publicInternet` and `allowCleartext` therefore exist as scoped capabilities, not as domain exceptions.

Only `VBOOK_JS_COMPAT` may enable those capabilities. Native/declarative sources keep declared-origin behavior.

Even in vBook public-Internet mode, `PublicAddressPolicy` still rejects loopback, RFC1918/site-local, link-local, multicast, documentation/test ranges and private IPv6 ranges. Public Internet access never grants LAN/app-internal access.

`VBookHostManifestFactory` creates the internal execution envelope for an unmodified extension and derives legacy cleartext need from metadata, config and script literals. This internal manifest is not a converted source format; the original plugin remains authoritative.

### Encrypted distribution payloads

`metadata.encrypt` is recognized and audited, but the encrypted ZIP/script payload algorithm is not guessed. `VBookPackageReader` has a decoder boundary; plaintext packages run normally, while an encrypted/non-UTF-8 payload without a proven decoder fails explicitly and is quarantined.

Until a reference format is proven, `METADATA_ENCRYPT` remains `PACKAGE_LAYER_PENDING` and must block any claim of full corpus parity when the corpus requires it.

## S4: differential certification

### Reference capture

`scripts/capture_vbook_reference.py` submits exact extension source trees to the official `/extension/test` API and records:

- test plan hash;
- source file hashes;
- profile, feature tags, script and positional arguments;
- the raw official response.

Reference capture is an acquisition step. Regression tests consume the immutable capture offline, so CI does not require the reference server to be online.

`VBookReferenceCaptureParser` validates capture schema/provenance before it becomes an expected snapshot.

### Semantic comparison

`VBookDifferentialFixtures` converts official captures into the shared `source-compat-testkit` representation. It compares semantic data and opaque continuation values rather than requiring irrelevant JSON serialization order to match.

`VBookCertificationEngine` certifies a feature only when every differential case that covers that feature matches the reference. A required feature with no case remains `UNTESTED`; any mismatch becomes `DIVERGED`.

### Candidate validation

Before activation, a candidate package is:

1. read by the bounded ZIP reader;
2. parsed as vBook metadata/scripts;
3. classified as legacy/current;
4. checked for required and referenced dynamic scripts;
5. compiled with Rhino without executing top-level extension code;
6. checked for syntax that the reference engine itself rejects.

Validation and activation operate on the same original ZIP bytes.

### Activate / quarantine / rollback

`VBookUpdateCoordinator` never accepts a separately supplied `plugin.json`/script tree for validation. It extracts and validates the exact package bytes that will be archived and activated.

The transaction is:

```text
candidate ZIP bytes
  -> safe package parse
  -> contract/compile validation
  -> immutable archive stage
  -> archive SHA-256 verification
  -> atomic active-pointer commit
```

A bad candidate is archived for diagnostics and quarantined without replacing the active extension.

A good update keeps the former active version as `PREVIOUS_KNOWN_GOOD`. Rollback restores that descriptor and quarantines the failed current version.

`FileSourceArtifactStore` provides persistent storage:

- content-addressed immutable blobs;
- artifact-to-blob archive pointers;
- immutable descriptor records;
- atomic identity active/previous pointer files.

Descriptor/blob writes happen before the identity pointer. Therefore a crash before the final pointer move can leave an orphan candidate, but cannot replace a working active extension with half-written state.

## Compatibility status vocabulary

Engine compatibility and website health must remain separate.

Examples:

- `SUPPORTED + SITE_OK`
- `SUPPORTED + UPSTREAM_DEAD`
- `SUPPORTED + AUTH_REQUIRED`
- `UNSUPPORTED + VBOOK_HOST_API_UNSUPPORTED`
- `DIVERGED + reference case id`
- `QUARANTINED + ARTIFACT_INVALID`

A site timeout must never be reported as proof that the vBook ABI is incompatible.

## Remaining proof gate

The implementation intentionally does not claim `FULL VBOOK` yet. Full corpus parity requires:

- an acquired corpus report for the repository set under test;
- no required `PARTIAL` or `PACKAGE_LAYER_PENDING` feature;
- every required implemented feature certified by differential cases;
- no `DIVERGED` case;
- a final consolidated build/test run.

This keeps the product claim tied to evidence rather than to a manually maintained feature list.
