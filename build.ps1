# Builds Kilnmark on Windows and restarts it with Podman (or Docker): the developer setup, with the
# root compose.yaml. A release folder (dist/release) has its own compose.yaml for customers.
#
#   .\build.ps1            build the release, both images, then restart the stack
#   .\build.ps1 -NoRestart build only
#   .\build.ps1 -Lab       also start every demo source of the reference lab (lab/lab.ps1 start all)
#   .\build.ps1 -Lab postgres,sqlserver   also start just those demo sources (see lab/README.md)
#
# podman-compose on Windows cannot build images whose Containerfile is in a sub-folder, so the
# images are built directly here and the stack is started without --build.

param(
    [switch]$NoRestart,
    [switch]$Lab,
    # Demo sources to start with -Lab (postgres, s3, sqlserver, ...); all when left out.
    [Parameter(Position = 0)]
    [string[]]$LabSources
)

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot
# Podman's notice that it runs an external compose provider is noise here.
$env:PODMAN_COMPOSE_WARNING_LOGS = 'false'

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
# podman prints "Executing external compose provider" and the harmless shared-network message on
# the error stream; under $ErrorActionPreference = 'Stop' PowerShell would treat those as failures,
# so compose runs with errors allowed and only the exit code decides.
$ErrorActionPreference = 'Continue'
# Lines podman prints that are not problems: the compose-provider notice, and the shared network
# that compose down cannot remove while the lab's containers are attached to it.
$quiet = 'Executing external compose provider|disable this message|has associated containers|^used$'
function Show-Output { process { $line = "$_"; if ($line -notmatch $quiet) { Write-Host $line } } }
& $engine compose down 2>&1 | Show-Output
& $engine compose up -d 2>&1 | Show-Output
$started = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($started -ne 0) { throw 'Starting the stack failed: see the messages above.' }

if ($Lab -or $LabSources) {
    $labNames = @('all')
    if ($LabSources) { $labNames = $LabSources }
    Step "Starting the reference lab: $($labNames -join ', ')"
    & (Join-Path -Path $PSScriptRoot -ChildPath 'lab\lab.ps1') start @labNames
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'The reference lab did not start: see the messages above.' -ForegroundColor Yellow
    }
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
