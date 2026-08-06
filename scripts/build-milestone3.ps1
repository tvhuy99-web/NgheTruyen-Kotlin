$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

if (-not (Get-Command java.exe -ErrorAction SilentlyContinue)) {
    throw "JDK 17 is required and java.exe is not on PATH."
}
$Python = Get-Command python.exe -ErrorAction SilentlyContinue
$PythonPrefix = @()
if (-not $Python) {
    $Python = Get-Command py.exe -ErrorAction SilentlyContinue
    $PythonPrefix = @("-3")
}
if (-not $Python) { throw "Python 3 is required for the offline release gates." }

& $Python.Source @PythonPrefix ".\scripts\validate_release.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Python.Source @PythonPrefix ".\scripts\check_milestone3_foundation.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Python.Source @PythonPrefix ".\scripts\check_milestone3_ui_static.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Python.Source @PythonPrefix ".\scripts\check_milestone3_download_static.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Python.Source @PythonPrefix ".\scripts\check_milestone3_kindle.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Python.Source @PythonPrefix ".\scripts\check_milestone2_complete.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$Tasks = @("clean", "test", "testDebugUnitTest", "lintDebug", "assembleDebug", "assembleDebugAndroidTest")
& ".\gradlew.bat" "--no-daemon" "--stacktrace" @Tasks
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$ExtraTasks = if ($env:MILESTONE3_EXTRA_TASKS) { $env:MILESTONE3_EXTRA_TASKS.ToLowerInvariant() } else { "" }
if ($ExtraTasks.Contains("connected")) {
    & ".\gradlew.bat" "--no-daemon" "--stacktrace" "connectedDebugAndroidTest"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
if ($ExtraTasks.Contains("release")) {
    & ".\gradlew.bat" "--no-daemon" "--stacktrace" "bundleRelease"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host "Milestone 3 complete build gates completed."
Write-Host "Debug APK: app\build\outputs\apk\debug\app-debug.apk"
