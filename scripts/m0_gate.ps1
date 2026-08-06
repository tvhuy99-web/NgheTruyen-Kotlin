$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root
$ReportDir = Join-Path $Root "build/reports/m0"
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
$GateResult = "FAILED"
$ExitCode = 1

try {
    Write-Host "M0_GATE_STARTED_UTC=$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))"
    python scripts/m0_preflight.py `
        --json (Join-Path $ReportDir "preflight.json") `
        --markdown (Join-Path $ReportDir "preflight.md") `
        --strict
    if ($LASTEXITCODE -ne 0) { throw "M0 preflight failed with exit $LASTEXITCODE" }

    $StaticGates = @(
        "scripts/validate_release.py",
        "scripts/check_source_platform_foundation.py",
        "scripts/check_milestone2_source_platform.py",
        "scripts/check_milestone4_foundation.py",
        "scripts/check_milestone4_complete.py",
        "scripts/check_milestone5_foundation.py",
        "scripts/check_audio_export_static.py",
        "scripts/check_p1_ui_static.py",
        "scripts/check_p2_ui_static.py",
        "scripts/check_p4_transfer_static.py",
        "scripts/check_milestone3_foundation.py",
        "scripts/check_milestone3_ui_static.py",
        "scripts/check_milestone3_download_static.py",
        "scripts/check_milestone3_kindle.py"
    )
    foreach ($Gate in $StaticGates) {
        Write-Host "RUN_STATIC_GATE=$Gate"
        python $Gate
        if ($LASTEXITCODE -ne 0) { throw "$Gate failed with exit $LASTEXITCODE" }
    }

    & .\gradlew.bat --no-daemon --stacktrace --warning-mode all clean
    if ($LASTEXITCODE -ne 0) { throw "Gradle clean failed" }
    & .\gradlew.bat --no-daemon --stacktrace --warning-mode all test testDebugUnitTest
    if ($LASTEXITCODE -ne 0) { throw "Gradle unit tests failed" }
    & .\gradlew.bat --no-daemon --stacktrace --warning-mode all lintDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle lint failed" }
    & .\gradlew.bat --no-daemon --stacktrace --warning-mode all assembleDebug assembleDebugAndroidTest bundleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle artifact build failed" }

    if ($env:M0_RUN_CONNECTED -eq "1") {
        & .\gradlew.bat --no-daemon --stacktrace --warning-mode all connectedDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw "Connected tests failed" }
    }

    $GateResult = "PASS"
    $ExitCode = 0
}
catch {
    Write-Error $_
    $ExitCode = 1
}
finally {
    python scripts/m0_collect_evidence.py
    Write-Host "M0_GATE=$GateResult"
}
exit $ExitCode
