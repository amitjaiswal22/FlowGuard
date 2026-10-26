param(
    [int]$Rows = 100000,
    [int]$Workers = [Math]::Max([Environment]::ProcessorCount, 2)
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$benchmarkDirectory = Join-Path $root "build\benchmark"
$inputFile = Join-Path $benchmarkDirectory "generated-events.csv"
$singleOutputDirectory = Join-Path $benchmarkDirectory "single-worker"
$parallelOutputDirectory = Join-Path $benchmarkDirectory "parallel"

if ($Rows -lt 1 -or $Workers -lt 2) {
    throw "Rows must be positive and Workers must be at least 2."
}
New-Item -ItemType Directory -Force -Path $benchmarkDirectory | Out-Null
& (Join-Path $root "build.ps1")
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$writer = [System.IO.StreamWriter]::new($inputFile, $false, [System.Text.UTF8Encoding]::new($false))
try {
    $writer.WriteLine("event_id,timestamp,source,metric,value,threshold")
    for ($index = 1; $index -le $Rows; $index++) {
        $value = if ($index % 10 -eq 0) { "12.5" } else { "3.2" }
        $writer.WriteLine("bench-$index,2026-10-23T08:00:00Z,service-$($index % 8),error_rate,$value,10")
    }
} finally {
    $writer.Dispose()
}

$classes = Join-Path $root "build\classes"
$singleOutput = & java -cp $classes com.flowguard.FlowGuard $inputFile $singleOutputDirectory 1 256
if ($LASTEXITCODE -ne 0) {
    throw "Single-worker benchmark run failed."
}
$parallelOutput = & java -cp $classes com.flowguard.FlowGuard $inputFile $parallelOutputDirectory $Workers 256
if ($LASTEXITCODE -ne 0) {
    throw "Parallel benchmark run failed."
}

$singleLine = $singleOutput | Where-Object { $_ -match "^Throughput:" } | Select-Object -First 1
$parallelLine = $parallelOutput | Where-Object { $_ -match "^Throughput:" } | Select-Object -First 1
$singleRate = [double]([regex]::Match($singleLine, "Throughput: ([\d,]+)").Groups[1].Value.Replace(",", ""))
$parallelRate = [double]([regex]::Match($parallelLine, "Throughput: ([\d,]+)").Groups[1].Value.Replace(",", ""))
$improvement = if ($singleRate -eq 0) { 0 } else { (($parallelRate - $singleRate) / $singleRate) * 100 }

Write-Host "Rows: $Rows"
Write-Host "Single-worker rate: $singleRate events/sec"
Write-Host "$Workers-worker rate: $parallelRate events/sec"
Write-Host ("Measured throughput change: {0:N1}%" -f $improvement)
