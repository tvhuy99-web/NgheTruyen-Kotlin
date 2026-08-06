# Roadmap Milestone 3: VietPhrase parity and beyond

Status: **SOURCE COMPLETE**

Android Gradle, APK/AAB, emulator, physical-device and live-site verification are deliberately deferred until after roadmap Milestone 9 by owner decision. No source-side VietPhrase item remains open.

## XPK parity completed

- Seven ordered dictionary layers: Luật Nhân, Pronouns, Hán-Việt, Lạc Việt, VietPhrase, Names and AIReplace.
- Priorities and audited behavior anchored to the supplied XPK v34 VietPhrase source contract.
- Deterministic longest-match, layer priority and stable tie breaking.
- Luật Nhân numbered captures with Names/Pronouns capture sources.
- One-meaning and multi-meaning modes.
- Global and story-scoped rules.
- Chinese punctuation normalization and Vietnamese sentence capitalization.
- Non-cascading base replacement and a single final AIReplace pass.
- UTF-8, UTF-16LE and UTF-16BE text dictionaries with delimiter detection.
- Legacy binary DIC decoding for Java modified UTF, .NET 7-bit UTF-8 and U32 BE/LE, paired and grouped layouts.
- Compiled Double-Array-Trie DAT decoding with strict bounds.
- TXT, DIC, DAT and safe ZIP bundle import through Android SAF.
- AIReplace suggestion review with accept, edit, reject and history status.

## Beyond XPK

- Prefix-trie indexing and a 100,000-rule offline performance gate.
- Per-story dictionary scope and independent per-dictionary enable state.
- Preview, diff, conflict audit and explicit commit boundary.
- Transactional snapshot before import, checksum verification and rollback.
- Lossless ZIP round-trip for rules and dictionary enable states, while retaining compatibility TXT copies.
- Backward-compatible import of prior rule-only archives.
- Versioned Room schema 14 with non-destructive migration from schema 13.
- Versioned backup format 11 preserving advanced rules, snapshots, dictionary states and pending suggestions.
- Duplicate, shadow, invalid-placeholder and AIReplace-cycle diagnostics.
- Archive path traversal, zip-bomb, record-count and payload-size defenses.
- XPK golden fixtures and a pinned source SHA-256 contract.

## Regression policy

Existing Reader, search, download, source, comment and extension behavior was not rewritten. Milestones 0–2 were re-run and re-accepted after the intentional M3 integration, then locked by their source evidence manifest.

## Acceptance gates

- Complete executable VietPhrase/XPK parity gate: PASS.
- Schema 14 persistence compile and SQLite migration gate: PASS.
- SAF transfer static compile: PASS.
- Advanced UI/ViewModel static compile: PASS.
- P4 feature/security wiring: PASS.
- Historical project Milestone 3 foundation and UI gates: PASS.
- Integrated project Milestone 4 gate: PASS.
- Offline release validation: PASS.
- Milestones 0–2 immutable source evidence: PASS.
- Final M3 SHA-256 evidence verifier: PASS.

Milestone 4 may now begin.
