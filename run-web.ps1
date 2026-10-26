$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $root "build.ps1")
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
$env:PORT = "8080"
java -cp (Join-Path $root "build\classes") com.flowguard.WebServer
