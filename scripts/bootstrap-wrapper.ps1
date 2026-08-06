$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$JavaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java.exe" }
$Downloader = Join-Path $ProjectRoot "gradle\wrapper\WrapperDownloader.java"
$WrapperJar = Join-Path $ProjectRoot "gradle\wrapper\gradle-wrapper.jar"
& $JavaExe $Downloader $WrapperJar
if ($LASTEXITCODE -ne 0) { throw "Gradle Wrapper bootstrap failed with exit code $LASTEXITCODE" }
Write-Host "Gradle Wrapper is ready. Run .\gradlew.bat test or .\gradlew.bat assembleDebug"
