# Start, stop and reset the reference lab's demo data sources one at a time (Windows).
# The same commands as lab/lab.sh on Linux and macOS. A development script: it lives in lab/ and is
# never shipped with Kilnmark.
#
#   .\lab\lab.ps1 list                     the demo sources, whether each runs, port, source name
#   .\lab\lab.ps1 start <name...>|all      start them (each is a compose profile of the same name)
#   .\lab\lab.ps1 stop <name...>|all       stop them (their data is kept)
#   .\lab\lab.ps1 status                   the lab's containers
#   .\lab\lab.ps1 reset <name...>|all [-Force]   drop the data and load it again (asks first)
#   .\lab\lab.ps1 logs <name>              the last lines of its containers' logs
#   .\lab\lab.ps1 sources                  where lab\sources.csv is, and how to import it
#
# Names may also be given with commas: .\lab\lab.ps1 start postgres,sqlserver
# Uses podman if it is on the PATH, otherwise docker, as build.ps1 does. Needs no administrator
# rights. Works in Windows PowerShell 5.1 and PowerShell 7.

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Command = 'help',

    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Names = @(),

    [Alias('Yes')]
    [switch]$Force
)

# Native commands report failure through $LASTEXITCODE, checked after each one. 'Stop' would turn
# their messages on stderr into terminating errors in Windows PowerShell 5.1.
$ErrorActionPreference = 'Continue'

$LabDir = $PSScriptRoot
$RepoRoot = Split-Path -Path $LabDir -Parent
$ComposeFile = 'lab/compose.yaml'   # relative to the repository root, as build.ps1 uses it
$Project = 'lab'                    # compose project name: containers and volumes are lab_... / lab-...
$Network = 'kilnmark'               # created by Kilnmark's own compose.yaml
$SqlServerLicence = 'https://go.microsoft.com/fwlink/?linkid=857698'

# The demo sources. Name is also the compose profile. A source with no services (files) needs
# nothing running. Keep this table the same as the one in lab.sh.
$Sources = @(
    [pscustomobject]@{ Name = 'postgres'; Services = @('lab-postgres'); OneShot = @(); Volumes = @()
        Port = '5433'; Source = 'lab-db'; What = 'PostgreSQL 18: database lab (crm, billing)' }
    [pscustomobject]@{ Name = 's3'; Services = @('lab-s3'); OneShot = @(); Volumes = @()
        Port = '9000'; Source = 'lab-bucket'; What = 'S3-compatible storage (VersityGW): bucket lab-raw' }
    [pscustomobject]@{ Name = 'sqlserver'; Services = @('lab-sqlserver'); OneShot = @('lab-sqlserver-init')
        Volumes = @('lab-sqlserver-data')
        Port = '14330'; Source = 'lab-sqlserver'; What = 'SQL Server 2022: database labdb (sales, hr, ops)' }
    [pscustomobject]@{ Name = 'files'; Services = @(); OneShot = @(); Volumes = @()
        Port = '-'; Source = 'lab-share'; What = 'File share lab/files/share, read from disk (mounted at /srv/files)' }
)

function Fail([string]$Message, [int]$Code = 1) {
    [Console]::Error.WriteLine("lab: $Message")
    exit $Code
}

function Get-SourceNames {
    return ($Sources | ForEach-Object { $_.Name }) -join ', '
}

function Get-Source([string]$Name) {
    $found = $Sources | Where-Object { $_.Name -eq $Name }
    if ($null -eq $found) {
        Fail "unknown demo source '$Name'. Choose from: $(Get-SourceNames), or all." 2
    }
    return $found
}

# The names given (commas or spaces allowed, or "all") as source objects, each one checked.
function Resolve-Sources([string[]]$Given) {
    $list = @($Given | ForEach-Object { $_ -split '[,\s]+' } | Where-Object { $_ -ne '' })
    if ($list.Count -eq 0) {
        Fail "name at least one demo source: $(Get-SourceNames), or all." 2
    }
    if ($list -contains 'all') {
        return $Sources
    }
    $result = @()
    foreach ($n in $list) {
        $result += Get-Source $n
    }
    return $result
}

function Get-Engine {
    if (Get-Command -Name podman -ErrorAction SilentlyContinue) { return 'podman' }
    if (Get-Command -Name docker -ErrorAction SilentlyContinue) { return 'docker' }
    Fail 'neither podman nor docker is on the PATH.'
}

