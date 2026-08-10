# Source Compatibility Lab

Status: S1 baseline

## Purpose

The compatibility lab proves semantic parity. "The script did not crash" and "most sources work" are not acceptance criteria.

`source-compat-testkit` provides ecosystem-neutral snapshots and comparison primitives. Corpus downloaders, vBook reference runners and Legado reference runners are added in later milestones.

## Differential test shape

```text
same raw artifact
same action and arguments
same fixture/network recording
          |
   +------+------+
   |             |
Reference      NgheTruyen
runtime        runtime
   |             |
   +------+------+
          |
 semantic comparator
```

Compare behavior that matters to source authors and users:

- response/status contract
- result data type and fields
- continuation token
- request URL/method/headers/body
- cookies
- persisted variables
- chapter/content output

Volatile fields can be ignored explicitly by path. Arrays can be configured as unordered only when the contract says ordering is irrelevant.

## Snapshot model

`CompatibilitySnapshot` is deliberately JSON-shaped and runtime-neutral. It can represent both vBook and Legado observations without forcing either ecosystem to adopt the other's model.

A case records:

- case ID
- artifact ID
- ecosystem
- compatibility profile
- action
- string arguments
- expected snapshot
- required feature IDs

Every vBook execute argument is kept as a string at the compatibility boundary. Legado test adapters may serialize their rule inputs into the same case envelope while retaining Legado semantics inside the runner.

## Verdicts

- `PASS`: semantic output matches reference
- `FAIL`: reference divergence
- `UNSUPPORTED`: known engine feature gap
- `UPSTREAM_UNAVAILABLE`: live dependency unavailable; not an engine failure
- `INVALID_FIXTURE`: corpus/test data defect

## Feature matrix

Corpus scanners should emit stable feature IDs instead of only source counts.

Examples for vBook:

```text
vbook.fetch.charset
vbook.fetch.blob
vbook.dynamic-script
vbook.config.constant-injection
vbook.browser
vbook.websocket
vbook.cryptojs
vbook.continuation.opaque-string
```

Examples for Legado:

```text
legado.rule.css
legado.rule.xpath
legado.rule.jsonpath
legado.rule.regex.capture
legado.rule.get-put
legado.rule.nested-js
legado.url.options
legado.login-ui
legado.login-check-js
legado.chapter.variables
legado.explore.state
```

A feature may be `SUPPORTED`, `PARTIAL`, `UNSUPPORTED` or `UNKNOWN` and carries passing/failing evidence case IDs.

## Corpus policy

A corpus entry must retain:

- repository provenance
- raw artifact hash
- artifact version if available
- detection profile and reason
- static feature inventory
- fixture/reference version

Live-site failures are tracked separately from fixture/reference parity so a domain outage cannot flip an engine feature from supported to unsupported.

## JavaScript sandbox tests

`source-js-sandbox` has four initial invariants:

1. JSON-shaped bindings execute normally.
2. Java/Rhino interop globals are absent.
3. infinite loops are terminated by instruction budget.
4. oversized results are rejected.

Future tests must cover timeout, recursion/depth, collection cardinality, host extension boundaries and per-profile JavaScript behavior.

## Source-specific code guard

`SourceSpecificityGuard` finds explicit HTTP(S) hosts in production source text. It is intentionally conservative and is not yet wired as a repository-wide build failure because the existing codebase may contain legitimate reference/test URLs that must first be classified.

When enabled in CI, compatibility engine directories should have a small reviewed allowlist and fixtures should be scanned separately.

## S1 exit criteria

S1 baseline is complete when:

- a shared Rhino module exists
- vBook obtains Rhino from that shared module instead of declaring its own Rhino dependency
- the sandbox denies Java class access by default
- execution has instruction and wall-clock budgets
- default bindings/results cross a bounded JSON-shaped boundary
- an ecosystem-neutral differential snapshot/comparator exists
- semantic comparator tests cover nested differences and ignored volatile paths
- a site-specific URL guard exists for later CI enforcement

S1 does not claim vBook runtime migration is complete. That migration follows after S2 corpus fixtures so behavior can be changed under differential protection rather than by blind refactor.
