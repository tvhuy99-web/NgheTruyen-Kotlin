#!/usr/bin/env bash
set -euo pipefail

echo '=== SourceManifest construction/parsing ==='
grep -R "SourceManifest(" -n source-* app/src/main/java | head -80 || true

echo '=== SourcePackStorySource ==='
grep -R "class SourcePackStorySource\|SourcePackStorySource(" -n app/src/main/java source-* | head -40 || true

echo '=== source-info / manifest JSON parser hints ==='
grep -R "schemaVersion\|runtime.*entry\|optJSONArray(\"actions\"\|getJSONObject(\"actions\"" -n source-package source-vbook source-lua app/src/main/java | head -120 || true

echo '=== DB migration registration tail ==='
grep -n "MIGRATION_1[4-9]_\|addMigrations\|version = 18" app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt | tail -80 || true

echo '=== backup UI placeholders and voice common UI ==='
grep -n "NHẬT KÝ SAO LƯU\|THÊM GIỌNG\|KHÔI PHỤC 7 HỒ SƠ\|ReferenceVoiceCastSettingsCard" app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt | head -80 || true

echo '=== backup manager summary types ==='
grep -n "data class BackupSummary\|enum class BackupComponent" app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt | head -40 || true