$Engine = $null
if (@('help', '-h', '--help', '-?') -notcontains $Command) {
    $Engine = Get-Engine
}

# Container ids of one compose service, found by the labels compose tools put on containers;
# failing that, by podman-compose's container name (lab_<service>_1).
function Get-Containers([string]$Service) {
    $ids = @(& $Engine ps -a -q --filter "label=com.docker.compose.project=$Project" `
        --filter "label=com.docker.compose.service=$Service")
    if ($LASTEXITCODE -ne 0) { Fail "could not list containers with $Engine." }
    $ids = @($ids | Where-Object { $_ -and $_.Trim() -ne '' })
    if ($ids.Count -eq 0) {
        $ids = @(& $Engine ps -a -q --filter "name=$($Project)_$($Service)_")
        $ids = @($ids | Where-Object { $_ -and $_.Trim() -ne '' })
    }
    return $ids
}

function Test-Running([string]$Service) {
    foreach ($id in (Get-Containers $Service)) {
        $state = & $Engine inspect --format '{{.State.Running}}' $id
        if ($LASTEXITCODE -eq 0 -and "$state".Trim() -eq 'true') { return $true }
    }
    return $false
}

function Get-State($Src) {
    if ($Src.Services.Count -eq 0) { return 'on disk' }
    $up = 0
    $created = 0
    foreach ($s in $Src.Services) {
        if ((Get-Containers $s).Count -gt 0) { $created++ }
        if (Test-Running $s) { $up++ }
    }
    if ($up -eq $Src.Services.Count) {
        foreach ($s in $Src.OneShot) {
            foreach ($id in (Get-Containers $s)) {
                if (Test-Running $s) { return 'running (loading data)' }
                $code = & $Engine inspect --format '{{.State.ExitCode}}' $id
                if ($LASTEXITCODE -eq 0 -and "$code".Trim() -ne '0') { return 'running (data load failed: see logs)' }
            }
        }
        return 'running'
    }
    if ($up -gt 0) { return 'partly running' }
    if ($created -gt 0) { return 'stopped' }
    return 'not started'
}

function Assert-Network {
    & $Engine network inspect $Network *> $null
    if ($LASTEXITCODE -ne 0) {
        Fail "network '$Network' does not exist. Start Kilnmark first ($Engine compose up -d in the repository root), or create it: $Engine network create $Network"
    }
}

function Invoke-List {
    $format = '{0,-10} {1,-34} {2,-6} {3,-14} {4}'
    Write-Output ($format -f 'NAME', 'STATE', 'PORT', 'SOURCE', 'WHAT')
    foreach ($src in $Sources) {
        Write-Output ($format -f $src.Name, (Get-State $src), $src.Port, $src.Source, $src.What)
    }
}

function Invoke-Start([string[]]$Given) {
    $selected = @(Resolve-Sources $Given)
    $profileArgs = @()
    foreach ($src in $selected) {
        if ($src.Services.Count -eq 0) {
            Write-Host "$($src.Name): nothing to start; Kilnmark reads it from disk."
            continue
        }
        if ($src.Name -eq 'sqlserver') {
            Write-Host "sqlserver: starting it accepts Microsoft's SQL Server Developer licence terms: $SqlServerLicence"
        }
        $profileArgs += @('--profile', $src.Name)
    }
    if ($profileArgs.Count -eq 0) { return }
    Assert-Network
    Push-Location -Path $RepoRoot
    try {
        & $Engine compose -p $Project -f $ComposeFile @profileArgs up -d
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($code -ne 0) { Fail 'starting failed: see the messages above.' }
    if (@($selected | Where-Object { $_.Name -eq 'sqlserver' }).Count -gt 0) {
        Write-Host "sqlserver: the first start takes a minute or two after the download; watch it with: .\lab\lab.ps1 logs sqlserver"
    }
}

function Invoke-Stop([string[]]$Given) {
    foreach ($src in @(Resolve-Sources $Given)) {
        if ($src.Services.Count -eq 0) {
            Write-Host "$($src.Name): nothing to stop."
            continue
        }
        foreach ($s in @($src.Services + $src.OneShot)) {
            $ids = @(Get-Containers $s)
            if ($ids.Count -gt 0) {
                & $Engine stop @ids | Out-Null
                if ($LASTEXITCODE -ne 0) { Fail "could not stop $s." }
            }
        }
        Write-Host "$($src.Name): stopped."
    }
}

function Invoke-Status {
    & $Engine ps -a --filter "label=com.docker.compose.project=$Project" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
    if ($LASTEXITCODE -ne 0) { Fail "could not list the lab's containers." }
}

function Invoke-Reset([string[]]$Given) {
    $rest = @($Given | Where-Object { @('--yes', '-y', '--force') -notcontains $_ })
    $yes = $Force.IsPresent -or ($rest.Count -lt @($Given).Count)
    $selected = @(Resolve-Sources $rest)
    if (-not $yes) {
        Write-Host "This deletes the demo data of: $(($selected | ForEach-Object { $_.Name }) -join ', ') and loads it again."
        try {
            $answer = Read-Host -Prompt 'Type yes to go ahead' -ErrorAction Stop
        } catch {
            Fail 'could not ask: add -Force to go ahead.'
        }
        if ($answer -ne 'yes') {
            Write-Host 'Nothing changed.'
            exit 1
        }
    }
    $restart = @()
    foreach ($src in $selected) {
        if ($src.Services.Count -eq 0) {
            Write-Host "$($src.Name): nothing to reset; its files are in git (git checkout -- lab/files restores them)."
            continue
        }
        foreach ($s in @($src.Services + $src.OneShot)) {
            $ids = @(Get-Containers $s)
            if ($ids.Count -gt 0) {
                # -v also removes the container's anonymous volumes (PostgreSQL's data)
                & $Engine rm -f -v @ids | Out-Null
                if ($LASTEXITCODE -ne 0) { Fail "could not remove $s." }
            }
        }
        foreach ($v in $src.Volumes) {
            $volume = "$($Project)_$v"
            & $Engine volume inspect $volume *> $null
            if ($LASTEXITCODE -eq 0) {
                & $Engine volume rm $volume | Out-Null
                if ($LASTEXITCODE -ne 0) { Fail "could not remove volume $volume." }
            }
        }
        Write-Host "$($src.Name): data removed."
        $restart += $src.Name
    }
    if ($restart.Count -gt 0) { Invoke-Start $restart }
}

function Invoke-Logs([string[]]$Given) {
    $list = @($Given | ForEach-Object { $_ -split '[,\s]+' } | Where-Object { $_ -ne '' })
    if ($list.Count -ne 1) { Fail "name one demo source: $(Get-SourceNames)." 2 }
    $src = Get-Source $list[0]
    if ($src.Services.Count -eq 0) {
        Write-Host "$($src.Name) runs nothing, so it has no logs."
        return
    }
    $found = $false
    foreach ($s in @($src.Services + $src.OneShot)) {
        foreach ($id in (Get-Containers $s)) {
            $found = $true
            Write-Host "== $s"
            & $Engine logs --tail 100 $id
        }
    }
    if (-not $found) { Fail "$($src.Name) has not been started: .\lab\lab.ps1 start $($src.Name)" }
}

function Invoke-Sources {
    Write-Output (Join-Path -Path $LabDir -ChildPath 'sources.csv')
    Write-Output ''
    Write-Output 'In Kilnmark: Sources > Import sources from a file, choose that file, tick "Rewrite host names'
    Write-Output 'for this server" (and, if you like, "Test each connection" and "Queue a scan"), then Import.'
    Write-Output 'Only the demo sources you have started will connect; import again whenever you like.'
}

function Show-Usage {
    Get-Content -Path $PSCommandPath -TotalCount 15 | ForEach-Object { $_ -replace '^# ?', '' }
    Write-Output ''
    Write-Output "Demo sources: $(Get-SourceNames)"
}

switch ($Command) {
    'list' { Invoke-List }
    'start' { Invoke-Start $Names }
    'stop' { Invoke-Stop $Names }
    'status' { Invoke-Status }
    'reset' { Invoke-Reset $Names }
    'logs' { Invoke-Logs $Names }
    'sources' { Invoke-Sources }
    { @('help', '-h', '--help', '-?') -contains $_ } { Show-Usage }
    default {
        Show-Usage
        Fail "unknown command '$Command'." 2
    }
}
exit 0
