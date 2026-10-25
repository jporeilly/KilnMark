# Builds Kilnmark on Windows and restarts it with Podman (or Docker): the developer setup, with the
# root compose.yaml. A release folder (dist/release) has its own compose.yaml for customers.
#
#   .\build.ps1            build the release, both images, then restart the stack
#   .\build.ps1 -NoRestart build only
#   .\build.ps1 -Lab       also start the reference lab (lab/compose.yaml)
#
# podman-compose on Windows cannot build images whose Containerfile is in a sub-folder, so the
# images are built directly here and the stack is started without --build.

param(
    [switch]$NoRestart,
    [switch]$Lab
)

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }

$engine = if (Get-Command podman -ErrorAction SilentlyContinue) { 'podman' }
          elseif (Get-Command docker -ErrorAction SilentlyContinue) { 'docker' }
          else { throw 'Neither podman nor docker is on the PATH.' }

Step 'Checking Node.js (the web UI needs 22.12 or later)'
$node = (& node --version) 2>$null
if (-not $node) { throw 'Node.js is not on the PATH. Install Node.js 22.12 or later.' }
$v = [version]($node.TrimStart('v'))
if ($v -lt [version]'22.12.0' -and -not ($v.Major -eq 20 -and $v -ge [version]'20.19.0')) {
    throw "Node.js $node is too old: install 22.12 or later."
}
Write-Host "Node.js $node"

Step 'Building the release (gradlew release)'
& .\gradlew.bat release
if ($LASTEXITCODE -ne 0) { throw 'The release build failed: see the messages above.' }

Step "Building the images with $engine"
& $engine build -t localhost/kilnmark-postgres:dev -f containers/postgres/Containerfile containers/postgres
if ($LASTEXITCODE -ne 0) { throw 'Building the PostgreSQL image failed.' }
& $engine build -t localhost/kilnmark-server:dev -f containers/server/Containerfile .
if ($LASTEXITCODE -ne 0) { throw 'Building the server image failed.' }

if ($NoRestart) { Write-Host "`nBuilt. Start with: $engine compose up -d"; exit 0 }

Step 'Restarting Kilnmark'
# A shared network still used by another container makes 'down' print an error; it is harmless.
& $engine compose down 2>&1 | Out-Host
& $engine compose up -d
if ($LASTEXITCODE -ne 0) { throw 'Starting the stack failed: see the messages above.' }

if ($Lab) {
    Step 'Starting the reference lab'
    & $engine compose -f lab/compose.yaml up -d
}

Step 'Waiting for Kilnmark to answer'
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $r = Invoke-WebRequest -Uri 'http://localhost:8780/q/health/ready' -UseBasicParsing -TimeoutSec 2
        if ($r.StatusCode -eq 200) { $ready = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if ($ready) {
    Write-Host "`nKilnmark is ready at http://localhost:8780" -ForegroundColor Green
} else {
    Write-Host "`nKilnmark has not answered yet. Check: $engine logs kilnmark_kilnmark_1 --tail 40" -ForegroundColor Yellow
}
