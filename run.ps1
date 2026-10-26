param(
    [string]$InputFile = ".\sample-events.csv",
    [string]$OutputDirectory = ".\output",
    [int]$Workers = [Environment]::ProcessorCount,
    [int]$QueueCapacity = 64
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $root "build.ps1")
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
$inputPath = [System.IO.Path]::GetFullPath((Join-Path $root $InputFile))
$outputPath = [System.IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
java -cp (Join-Path $root "build\classes") com.flowguard.FlowGuard $inputPath $outputPath $Workers $QueueCapacity
exit $LASTEXITCODE
