$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceFiles = Get-ChildItem -Path (Join-Path $root "src\main\java") -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }
$classes = Join-Path $root "build\classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
javac --release 17 -encoding UTF-8 -d $classes $sourceFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
Write-Host "Build complete: $classes"
