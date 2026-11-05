# Compile the Java 17 core and run the shared merge cases.
# Writes out/results.json in the shared contract format; exits 0 only when all cases pass.
#
# Used by memo-sync-poc/tools/run_conformance.py:
#   powershell -NoProfile -ExecutionPolicy Bypass -File run-merge-cases.ps1
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

    $classes = Join-Path $root 'out\classes-merge'
    if (Test-Path -LiteralPath $classes) {
        Remove-Item -LiteralPath $classes -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $classes | Out-Null

    $sourceRoot = Join-Path $root 'core\src\main\java'
    $sources = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter '*.java' |
        ForEach-Object { $_.FullName })
    if ($sources.Count -eq 0) {
        throw "No Java sources found under $sourceRoot"
    }

    Write-Host ("Compiling {0} Java source file(s) with javac..." -f $sources.Count)
    & javac -encoding UTF-8 -d $classes -cp $gson @sources
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed with exit code $LASTEXITCODE"
    }

    & java -cp "$classes;$gson" com.example.memosync.cli.MergeCaseRunner @args
    if ($LASTEXITCODE -ne 0) {
        throw "MergeCaseRunner failed with exit code $LASTEXITCODE"
    }
    Write-Host 'merge cases: PASS'
}
catch {
    Write-Host ("ERROR: " + $_.Exception.Message) -ForegroundColor Red
    $exitCode = 1
}
finally {
    Pop-Location
}
exit $exitCode
