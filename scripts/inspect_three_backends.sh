#!/usr/bin/env bash
set -euo pipefail

echo '=== SourceManifest construction/parsing ==='
grep -R "SourceManifest(" -n source-* app/src/main/java | head -80 || true

echo '=== SourcePackStorySource ==='
grep -R "class SourcePackStorySource\|SourcePackStorySource(" -n app/src/main/java source-* | head -40 || true

echo '=== SourceActionRequest ==='
grep -R "data class SourceActionRequest\|class SourceActionRequest" -n source-api source-runtime source-vbook source-lua app/src/main/java | head -40 || true

echo '=== VoiceRoleDraft ==='
grep -R "data class VoiceRoleDraft\|class VoiceRoleDraft" -n app/src/main/java | head -40 || true

echo '=== Voice role backup codec ==='
grep -n "writeVoiceRoles\|readVoiceRole\|voiceRoles" app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt | head -100 || true

echo '=== Voice role repository methods ==='
grep -n "saveVoiceRole\|listVoiceRoles\|deleteVoiceRole\|setVoiceRole" app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt | head -100 || true

echo '=== Narration role lookup ==='
grep -R "listVoiceRoles\|voiceRoles" -n app/src/main/java/vn/nghetruyen/app/ai app/src/main/java/vn/nghetruyen/app/playback app/src/main/java/vn/nghetruyen/app/audio | head -120 || true

echo '=== DB migration registration tail ==='
grep -n "MIGRATION_1[4-9]_\|addMigrations\|version = 18" app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt | tail -80 || true
