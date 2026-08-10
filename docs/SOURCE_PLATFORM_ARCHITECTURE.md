# Source Platform Architecture

Status: S0 baseline

## Goal

NgheTruyen hosts multiple source ecosystems without coupling application code to individual websites. A website change must be fixed in the source artifact whenever the artifact contract is still valid. Compatibility engines change only when their implementation is wrong or the external contract evolves.

## Non-negotiable invariants

1. Native, vBook and Legado are independent source ecosystems.
2. Ecosystem artifacts are never converted into another ecosystem as the execution format.
3. Engines are site-agnostic. Production engine code must not branch on a website host, source display name or specific extension identity.
4. Raw artifacts are retained. Normalized representations are disposable caches.
5. Compatibility and upstream health are separate dimensions.
6. Unknown syntax or host API usage fails explicitly. It must never silently fall through to a different parser.
7. Untrusted source code receives capabilities through brokers. It never receives Java/Android object access by default.
8. Cookie, storage, config and secrets are partitioned by installed source instance.
9. Updates are transactional and keep a previous known-good artifact.
10. Reference behavior is pinned by profile/version/corpus. "Latest" is not a reproducible compatibility target.

## Layering

```text
Application UI / Library / Reader
              |
        Normalized Source API
              |
   +----------+-----------+
   |          |           |
 Native     vBook       Legado
 Engine     Engine       Engine
   |          |           |
   +----------+-----------+
              |
       Host Capabilities
 Network / Cookie / Browser / Storage / Secrets / Crypto / Diagnostics
```

The normalized API models application-facing content. It does not absorb ecosystem-specific script roles, rule syntax or pagination contracts. Each compatibility engine owns those semantics.

## S0 platform contract

`source-api` contains cross-ecosystem metadata only:

- `SourceEcosystem`
- `SourceCompatibilityProfile`
- `SourceArtifactIdentity`
- `SourceArtifactDescriptor`
- `SourceArtifactState`
- `SourceTrustState`
- `SourceCompatibilityState`
- `SourceHealthState`
- `SourceFailureCode`
- `SourceFailure`
- `SourceOperationalAssessment`
- `SourceExecutionOutcome`

These types intentionally do not replace the existing native `SourceManifest` or `StorySource` contracts.

## Artifact identity

A stable source identity is derived from:

```text
ecosystem + canonical repository identity + remote artifact identity
```

Display names and primary website hosts are not identities. Domains can change without creating a new installed source.

The store layer may hash `SourceArtifactIdentity.canonicalKey()` to obtain a compact artifact/source key.

## Artifact lifecycle

```text
DOWNLOAD
   |
CANDIDATE
   |
validate format and archive safety
   |
verify hash/provenance
   |
parse + compile
   |
compatibility smoke tests
   |
ACTIVATE ATOMICALLY
   |               \
ACTIVE              QUARANTINED
   |
PREVIOUS_KNOWN_GOOD
```

Activation must never destroy the current known-good artifact. A bad update is quarantined and can be inspected without becoming active.

## Compatibility versus health

Compatibility answers whether the host implements the artifact contract.

Health answers whether the source can currently reach and parse its upstream service.

Valid combinations include:

```text
SUPPORTED + HEALTHY
SUPPORTED + AUTH_REQUIRED
SUPPORTED + UPSTREAM_UNAVAILABLE
SUPPORTED + SOURCE_RULE_BROKEN
PARTIAL + HEALTHY
UNSUPPORTED + UNKNOWN
```

A dead website must not be counted as an engine incompatibility.

## Failure ownership

Failures carry both a stable code and a default owner:

- `ENGINE`: compatibility implementation gap or bug
- `SOURCE_ARTIFACT`: plugin/rule/package defect
- `UPSTREAM`: website/service failure or structural change
- `USER`: authentication or user action required
- `HOST`: application capability/policy failure

This lets diagnostics answer who must fix the problem instead of returning one generic "source failed" state.

## Host capability boundary

Compatibility engines may request host capabilities, but execution is performed by brokers. The engine must not gain ambient Android/JVM access.

Default network policy for untrusted sources:

- public Internet as allowed by source/network policy
- block localhost and loopback
- block RFC1918/private ranges
- block link-local ranges
- block cloud metadata endpoints
- block `file://`, `content://` and application-private schemes
- cleartext HTTP requires an explicit legacy policy

## JavaScript isolation

S1 introduces `source-js-sandbox` as the reusable Rhino execution kernel.

It owns only:

- Rhino context creation
- safe standard objects
- Java class shutter
- removal of Java interop globals
- instruction budget
- wall-clock budget
- bounded result conversion
- JSON-shaped default bindings
- trusted extension hook for ecosystem bindings

It does not contain vBook or Legado APIs.

```text
source-js-sandbox
      ^       ^
      |       |
vBookBindings LegadoBindings
```

The current vBook module consumes Rhino through `source-js-sandbox`. Moving its existing bootstrap/runtime logic onto `SafeRhinoSandbox` is a later ABI-preserving step after differential fixtures are in place.

## Legado clean-room rule

The planned `source-legado` implementation must be written from behavior/specification and independently authored fixtures. GPL implementations may be studied to understand behavior and test cases, but code must not be copied into this repository without an explicit licensing decision.

## Compatibility profiles

Profiles are ecosystem-scoped and versionable. Initial expected profiles include concepts such as:

```text
vbook/legacy-js
vbook/current-js
legado/lyc486
legado/gedoor-compatible
```

Exact names are assigned only after corpus detection rules are implemented. A profile is not selected from one weak metadata field; detection should be deterministic and explainable.

## Site-specific hack prohibition

Compatibility engine production code must not contain website-specific branches. `source-compat-testkit` includes a guard that can identify explicit URL literals so CI can enforce this policy once existing neutral/test URLs are allowlisted.

Exceptions are limited to:

- test fixtures
- standards/reference endpoints required by a generic protocol
- explicitly reviewed compatibility quirks that belong to an ecosystem contract rather than a website

## S0 exit criteria

S0 is complete when:

- the cross-ecosystem contract exists without modifying native source semantics
- compatibility and health are modeled independently
- artifact state supports candidate/active/previous-known-good/quarantine
- failure taxonomy identifies responsibility
- architecture invariants are documented and test-covered
- no vBook/Legado action surface is added to native `SourceManifest`
