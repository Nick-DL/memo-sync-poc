# Compile the Java 17 core + test suite with javac and run every POC assertion.
# Covers: HLC, merge engine rules, shared merge cases (7/7), protocol codec,
# chunking/reassembly, duplicate/out-of-order messages, sync state machine.
#
# Usage (from phone/):
#   powershell -NoProfile -ExecutionPolicy Bypass -File run-tests.ps1
#   pwsh -NoProfile -File run-tests.ps1
#
# Compatible with Windows PowerShell 5.1 and PowerShell 7. ASCII-only on purpose.

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Push-Location $root
$exitCode = 0
try {
    $gson = Join-Path $root 'lib\gson-2.11.0.jar'
    if (-not (Test-Path -LiteralPath $gson)) {
        throw "Missing $gson. Download it from Maven Central: https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar"
    }

    $classes = Join-Path $root 'out\classes-tests'
    if (Test-Path -LiteralPath $classes) {
        Remove-Item -LiteralPath $classes -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $classes | Out-Null

    $mainRoot = Join-Path $root 'core\src\main\java'
    $testRoot = Join-Path $root 'core\src\test\java'
    $sources = @()
    $sources += @(Get-ChildItem -LiteralPath $mainRoot -Recurse -Filter '*.java' |
        ForEach-Object { $_.FullName })
    $sources += @(Get-ChildItem -LiteralPath $testRoot -Recurse -Filter '*.java' |
        ForEach-Object { $_.FullName })
    if ($sources.Count -eq 0) {
        throw "No Java sources found under $mainRoot or $testRoot"
    }

    Write-Host ("Compiling {0} Java source file(s) with javac..." -f $sources.Count)
    & javac -encoding UTF-8 -d $classes -cp $gson @sources
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed with exit code $LASTEXITCODE"
    }

    Write-Host ''
    Write-Host '== Java 17 POC test suite =='
    & java -cp "$classes;$gson" com.example.memosync.test.AllTests
    if ($LASTEXITCODE -ne 0) {
        throw "test suite failed with exit code $LASTEXITCODE"
    }

    Write-Host ''
    Write-Host '== Shared merge-case CLI (writes out/results.json) =='
    & java -cp "$classes;$gson" com.example.memosync.cli.MergeCaseRunner
    if ($LASTEXITCODE -ne 0) {
        throw "MergeCaseRunner failed with exit code $LASTEXITCODE"
    }

    Write-Host ''
    Write-Host 'ALL PHONE POC TESTS PASSED'
}
catch {
    Write-Host ("ERROR: " + $_.Exception.Message) -ForegroundColor Red
    $exitCode = 1
}
finally {
    Pop-Location
}
exit $exitCode
