# Kilnmark's control script for Windows (INSTALL.md, "A. Install a release"; ADR 0044). Windows
# PowerShell 5.1 or later; run it through kilnmark.cmd, which passes -ExecutionPolicy Bypass:
#   kilnmark.cmd install [-Root DIR] [-Offline IMAGES] [-Auth] [-Starter] [-SkipVerify]
#   kilnmark.cmd up | down | restart | status | logs | check | backup | restore | upgrade | ...
#   kilnmark.cmd help
# Run with no command, it starts Kilnmark (up). The install root holds versions\<version>\ (a
# release, never edited), current.txt (which version runs), config\.env (this site's settings and
# secrets), certs\, backups\ and logs\. Data stays in the compose project's volumes. No command
# prints a secret. This file is ASCII only: Windows PowerShell 5.1 misreads anything else.

$ErrorActionPreference = 'Continue'   # native tools are judged by their exit codes, not stderr
# Podman says on every compose command that it runs an external compose tool: not news here.
$env:PODMAN_COMPOSE_WARNING_LOGS = 'false'
$Here = $PSScriptRoot
$Registry = 'ghcr.io/jporeilly'
$DefaultRoot = 'C:\Kilnmark'
# The compose project: kilnmark, unless KILNMARK_PROJECT names another (a second install on one
# machine, or a developer's machine whose source checkout already uses kilnmark).
$Project = 'kilnmark'; if ($env:KILNMARK_PROJECT) { $Project = $env:KILNMARK_PROJECT }
# Compose fills ${NAME} from this process's environment before config\.env. These must mean what
# config\.env says, as this script reads them (ADR 0051), so a value set outside never counts.
foreach ($n in @('KILNMARK_PRODUCTION', 'KILNMARK_STARTER', 'KILNMARK_STARTER_PEOPLE_ACTION')) {
    Remove-Item ('Env:' + $n) -ErrorAction SilentlyContinue
}
$Launchers = @('kilnmark.ps1', 'kilnmark.cmd', 'start-kilnmark.cmd', 'stop-kilnmark.cmd', 'create-shortcut.ps1', 'kilnmark.ico')

$Command = 'up'
$Rest = @()
if ($args.Count -gt 0) { $Command = [string]$args[0]; if ($args.Count -gt 1) { $Rest = @($args[1..($args.Count - 1)]) } }

$script:Root = $null
$script:Engine = $null
$script:SkipVerify = $false
$script:KeyOverride = $null
$script:Offline = $null
$script:Build = $false
$script:WithAuth = $false
$script:WithStarter = $false
$script:Problems = New-Object System.Collections.ArrayList
$script:Warnings = New-Object System.Collections.ArrayList
$script:Logging = $false

function Fail([string]$Message) {
    Write-Host ('Error: ' + $Message) -ForegroundColor Red
    if ($script:Logging) { try { Stop-Transcript | Out-Null } catch { } }
    exit 1
}

function Show-Usage {
    @'
Kilnmark control script.

  install [-Root DIR] [-Offline IMAGES.tar.gz] [-Auth] [-Starter] [-Build] [-SkipVerify] [-NoStart]
                       First setup: checks, folders, settings with new secrets, images, start.
                       -Starter then turns the Starter Pack on (example data; with -Auth,
                       example people too): for trials, demos and training, never production.
  starter people [-NewPassword | -Remove]
                       The Starter Pack's five example people in the bundled Keycloak (with
                       KILNMARK_STARTER=allowed): add them, give them all a new password, or remove
                       them. The password shows once on this console and is kept nowhere.
  up [-NoBrowser]      Start Kilnmark (and Keycloak when .env turns on the auth profile); wait
                       until it answers; open it in the browser. The default command.
  down                 Stop. Every volume, so all data, is kept.
  restart [SERVICE]    Restart everything, or one service.
  status               Version, containers, health, ports and the last backup.
  logs [SERVICE] [-f]  The last 200 lines of the logs, or follow them.
  check                Check the prerequisites and the settings, changing nothing.
  verify FILE [-Key PUB]   Check a downloaded file's signature (cosign) against Kilnmark's key.
  load-images FILE [-SkipVerify]   Load the offline images file.
  pull [-SkipVerify]   Pull this version's images from ghcr.io and check their signatures.
  build                Build the images here from containers\, instead of pulling or loading.
  backup [DIR]         Back up the database and the keys (default: ROOT\backups).
  restore BACKUP [-Yes] [-WithoutKeys]   Put Kilnmark back to a backup.
  upgrade ARCHIVE [-Offline IMAGES] [-NoBackup] [-SkipVerify]
                       Back up, unpack the new version beside this one, and start it.
  uninstall [-Purge]   Stop Kilnmark. -Purge also deletes its volumes (all data) and images.
  version | help
'@ | Write-Host
}

# --- where things are ------------------------------------------------------------------------

function Find-Root {
    if ($env:KILNMARK_ROOT) { return $env:KILNMARK_ROOT }
    if (Test-Path (Join-Path $Here 'versions')) { return $Here }
    $parent = Split-Path $Here -Parent
    if ((Split-Path $parent -Leaf) -eq 'versions') { return (Split-Path $parent -Parent) }
    return $null
}
$script:Root = Find-Root

function Get-VersionOf([string]$Dir) {
    $file = Join-Path $Dir 'compose.yaml'
    if (-not (Test-Path $file)) { return $null }
    foreach ($line in [IO.File]::ReadAllLines($file)) {
        if ($line -match '^\s*image: .*/kilnmark-server:(\S+)\s*$') { return $Matches[1] }
    }
    return $null
}

function Get-CurrentDir {
    if (-not $script:Root) { Fail 'Kilnmark is not installed here. Run: kilnmark.cmd install' }
    $marker = Join-Path $script:Root 'current.txt'
    if (-not (Test-Path $marker)) { Fail ('No version is installed in ' + $script:Root + '.') }
    $v = ([IO.File]::ReadAllText($marker)).Trim()
    return (Join-Path (Join-Path $script:Root 'versions') $v)
}

function Get-CurrentVersion { return (Get-VersionOf (Get-CurrentDir)) }

# --- engine and compose ------------------------------------------------------------------------

function Select-Engine {
    if ($script:Engine) { return }
    if ($env:KILNMARK_ENGINE) { $script:Engine = $env:KILNMARK_ENGINE }
    elseif (Get-Command podman -ErrorAction SilentlyContinue) { $script:Engine = 'podman' }
    elseif (Get-Command docker -ErrorAction SilentlyContinue) { $script:Engine = 'docker' }
    else { Fail 'Neither Podman nor Docker is installed. Install Podman Desktop or Docker Desktop first (INSTALL.md, "What to install first").' }
    if ($script:Engine -eq 'podman') {
        & podman info *> $null
        if ($LASTEXITCODE -ne 0) {
            Write-Host 'Starting the Podman machine ...'
            & podman machine start | Out-Host
            & podman info *> $null
            if ($LASTEXITCODE -ne 0) { Fail 'The Podman machine could not start. If Podman has none yet, make one with: podman machine init' }
        }
    }
    & $script:Engine compose version *> $null
    if ($LASTEXITCODE -ne 0) { Fail ("'" + $script:Engine + " compose' does not work. Install Docker Compose or podman-compose.") }
}

# A setting from config\.env as compose reads it (an export in front too); empty when it is not set.
function Get-Setting([string]$Name) {
    $file = Join-Path $script:Root 'config\.env'
    if (-not (Test-Path $file)) { return '' }
    $value = ''
    foreach ($line in [IO.File]::ReadAllLines($file)) {
        # the name's case exactly, as compose reads it
        if ($line -cmatch ('^\s*(export\s+)?' + $Name + '\s*=(.*)$')) { $value = $Matches[2] }
    }
    $value = $value.Trim()
    if ($value.StartsWith('"')) { $value = $value.Substring(1); $i = $value.IndexOf('"'); if ($i -ge 0) { $value = $value.Substring(0, $i) } }
    elseif ($value.StartsWith("'")) { $value = $value.Substring(1); $i = $value.IndexOf("'"); if ($i -ge 0) { $value = $value.Substring(0, $i) } }
    else { $value = ($value -replace '\s#.*$', '').Trim() }
    return $value
}

# Whether the install is marked production: KILNMARK_PRODUCTION true or false, in any case, as the
# server reads it; unset is false. The server refuses to start on any other value, and so does this.
function Test-Production {
    $v = (Get-Setting 'KILNMARK_PRODUCTION').ToLowerInvariant()
    if ($v -eq '' -or $v -eq 'false') { return $false }
    if ($v -eq 'true') { return $true }
    Fail 'KILNMARK_PRODUCTION in config\.env is true or false: Kilnmark does not start with anything else. Nothing was changed.'
}

function Get-ComposeArgs {
    Select-Engine
    $env = Join-Path $script:Root 'config\.env'
    if (-not (Test-Path $env)) { Fail ('There is no ' + $env + '. Run: kilnmark.cmd install') }
    $list = @('compose', '-p', $Project, '--env-file', $env, '-f', (Join-Path (Get-CurrentDir) 'compose.yaml'))
    # Some compose tools read COMPOSE_PROFILES from the env file and some do not: name each one.
    foreach ($p in ((Get-Setting 'COMPOSE_PROFILES') -split ',')) {
        if ($p.Trim()) { $list += @('--profile', $p.Trim()) }
    }
    return $list
}

# podman-compose 1.0.x prints each podman command it runs, with every setting of the container on
# it (-e NAME=value: passwords, the client secret), joined with spaces or as a Python list. Those
# lines are never shown or kept in logs\.
function Test-ComposeCommand([string]$Line) { return ($Line -match 'podman.*[\s''",\[](-e|--env)([\s=,''"]|$)') }

# Runs compose with the given arguments; its output reaches the screen and the log.
function Invoke-Compose {
    $base = Get-ComposeArgs
    & $script:Engine @base @args 2>&1 | ForEach-Object { '' + $_ } | Where-Object { -not (Test-ComposeCommand $_) } | Out-Host
    return ($LASTEXITCODE -eq 0)
}

# Runs compose without showing what it prints (container ids and names) unless it fails, when
# all of it is shown. For up and down, whose own lines say what happened.
function Invoke-ComposeQuietly {
    $base = Get-ComposeArgs
    $out = & $script:Engine @base @args 2>&1 | ForEach-Object { '' + $_ }
    if ($LASTEXITCODE -eq 0) { return $true }
    $out | Where-Object { -not (Test-ComposeCommand $_) } | Out-Host
    return $false
}

# Runs compose and returns what it printed, as text.
function Read-Compose {
    $base = Get-ComposeArgs
    $out = & $script:Engine @base @args
    return (($out | Out-String).Trim())
}

function Get-ImageNames([string]$V) { return @(($Registry + '/kilnmark-server:' + $V), ($Registry + '/kilnmark-postgres:' + $V)) }

function Test-ImagesPresent([string]$V) {
    Select-Engine
    foreach ($i in (Get-ImageNames $V)) {
        & $script:Engine image inspect $i *> $null
        if ($LASTEXITCODE -ne 0) { return $false }
    }
    return $true
}

# --- logging -----------------------------------------------------------------------------------

function Start-Log([string]$Name) {
    if (-not $script:Root) { return }
    $dir = Join-Path $script:Root 'logs'
    if (-not (Test-Path $dir)) { return }
    $file = Join-Path $dir ($Name + '-' + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '.log')
    try { Start-Transcript -Path $file -Append | Out-Null; $script:Logging = $true } catch { }
}

function Stop-Log { if ($script:Logging) { try { Stop-Transcript | Out-Null } catch { }; $script:Logging = $false } }

# --- signatures --------------------------------------------------------------------------------

# Kilnmark's public key: -Key, else KILNMARK_COSIGN_PUB, else the installed version's cosign.pub.
function Get-PubKey {
    if ($script:KeyOverride) { return $script:KeyOverride }
    if ($env:KILNMARK_COSIGN_PUB) { return $env:KILNMARK_COSIGN_PUB }
    if ($script:Root -and (Test-Path (Join-Path $script:Root 'current.txt'))) {
        $k = Join-Path (Get-CurrentDir) 'cosign.pub'
        if (Test-Path $k) { return $k }
    }
    return (Join-Path $Here 'cosign.pub')
}

# cosign, as installed by winget (cosign-windows-amd64) or by hand (cosign).
function Get-Cosign {
    foreach ($name in @('cosign', 'cosign-windows-amd64')) {
        $c = Get-Command $name -ErrorAction SilentlyContinue
        if ($c) { return $c.Source }
    }
    $link = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Links\cosign-windows-amd64.exe'
    if (Test-Path $link) { return $link }
    return $null
}

function Test-FileSignature([string]$File) {
    $name = Split-Path $File -Leaf
    if ($script:SkipVerify) { Write-Host ('Not checking the signature of ' + $name + ' (-SkipVerify).'); return }
    $cosign = Get-Cosign
    if (-not $cosign) { Fail ('cosign 3 or later is needed to check ' + $name + ' (winget install Sigstore.Cosign). Install it, or pass -SkipVerify to go on unchecked.') }
    $bundle = $File + '.sigstore.json'
    if (-not (Test-Path $bundle)) { Fail ($name + '.sigstore.json, its signature, is not beside it. Download it too, or pass -SkipVerify.') }
    $key = Get-PubKey
    if (-not (Test-Path $key)) { Fail "Kilnmark's public key, cosign.pub, was not found." }
    & $cosign verify-blob --key $key --insecure-ignore-tlog=true --bundle $bundle $File *> $null
    if ($LASTEXITCODE -ne 0) { Fail ($name + ' does not match its signature: it is not the file Kilnmark signed. Nothing was changed.') }
    Write-Host ('Signature checked: ' + $name)
}

# --- images ------------------------------------------------------------------------------------

function Import-Images([string]$File) {
    if (-not (Test-Path $File)) { Fail ('There is no file ' + $File + '.') }
    Test-FileSignature $File
    Select-Engine
    Write-Host ('Loading the images from ' + (Split-Path $File -Leaf) + ' ...')
    & $script:Engine load -i $File | Out-Host
    if ($LASTEXITCODE -ne 0) { Fail 'The images could not be loaded.' }
}

function Get-Images([string]$V) {
    Select-Engine
    foreach ($i in (Get-ImageNames $V)) {
        Write-Host ('Pulling ' + $i + ' ...')
        & $script:Engine pull $i | Out-Host
        if ($LASTEXITCODE -ne 0) { Fail ($i + ' could not be pulled. Without internet access, use the offline images file: load-images.') }
        if ($script:SkipVerify) { continue }
        $cosign = Get-Cosign
        if (-not $cosign) { Fail "cosign 3 or later is needed to check the images' signatures (winget install Sigstore.Cosign). Install it, or pass -SkipVerify." }
        $digest = (& $script:Engine image inspect --format '{{index .RepoDigests 0}}' $i | Out-String).Trim()
        if (-not $digest) { Fail ('Cannot tell the digest of ' + $i + ', so its signature cannot be checked.') }
        & $cosign verify --key (Get-PubKey) --insecure-ignore-tlog=true $digest *> $null
        if ($LASTEXITCODE -ne 0) { Fail ($i + " does not carry Kilnmark's signature. Do not run it; remove it with: " + $script:Engine + ' rmi ' + $i) }
        Write-Host ('Signature checked: ' + $i)
    }
}

function Build-Images([string]$V) {
    Select-Engine
    $dir = Join-Path (Join-Path $script:Root 'versions') $V
    Write-Host 'Building the database image ...'
    & $script:Engine build -t ($Registry + '/kilnmark-postgres:' + $V) -f (Join-Path $dir 'containers\postgres\Containerfile') (Join-Path $dir 'containers\postgres') | Out-Host
    if ($LASTEXITCODE -ne 0) { Fail 'The database image could not be built.' }
    Write-Host 'Building the server image ...'
    & $script:Engine build -t ($Registry + '/kilnmark-server:' + $V) --build-arg APP_DIR=server -f (Join-Path $dir 'containers\server\Containerfile') $dir | Out-Host
    if ($LASTEXITCODE -ne 0) { Fail 'The server image could not be built.' }
}

function Get-ImagesFor([string]$V) {
    if ($script:Offline) { Import-Images $script:Offline }
    elseif (Test-ImagesPresent $V) { Write-Host ('The images of Kilnmark ' + $V + ' are already here.') }
    elseif ($script:Build) { Build-Images $V }
    else { Get-Images $V }
    if (-not (Test-ImagesPresent $V)) { Fail ('The images of Kilnmark ' + $V + ' are still missing after that.') }
}

# --- checks ------------------------------------------------------------------------------------

# A port as config\.env sets it, else as the environment does (for install), else the default.
function Get-Port([string]$Name, [int]$Default) {
    $v = ''
    if ($script:Root -and (Test-Path (Join-Path $script:Root 'config\.env'))) { $v = Get-Setting $Name }
    if (-not $v) { $v = [Environment]::GetEnvironmentVariable($Name) }
    if ($v) { return [int]$v }
    return $Default
}

function Test-PortBusy([int]$Port) {
    $c = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    return [bool]$c
}

function Test-Prerequisites([bool]$Running) {
    $hasEngine = (Get-Command podman -ErrorAction SilentlyContinue) -or (Get-Command docker -ErrorAction SilentlyContinue) -or $env:KILNMARK_ENGINE
    if (-not $hasEngine) {
        [void]$script:Problems.Add('Install Podman Desktop (Podman 5 or later, with a machine) or Docker Desktop.')
    } else {
        Select-Engine
        if ($script:Engine -eq 'podman') {
            $v = (& podman version --format '{{.Client.Version}}' | Out-String).Trim()
            $major = 0; [void][int]::TryParse(($v -split '\.')[0], [ref]$major)
            if ($major -lt 5) { [void]$script:Problems.Add('Podman ' + $v + ' is too old: Kilnmark needs Podman 5 or later.') }
            $mem = (& podman info --format '{{.Host.MemTotal}}' | Out-String).Trim()
        } else {
            $mem = (& docker info --format '{{.MemTotal}}' | Out-String).Trim()
        }
        $bytes = [int64]0
        if ([int64]::TryParse($mem, [ref]$bytes) -and $bytes -gt 0 -and $bytes -lt 4026531840) {
            [void]$script:Warnings.Add('The container engine has ' + [int]($bytes / 1MB) + ' MB of memory; Kilnmark wants 4 GB (5 GB with Keycloak). Give the Podman machine or Docker Desktop more.')
        }
    }
    if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) { [void]$script:Problems.Add('curl.exe is missing (it comes with Windows 10 1803 and later).') }
    if (-not (Get-Command tar.exe -ErrorAction SilentlyContinue)) { [void]$script:Problems.Add('tar.exe is missing (it comes with Windows 10 1803 and later).') }
    if (-not (Get-Cosign)) { [void]$script:Warnings.Add("cosign is not installed: downloads and images cannot be checked against Kilnmark's signature (winget install Sigstore.Cosign; -SkipVerify goes on without).") }
    $target = $DefaultRoot; if ($script:Root) { $target = $script:Root }
    $drive = (Split-Path $target -Qualifier).TrimEnd(':')
    $d = Get-PSDrive $drive -ErrorAction SilentlyContinue
    if ($d -and $d.Free -lt 10GB) { [void]$script:Warnings.Add('Less than 10 GB free on ' + $drive + ':; images, data and backups need more.') }
    if (-not $Running) {
        foreach ($p in @((Get-Port 'KILNMARK_PORT' 8780), (Get-Port 'KILNMARK_HTTPS_PORT' 8443), (Get-Port 'KILNMARK_DB_PORT' 5442))) {
            if (Test-PortBusy $p) { [void]$script:Problems.Add('Port ' + $p + ' is in use: free it, or set another port in config\.env (KILNMARK_PORT, KILNMARK_HTTPS_PORT, KILNMARK_DB_PORT).') }
        }
    }
}

function Write-Checks {
    foreach ($w in $script:Warnings) { Write-Host ('Warning: ' + $w) -ForegroundColor Yellow }
    foreach ($p in $script:Problems) { Write-Host ('Problem: ' + $p) -ForegroundColor Red }
    return ($script:Problems.Count -eq 0)
}

# Settings this version's .env.example names that config\.env does not mention at all.
function Get-NewSettings([string]$Example) {
    $env = Join-Path $script:Root 'config\.env'
    if (-not ((Test-Path $Example) -and (Test-Path $env))) { return @() }
    $have = [IO.File]::ReadAllText($env)
    $out = @()
    foreach ($line in [IO.File]::ReadAllLines($Example)) {
        if ($line -match '^#? *([A-Z][A-Z0-9_]*)=') {
            $k = $Matches[1]
            if (($have -notmatch ('(?m)^#? *' + $k + '=')) -and ($out -notcontains $k)) { $out += $k }
        }
    }
    return $out
}

# --- secrets and settings ----------------------------------------------------------------------

function New-RandomValue {
    $bytes = New-Object byte[] 32
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    $rng.GetBytes($bytes); $rng.Dispose()
    return ([Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('='))
}

# Writes text with LF line ends and no byte-order mark, as compose reads it.
function Write-UnixText([string]$Path, [string[]]$Lines) {
    $enc = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, (($Lines -join "`n") + "`n"), $enc)
}

# Sets KEY=VALUE in the file: replaces a "KEY=" or "# KEY=" line, or adds one at the end.
function Set-Line([string]$File, [string]$Key, [string]$Value) {
    $lines = [IO.File]::ReadAllLines($File)
    $done = $false
    $out = foreach ($l in $lines) {
        if (-not $done -and ($l -match ('^(# *)?' + $Key + '='))) { $done = $true; $Key + '=' + $Value } else { $l }
    }
    $out = @($out)
    if (-not $done) { $out += ($Key + '=' + $Value) }
    Write-UnixText $File $out
}

# Only the current user may read the folder and what is in it.
function Protect-Folder([string]$Dir) {
    $who = $env:USERNAME; if ($env:USERDOMAIN) { $who = $env:USERDOMAIN + '\' + $env:USERNAME }
    & icacls.exe $Dir /inheritance:r /grant:r ($who + ':(OI)(CI)F') *> $null
    if ($LASTEXITCODE -ne 0) { [void]$script:Warnings.Add('Could not limit ' + $Dir + ' to your account; check its permissions.') }
}

function New-Settings([string]$Example) {
    $config = Join-Path $script:Root 'config'
    $env = Join-Path $config '.env'
    Copy-Item $Example (Join-Path $config '.env.example') -Force
    if (Test-Path $env) { Write-Host ('Keeping the settings in ' + $env + '.'); return }
    Copy-Item $Example $env
    Set-Line $env 'KILNMARK_DB_PASSWORD' (New-RandomValue)
    Set-Line $env 'KILNMARK_ADMIN_TOKEN' ('km_' + (New-RandomValue))
    Set-Line $env 'KILNMARK_SAMPLES_DB_PASSWORD' (New-RandomValue)
    # Relative to compose.yaml in versions\<version>\: compose tools read it the same everywhere.
    Set-Line $env 'KILNMARK_CERTS_DIR' '../../certs/https'
    # Other ports, when install was run with them set ($env:KILNMARK_PORT = '9780'; kilnmark.cmd install).
    foreach ($k in @('KILNMARK_PORT', 'KILNMARK_HTTPS_PORT', 'KILNMARK_DB_PORT', 'KILNMARK_KEYCLOAK_PORT')) {
        $val = [Environment]::GetEnvironmentVariable($k)
        if ($val) { Set-Line $env $k $val }
    }
    if ($script:WithAuth) {
        Set-Line $env 'COMPOSE_PROFILES' 'auth'
        Set-Line $env 'KILNMARK_KEYCLOAK_DB_PASSWORD' (New-RandomValue)
        Set-Line $env 'KILNMARK_KEYCLOAK_ADMIN_PASSWORD' (New-RandomValue)
        Set-Line $env 'KILNMARK_OIDC_ENABLED' 'true'
        Set-Line $env 'KILNMARK_OIDC_ISSUER' 'http://keycloak:8080/realms/kilnmark'
        Set-Line $env 'KILNMARK_OIDC_CLIENT_ID' 'kilnmark'
        Set-Line $env 'KILNMARK_OIDC_CLIENT_SECRET' (New-RandomValue)
        Set-Line $env 'KILNMARK_OIDC_PROVIDER_NAME' 'Keycloak'
        Set-Line $env 'KILNMARK_OIDC_VIEWER_GROUPS' 'kilnmark-viewers'
        Set-Line $env 'KILNMARK_OIDC_STEWARD_GROUPS' 'kilnmark-stewards'
        Set-Line $env 'KILNMARK_OIDC_DESIGNER_GROUPS' 'kilnmark-designers'
        Set-Line $env 'KILNMARK_OIDC_ADMIN_GROUPS' 'kilnmark-admins'
        Set-Line $env 'KILNMARK_OIDC_UNMASK_GROUPS' 'kilnmark-sensitive-data'
        # Other ports: where browsers reach Keycloak and Kilnmark follows them, so sign-in never sends
        # people to another install's Keycloak on the default port.
        $kc = [Environment]::GetEnvironmentVariable('KILNMARK_KEYCLOAK_PORT')
        if ($kc) { Set-Line $env 'KILNMARK_KEYCLOAK_URL' ('http://localhost:' + $kc) }
        $kp = [Environment]::GetEnvironmentVariable('KILNMARK_PORT')
        if ($kp) { Set-Line $env 'KILNMARK_URL' ('http://localhost:' + $kp) }
        # A trial install with Keycloak: the Starter Pack may add its example people (ADR 0051).
        if ($script:WithStarter) { Set-Line $env 'KILNMARK_STARTER' 'allowed' }
    }
    Write-Host ('Wrote ' + $env + ' with new secrets (readable by you only).')
}

# Secrets later versions added, as config\.env now asks: run before every start, so upgrade and up
# keep them right. Each new secret is random.
function Update-Settings {
    $env = Join-Path $script:Root 'config\.env'
    if (-not (Test-Path $env)) { return }
    [void](Test-Production)   # stops here on a value the server would refuse
    if (-not (Get-Setting 'KILNMARK_SAMPLES_DB_PASSWORD')) {
        Set-Line $env 'KILNMARK_SAMPLES_DB_PASSWORD' (New-RandomValue)
        Write-Host "Added KILNMARK_SAMPLES_DB_PASSWORD to config\.env: the Starter Pack's database login (ADR 0051)."
    }
    # The example people are made one way now (ADR 0051): this setting is no longer read.
    if (Get-Setting 'KILNMARK_KEYCLOAK_DEMO_PASSWORD') {
        Set-Line $env 'KILNMARK_KEYCLOAK_DEMO_PASSWORD' ''
        Write-Host 'Emptied KILNMARK_KEYCLOAK_DEMO_PASSWORD, no longer used: the example people come with KILNMARK_STARTER=allowed'
        Write-Host 'and kilnmark.cmd starter people. People it made before are left in Keycloak: remove them in its console.'
    }
}

# --- start, stop, health -----------------------------------------------------------------------

function Get-LocalUrl {
    if (Get-Setting 'KILNMARK_TLS_CERT_FILE') {
        $p = Get-Setting 'KILNMARK_HTTPS_PORT'; if (-not $p) { $p = '8443' }
        return ('https://localhost:' + $p)
    }
    $p = Get-Setting 'KILNMARK_PORT'; if (-not $p) { $p = '8780' }
    return ('http://localhost:' + $p)
}

function Test-Answers([string]$Url) {
    & curl.exe -k -L -s -f -o NUL $Url *> $null
    return ($LASTEXITCODE -eq 0)
}

function Wait-For([string]$What, [string]$Url, [int]$Tries) {
    Write-Host ('Waiting for ' + $What + ' ...')
    for ($i = 0; $i -lt $Tries; $i++) {
        if (Test-Answers $Url) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Invoke-Up([string[]]$Options) {
    $browser = $true
    foreach ($o in $Options) { if ($o -eq '-NoBrowser') { $browser = $false } else { Fail ('Unknown option ' + $o + '.') } }
    $v = Get-CurrentVersion
    Update-Settings
    if (-not (Test-ImagesPresent $v)) { Fail ('The images of Kilnmark ' + $v + ' are not here. Get them with one of: kilnmark.cmd pull, kilnmark.cmd load-images FILE, kilnmark.cmd build') }
    Write-Host ('Starting Kilnmark ' + $v + ' ...')
    if (-not (Invoke-ComposeQuietly up -d)) { Fail 'Kilnmark could not start. See why with: kilnmark.cmd logs' }
    if (-not (Wait-For 'Kilnmark' ((Get-LocalUrl) + '/q/health/ready') 90)) { Fail 'Kilnmark did not answer within 3 minutes. See why with: kilnmark.cmd logs kilnmark' }
    $profiles = ',' + ((Get-Setting 'COMPOSE_PROFILES') -replace '\s', '') + ','
    if ($profiles.Contains(',auth,')) {
        $kc = Get-Setting 'KILNMARK_KEYCLOAK_PORT'; if (-not $kc) { $kc = '8180' }
        if (-not (Wait-For 'Keycloak' ('http://localhost:' + $kc + '/realms/kilnmark') 150)) { Fail 'Keycloak did not answer within 5 minutes. See why with: kilnmark.cmd logs keycloak' }
    }
    $url = Get-Setting 'KILNMARK_URL'; if (-not $url) { $url = Get-LocalUrl }
    $url = $url.TrimEnd('/')
    Write-Host ('Kilnmark ' + $v + ' is ready at ' + $url)
    if ($browser) { Start-Process $url }
}

function Invoke-Down {
    Write-Host 'Stopping Kilnmark ...'
    if (-not (Invoke-ComposeQuietly down)) { Fail 'Kilnmark could not be stopped cleanly. See: kilnmark.cmd status' }
    Write-Host 'Kilnmark is stopped. Its data is kept.'
}

function Show-Status {
    $v = Get-CurrentVersion
    Select-Engine
    Write-Host ('Kilnmark ' + $v + ' in ' + $script:Root + ', with ' + $script:Engine)
    [void](Invoke-Compose ps)
    $u = Get-LocalUrl
    if (Test-Answers ($u + '/q/health/ready')) { Write-Host ('Health: ready at ' + $u) } else { Write-Host ('Health: not answering at ' + $u) }
    $last = Get-ChildItem (Join-Path $script:Root 'backups') -Directory -Filter 'kilnmark-*' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
    if ($last) { Write-Host ('Last backup: ' + $last.Name) } else { Write-Host ('Last backup: none in ' + (Join-Path $script:Root 'backups')) }
}

# --- install, upgrade, uninstall ---------------------------------------------------------------

function New-Folders {
    foreach ($d in @('versions', 'config', 'certs\https', 'backups', 'logs')) {
        $p = Join-Path $script:Root $d
        if (-not (Test-Path $p)) {
            try { New-Item -ItemType Directory -Path $p -Force -ErrorAction Stop | Out-Null }
            catch { Fail ('Cannot create ' + $p + '. Choose another folder with -Root.') }
        }
    }
    foreach ($d in @('config', 'certs', 'backups')) { Protect-Folder (Join-Path $script:Root $d) }
}

function Copy-Launchers([string]$V) {
    $dir = Join-Path (Join-Path $script:Root 'versions') $V
    foreach ($f in $Launchers) {
        $src = Join-Path $dir $f
        if (Test-Path $src) { Copy-Item $src (Join-Path $script:Root $f) -Force }
    }
    Write-UnixText (Join-Path $script:Root 'current.txt') @($V)
}

function Install-Release([string]$Source, [string]$V) {
    $dest = Join-Path (Join-Path $script:Root 'versions') $V
    if (Test-Path $dest) { Write-Host ('Kilnmark ' + $V + ' is already in ' + $dest + '.') }
    else {
        Copy-Item $Source ($dest + '.part') -Recurse
        Rename-Item ($dest + '.part') $V
        Write-Host ('Placed Kilnmark ' + $V + ' in ' + $dest + '.')
    }
    Copy-Launchers $V
}

function Invoke-Install([string[]]$Options) {
    $start = $true
    for ($i = 0; $i -lt $Options.Count; $i++) {
        switch ($Options[$i]) {
            '-Root' { $i++; $script:Root = $Options[$i] }
            '-Offline' { $i++; $script:Offline = (Resolve-Path $Options[$i]).Path }
            '-Auth' { $script:WithAuth = $true }
            '-Starter' { $script:WithStarter = $true }
            '-Build' { $script:Build = $true }
            '-SkipVerify' { $script:SkipVerify = $true }
            '-NoStart' { $start = $false }
            default { Fail ('Unknown option ' + $Options[$i] + '. See: kilnmark.cmd help') }
        }
    }
    if (-not (Test-Path (Join-Path $Here 'compose.yaml'))) { Fail 'Run install from the unpacked release folder, kilnmark-<version>.' }
    $v = Get-VersionOf $Here
    if (-not $v) { Fail "Cannot tell this release's version from its compose.yaml." }
    if (-not $script:Root) { $script:Root = $DefaultRoot }
    $script:Root = [IO.Path]::GetFullPath($script:Root)
    if ($script:Root -match '\s') { Fail 'Choose a folder without spaces for -Root.' }
    Write-Host ('Installing Kilnmark ' + $v + ' in ' + $script:Root)
    Test-Prerequisites $false
    if (-not (Write-Checks)) { Fail 'Fix the problems above, then run install again.' }
    New-Folders
    Start-Log 'install'
    Install-Release $Here $v
    New-Settings (Join-Path (Join-Path (Join-Path $script:Root 'versions') $v) '.env.example')
    Get-ImagesFor $v
    if ($start) {
        Invoke-Up @('-NoBrowser')
        if ($script:WithStarter) {
            Invoke-StarterOn
            if ($script:WithAuth) {
                # Not under this install's transcript: while one runs, Windows PowerShell takes a child's
                # output into it, and the people's password is printed there. The child logs on its own.
                Stop-Log
                & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $script:Root 'kilnmark.ps1') starter people
                Start-Log 'install'
            }
        }
    }
    $envFile = Join-Path $script:Root 'config\.env'
    Write-Host ''
    Write-Host ('Kilnmark ' + $v + ' is installed in ' + $script:Root + '.')
    Write-Host ('  Sign in with the admin token: it is KILNMARK_ADMIN_TOKEN in ' + $envFile + '.')
    Write-Host '  Then make a token of your own under API tokens, and keep it safe.'
    Write-Host ('  Control it with: ' + (Join-Path $script:Root 'kilnmark.cmd') + ' up | down | status | backup | help')
    Write-Host ('  Shortcuts on the desktop and in the Start menu: powershell -ExecutionPolicy Bypass -File ' + (Join-Path $script:Root 'create-shortcut.ps1'))
    Write-Host '  Back it up every day, for example from Task Scheduler: kilnmark.cmd backup'
    Stop-Log
}

function Invoke-Upgrade([string[]]$Options) {
    $archive = $null; $backup = $true
    for ($i = 0; $i -lt $Options.Count; $i++) {
        switch ($Options[$i]) {
            '-Offline' { $i++; $script:Offline = (Resolve-Path $Options[$i]).Path }
            '-NoBackup' { $backup = $false }
            '-SkipVerify' { $script:SkipVerify = $true }
            default { if ($Options[$i].StartsWith('-')) { Fail ('Unknown option ' + $Options[$i] + '.') } else { $archive = $Options[$i] } }
        }
    }
    if (-not ($archive -and (Test-Path $archive))) { Fail 'Say which release to upgrade to: kilnmark.cmd upgrade kilnmark-<version>.zip' }
    $archive = (Resolve-Path $archive).Path
    $old = Get-CurrentVersion
    Start-Log 'upgrade'
    Test-FileSignature $archive
    $versions = Join-Path $script:Root 'versions'
    $tmp = Join-Path $versions ('.unpack-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory $tmp | Out-Null
    & tar.exe -xf $archive -C $tmp
    if ($LASTEXITCODE -ne 0) { Remove-Item $tmp -Recurse -Force; Fail ((Split-Path $archive -Leaf) + ' could not be unpacked.') }
    $src = (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName
    $new = Get-VersionOf $src
    if (-not $new) { Remove-Item $tmp -Recurse -Force; Fail "Cannot tell the new release's version." }
    if ($new -eq $old) { Remove-Item $tmp -Recurse -Force; Fail ('Kilnmark ' + $old + ' is already the running version.') }
    $vn = $null; $vo = $null
    if ([version]::TryParse($new, [ref]$vn) -and [version]::TryParse($old, [ref]$vo) -and $vn -lt $vo) {
        Remove-Item $tmp -Recurse -Force; Fail ($new + ' is older than the running ' + $old + ': upgrade only goes forward (go back with restore).')
    }
    if ($backup) { Invoke-Backup @() }
    $dest = Join-Path $versions $new
    if (-not (Test-Path $dest)) { Move-Item $src $dest }
    Remove-Item $tmp -Recurse -Force
    Get-ImagesFor $new
    $added = Get-NewSettings (Join-Path $dest '.env.example')
    Copy-Item (Join-Path $dest '.env.example') (Join-Path $script:Root 'config\.env.example') -Force
    Copy-Launchers $new
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $script:Root 'kilnmark.ps1') up -NoBrowser
    if ($LASTEXITCODE -ne 0) {
        Write-Host ('Kilnmark ' + $new + ' did not start: stopping it and starting ' + $old + ' again.') -ForegroundColor Red
        [void](Invoke-Compose down)
        Copy-Launchers $old
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $script:Root 'kilnmark.ps1') up -NoBrowser
        if ($LASTEXITCODE -eq 0) { Write-Host ('Kilnmark ' + $old + ' runs again. See why ' + $new + ' did not start in logs\.') -ForegroundColor Red }
        else {
            Write-Host ('Kilnmark ' + $old + ' does not start either: ' + $new + ' may have changed its database already.') -ForegroundColor Red
            Write-Host 'Put it back to the backup taken above: kilnmark.cmd restore <that backup> -Yes' -ForegroundColor Red
        }
        Stop-Log; exit 1
    }
    if ($added.Count -gt 0) { Write-Host ('Settings new in ' + $new + ' (see config\.env.example): ' + ($added -join ' ')) }
    # Keep the two newest versions.
    $all = Get-ChildItem $versions -Directory | Where-Object { -not $_.Name.StartsWith('.') } | Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } }
    $drop = @($all | Select-Object -SkipLast 2)
    foreach ($d in $drop) { if ($d.Name -ne $new -and $d.Name -ne $old) { Remove-Item $d.FullName -Recurse -Force } }
    Write-Host ('Upgraded Kilnmark from ' + $old + ' to ' + $new + '.')
    Stop-Log
}

function Invoke-Uninstall([string[]]$Options) {
    $purge = $false
    foreach ($o in $Options) { if ($o -eq '-Purge') { $purge = $true } else { Fail ('Unknown option ' + $o + '.') } }
    $v = Get-CurrentVersion
    if (-not $purge) {
        [void](Invoke-Compose down)
        Write-Host ('Kilnmark is stopped and will not start again by itself. Its data and ' + $script:Root + ' are kept.')
        Write-Host 'To delete the data too: kilnmark.cmd uninstall -Purge'
        return
    }
    Write-Host "This deletes all of Kilnmark's data: its database, the key to stored passwords, and Keycloak's."
    Write-Host 'Back up first (kilnmark.cmd backup) if you might want any of it. Type DELETE to go on:'
    $answer = Read-Host
    if ($answer -cne 'DELETE') { Fail 'Nothing was deleted.' }
    [void](Invoke-Compose --profile auth --profile ai down -v)
    foreach ($i in (Get-ImageNames $v)) { & $script:Engine rmi $i *> $null }
    Write-Host ("Kilnmark's containers, volumes and images are deleted. " + $script:Root + ' is kept: delete it yourself if you wish.')
}

# --- the Starter Pack (ADR 0051) ----------------------------------------------------------------

# Waits for a one-off service (samples-db) of this start to finish; false when it failed or did not
# finish in 5 minutes. By compose's labels, which Docker and Podman both set.
function Wait-OneOff([string]$Service) {
    Select-Engine
    for ($i = 0; $i -lt 150; $i++) {
        $state = (& $script:Engine ps -a --filter ('label=com.docker.compose.project=' + $Project) --filter ('label=com.docker.compose.service=' + $Service) --format '{{.Status}}' 2> $null | Select-Object -First 1)
        $state = ('' + $state).Trim()
        if ($state.StartsWith('Exited (0)')) { return $true }
        if ($state.StartsWith('Exited')) { return $false }
        Start-Sleep -Seconds 2
    }
    return $false
}

# Turns the Starter Pack's example data on through Kilnmark's own API, as Settings > Starter Pack
# does: Kilnmark does the work. The admin token reaches curl in a settings file in a new folder only
# this account can open, deleted at once, never on a command line (Windows PowerShell can add a
# byte-order mark to what it pipes, which curl would not read).
function Invoke-StarterOn {
    if (-not (Wait-OneOff 'samples-db')) { Write-Host 'Warning: samples-db did not finish well, so the Starter Pack may not find its database. See: kilnmark.cmd logs samples-db' -ForegroundColor Yellow }
    Write-Host 'Turning the Starter Pack on ...'
    $work = Join-Path ([IO.Path]::GetTempPath()) ('kilnmark-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory $work | Out-Null
    Protect-Folder $work
    $configFile = Join-Path $work 'curl.conf'
    $answerFile = Join-Path $work 'answer.json'
    $text = ''
    try {
        $config = 'header = "Authorization: Bearer ' + (Get-Setting 'KILNMARK_ADMIN_TOKEN') + '"'
        [IO.File]::WriteAllText($configFile, $config + "`n", (New-Object System.Text.ASCIIEncoding))
        $config = $null
        $code = (& curl.exe -k -s -K $configFile -X POST --max-time 600 -o $answerFile -w '%{http_code}' ((Get-LocalUrl) + '/api/v1/starter-pack') | Out-String).Trim()
        if (Test-Path $answerFile) { $text = [IO.File]::ReadAllText($answerFile) }
    } finally {
        Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
    }
    $answer = $null
    try { $answer = ConvertFrom-Json $text } catch { }
    if ($code -ne '200' -or -not $answer) {
        $why = ''; if ($answer -and $answer.error) { $why = ': ' + $answer.error }
        Write-Host ('The Starter Pack was not turned on (HTTP ' + $code + ')' + $why) -ForegroundColor Red
        Write-Host 'Turn it on later under Settings > Starter Pack.'
        return
    }
    foreach ($st in $answer.steps) { Write-Host ('  ' + $st.step + ': ' + $st.outcome) }
    Write-Host 'The Starter Pack is on: example data, not for production. Turn it off under Settings > Starter Pack.'
}

# The Starter Pack's five example people, made and removed by keycloak-setup with Keycloak's own
# admin: Kilnmark holds no Keycloak credential. Their password is new, made here, handed to that one
# run of keycloak-setup on its standard input (never its environment, a command line or config\.env), and
# shown once on the console, past the transcript, never in logs\. People already there keep theirs unless
# -NewPassword.
function Invoke-Starter([string[]]$Options) {
    $what = $null; $action = 'add'
    foreach ($o in $Options) {
        switch ($o) {
            'people' { $what = 'people' }
            '-NewPassword' { $action = 'new-password' }
            '-Remove' { $action = 'remove' }
            default { Fail ('Unknown option ' + $o + ': only -NewPassword and -Remove.') }
        }
    }
    if ($what -ne 'people') { Fail 'Say what: kilnmark.cmd starter people [-NewPassword | -Remove]' }
    [void](Get-CurrentVersion)
    Update-Settings
    $profiles = ',' + ((Get-Setting 'COMPOSE_PROFILES') -replace '\s', '') + ','
    if (-not $profiles.Contains(',auth,')) { Fail 'The example people need the bundled Keycloak: the auth profile (INSTALL.md, "Keycloak, if you have no identity provider").' }
    $password = ''
    if ($action -ne 'remove') {
        if (Test-Production) { Fail 'This install is marked production (KILNMARK_PRODUCTION=true): no example people here. Nothing was changed.' }
        if ((Get-Setting 'KILNMARK_STARTER') -ne 'allowed') { Fail 'Set KILNMARK_STARTER=allowed in config\.env first: the example people are only for trials and training.' }
        $password = New-RandomValue
    }
    if ($action -eq 'remove') { Write-Host 'Asking keycloak-setup to remove the example people ...' } else { Write-Host 'Asking keycloak-setup to add the example people ...' }
    $base = Get-ComposeArgs
    $env:KILNMARK_STARTER_PEOPLE_ACTION = $action
    try {
        # one line on its standard input (Windows PowerShell can put a byte-order mark first: setup drops it)
        $password | & $script:Engine @base run --rm -T keycloak-setup 2>&1 | ForEach-Object { '' + $_ } |
            Where-Object { -not (Test-ComposeCommand $_) } | Out-Host
        $ok = ($LASTEXITCODE -eq 0)
    } finally {
        Remove-Item Env:KILNMARK_STARTER_PEOPLE_ACTION -ErrorAction SilentlyContinue
    }
    if (-not $ok) { Fail 'keycloak-setup did not finish: see what it said above. Nothing more was done.' }
    if ($action -eq 'remove') {
        Write-Host 'The example people the Starter Pack made are removed from Keycloak; anyone else is left as they are.'
        return
    }
    # Straight to the console, past the transcript: what this script says otherwise goes to logs\ too.
    # Never when the output goes elsewhere (a file, or a parent's transcript): it would be kept there.
    if ([Console]::IsOutputRedirected) {
        $password = $null
        Write-Host 'There is no console to show the password on (it is never written anywhere else): run'
        Write-Host 'kilnmark.cmd starter people -NewPassword in a console window.'
        return
    }
    if ($action -eq 'new-password') { [Console]::WriteLine('The example people now all sign in with this password,') }
    else { [Console]::WriteLine('The example people added just now sign in with this password,') }
    [Console]::WriteLine('shown only now and kept nowhere, not even in logs\: ' + $password)
    if ($action -ne 'new-password') { [Console]::WriteLine('(Any that were there already keep theirs: -NewPassword gives them all a new one.)') }
    $password = $null
}

# --- backup and restore (as backup.sh and restore.sh do on Linux) ------------------------------

function Get-ContainerId([string]$Service) {
    # By compose's labels, which Docker Compose and podman-compose both set (podman-compose's ps
    # takes no service name).
    Select-Engine
    $id = (& $script:Engine ps -q --filter ('label=com.docker.compose.project=' + $Project) --filter ('label=com.docker.compose.service=' + $Service) | Out-String).Trim()
    if (-not $id) { Fail ('The ' + $Service + ' container is not running. Start Kilnmark first: kilnmark.cmd up') }
    return (($id -split "`r?`n")[0].Trim())
}

function Write-Sums([string]$Dir, [string[]]$Files) {
    $lines = foreach ($f in $Files) { (Get-FileHash (Join-Path $Dir $f) -Algorithm SHA256).Hash.ToLower() + '  ' + $f }
    Write-UnixText (Join-Path $Dir 'SHA256SUMS') @($lines)
}

function Invoke-Backup([string[]]$Options) {
    $dest = Join-Path $script:Root 'backups'
    if ($Options.Count -gt 0) { $dest = $Options[0] }
    $v = Get-CurrentVersion
    $now = (Get-Date).ToUniversalTime()
    $dir = Join-Path $dest ('kilnmark-' + $now.ToString('yyyyMMddTHHmmssZ'))
    if (Test-Path $dir) { Fail ($dir + ' already exists: wait a second and run again.') }
    New-Item -ItemType Directory $dir -Force | Out-Null
    $bad = { param($what) Fail ('Backup failed: ' + $what + '. Nothing usable is in ' + $dir + '; delete it and run again.') }
    $pg = Get-ContainerId 'postgres'
    # One consistent snapshot while Kilnmark keeps working, read back in full as a restore would.
    if (-not (Invoke-Compose exec -T postgres sh -c 'pg_dump -U kilnmark -d kilnmark --format=custom -f /tmp/kilnmark-db.dump && pg_restore -f /dev/null /tmp/kilnmark-db.dump')) { & $bad 'the database could not be dumped, or the dump cannot be read back' }
    & $script:Engine cp ($pg + ':/tmp/kilnmark-db.dump') (Join-Path $dir 'kilnmark-db.dump') | Out-Host
    if ($LASTEXITCODE -ne 0) { & $bad 'the dump could not be copied out' }
    [void](Invoke-Compose exec -T postgres rm -f /tmp/kilnmark-db.dump)
    $migration = Read-Compose exec -T postgres psql -U kilnmark -d kilnmark -Atc 'SELECT version FROM kilnmark.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1'
    if (-not $migration) { & $bad "the database's migration could not be read" }
    # Not the keys an earlier restore set aside, nor its note for the audit log.
    $km = Get-ContainerId 'kilnmark'
    if (-not (Invoke-Compose exec -T kilnmark tar -C /var/lib/kilnmark --exclude=./before-restore-* --exclude=./restored.properties -cf /tmp/kilnmark-keys.tar .)) { & $bad 'the keys volume could not be copied' }
    & $script:Engine cp ($km + ':/tmp/kilnmark-keys.tar') (Join-Path $dir 'kilnmark-keys.tar') | Out-Host
    if ($LASTEXITCODE -ne 0) { & $bad 'the copy of the keys volume could not be copied out' }
    [void](Invoke-Compose exec -T kilnmark rm -f /tmp/kilnmark-keys.tar)
    $list = (& tar.exe -tf (Join-Path $dir 'kilnmark-keys.tar') | Out-String)
    if ($LASTEXITCODE -ne 0) { & $bad 'the copy of the keys volume cannot be read back' }
    if ($list -notmatch 'secret\.key') { Write-Host 'Note: no source password is stored yet, so the keys volume holds no key.' }
    Write-UnixText (Join-Path $dir 'backup.txt') @(('taken_at=' + $now.ToString('yyyy-MM-ddTHH:mm:ssZ')), ('kilnmark_version=' + $v), ('migration=' + $migration.Trim()))
    Write-Sums $dir @('kilnmark-db.dump', 'kilnmark-keys.tar', 'backup.txt')
    Write-Host ('Backed up Kilnmark ' + $v + ' to ' + $dir)
    Write-Host ('  kilnmark-db.dump   the database (' + (Get-Item (Join-Path $dir 'kilnmark-db.dump')).Length + ' bytes)')
    Write-Host '  kilnmark-keys.tar  the key for stored source passwords, and drivers: keep it apart from the dumps'
}

function Invoke-Restore([string[]]$Options) {
    $src = $null; $yes = $false; $keys = $true
    foreach ($o in $Options) {
        switch ($o) { '-Yes' { $yes = $true } '-WithoutKeys' { $keys = $false } default { if ($o.StartsWith('-')) { Fail ('Unknown option ' + $o + ': only -Yes and -WithoutKeys.') } else { $src = $o } } }
    }
    if (-not ($src -and (Test-Path (Join-Path $src 'backup.txt')) -and (Test-Path (Join-Path $src 'SHA256SUMS')))) {
        Fail 'Say which backup to restore, a folder made by backup: kilnmark.cmd restore C:\Kilnmark\backups\kilnmark-<time> -Yes'
    }
    $src = (Resolve-Path $src).Path
    # The backup must be whole, and no newer than this release.
    $sums = [IO.File]::ReadAllLines((Join-Path $src 'SHA256SUMS'))
    $files = @('kilnmark-db.dump', 'backup.txt'); if ($keys) { $files += 'kilnmark-keys.tar' }
    foreach ($f in $files) {
        $line = $sums | Where-Object { $_ -match ('^([0-9a-f]{64}) [ *]' + [regex]::Escape($f) + '$') } | Select-Object -First 1
        $ok = $false
        if ($line -and (Test-Path (Join-Path $src $f))) {
            $want = ($line -split ' ')[0]
            $ok = ((Get-FileHash (Join-Path $src $f) -Algorithm SHA256).Hash.ToLower() -eq $want)
        }
        if (-not $ok) {
            if ($f -eq 'kilnmark-keys.tar') { Fail 'kilnmark-keys.tar is missing from the backup or does not match its checksum: put it back from where you keep keys. If it is lost for good, -WithoutKeys restores the database alone, and every stored password must be entered again.' }
            Fail ($f + ' is missing from the backup or does not match its checksum: the backup is damaged. Nothing was changed.')
        }
    }
    $info = @{}
    foreach ($l in [IO.File]::ReadAllLines((Join-Path $src 'backup.txt'))) { $kv = $l -split '=', 2; if ($kv.Count -eq 2) { $info[$kv[0]] = $kv[1] } }
    $taken = $info['taken_at']; $from = $info['kilnmark_version']; $thisVersion = Get-CurrentVersion
    if (-not $from) { Fail "Cannot tell the backup's Kilnmark version. Nothing was changed." }
    $vf = $null; $vh = $null
    if ([version]::TryParse($from, [ref]$vf) -and [version]::TryParse($thisVersion, [ref]$vh) -and $vf -gt $vh) {
        Fail ('The backup is from Kilnmark ' + $from + ', newer than this release (' + $thisVersion + '). Restore it with ' + $from + ' or later. Nothing was changed.')
    }
    if (-not $yes) {
        Write-Host ('This puts Kilnmark back to the backup taken at ' + $taken + ' (Kilnmark ' + $from + ').')
        Write-Host ('The database and keys it replaces are kept, to go back to. To go on: kilnmark.cmd restore ' + $src + ' -Yes')
        exit 2
    }
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')
    $before = 'kilnmark_before_' + $stamp
    [void](Invoke-Compose up -d postgres)
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        $base = Get-ComposeArgs
        & $script:Engine @base exec -T postgres pg_isready -h 127.0.0.1 -U kilnmark -d postgres *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { Fail 'The database did not become ready. See: kilnmark.cmd logs postgres' }

    Write-Host 'Restoring the backup into a database of its own (Kilnmark keeps running)'
    $pg = Get-ContainerId 'postgres'
    & $script:Engine cp (Join-Path $src 'kilnmark-db.dump') ($pg + ':/tmp/restore.dump') | Out-Host
    if ($LASTEXITCODE -ne 0) { Fail 'The dump could not be copied into the database container. Kilnmark and its database are as they were.' }
    $ok = Invoke-Compose exec -T postgres sh -c 'dropdb -U kilnmark --if-exists kilnmark_restoring && createdb -U kilnmark kilnmark_restoring && pg_restore -U kilnmark -d kilnmark_restoring --exit-on-error --single-transaction /tmp/restore.dump; rc=$?; rm -f /tmp/restore.dump; exit $rc'
    if (-not $ok) { Fail 'The backup could not be restored into kilnmark_restoring. Kilnmark and its database are as they were.' }

    Write-Host 'Stopping Kilnmark'
    [void](Invoke-Compose stop kilnmark)
    $base = Get-ComposeArgs
    & $script:Engine @base exec -T kilnmark true *> $null
    if ($LASTEXITCODE -eq 0) { Fail 'Kilnmark is still running after the stop: nothing was replaced. Stop it, then run restore again.' }

    Write-Host ('Putting the restored database in place; the previous one is kept as ' + $before)
    $sql = 'ALTER DATABASE kilnmark RENAME TO ' + $before
    if (-not (Invoke-Compose exec -T postgres psql -U kilnmark -d postgres -q -v ON_ERROR_STOP=1 -c $sql)) { Fail 'The database could not be renamed. Kilnmark is stopped; its database is still kilnmark.' }
    if (-not (Invoke-Compose exec -T postgres psql -U kilnmark -d postgres -q -v ON_ERROR_STOP=1 -c 'ALTER DATABASE kilnmark_restoring RENAME TO kilnmark')) {
        Fail ('The restored database could not be put in place. Kilnmark is stopped; its previous database is ' + $before + ': see the guide, "Going back".')
    }

    # The restore is recorded by Kilnmark at its next start, from restored.properties beside the key.
    $stage = Join-Path $script:Root ('backups\.restore-' + $stamp)
    New-Item -ItemType Directory $stage | Out-Null
    Write-UnixText (Join-Path $stage 'restored.properties') @(('backup=' + (Split-Path $src -Leaf)), ('takenAt=' + $taken), ('version=' + $from), ('previousDatabase=' + $before), ('keys=' + $keys.ToString().ToLower()))
    if ($keys) {
        Write-Host ('Restoring the keys volume; the previous keys are kept in before-restore-' + $stamp)
        Copy-Item (Join-Path $src 'kilnmark-keys.tar') (Join-Path $stage 'kilnmark-keys.tar')
        $script = 'set -e; cd /var/lib/kilnmark; rm -rf .restoring; mkdir .restoring; tar -C .restoring -xf /tmp/restore/kilnmark-keys.tar; mkdir before-restore-' + $stamp + '; for f in * .[!.]*; do case $f in .restoring|before-restore-*|''*''|''.[!.]*'') ;; *) mv $f before-restore-' + $stamp + '/ ;; esac; done; for f in .restoring/* .restoring/.[!.]*; do [ -e $f ] && mv $f . ; done; rmdir .restoring; cp /tmp/restore/restored.properties restored.properties'
    } else {
        $script = 'cp /tmp/restore/restored.properties /var/lib/kilnmark/restored.properties'
    }
    # A short-lived container on the keys volume, the files copied in with cp: no bind mount of a
    # Windows path, which some compose tools split at the drive letter's colon.
    $image = $Registry + '/kilnmark-server:' + (Get-CurrentVersion)
    $cid = (& $script:Engine run -d --rm -v ($Project + '_keys:/var/lib/kilnmark') --entrypoint sleep $image 600 | Out-String).Trim()
    $ok = $false
    if ($cid) {
        & $script:Engine cp $stage ($cid + ':/tmp/restore') | Out-Host
        if ($LASTEXITCODE -eq 0) {
            & $script:Engine exec $cid sh -c $script | Out-Host
            $ok = ($LASTEXITCODE -eq 0)
        }
        & $script:Engine rm -f $cid *> $null
    }
    Remove-Item $stage -Recurse -Force
    if (-not $ok) { Fail ('The keys volume could not be restored. Kilnmark is stopped; its previous database is ' + $before + ' and its previous keys are in before-restore-' + $stamp + ': see the guide, "Going back".') }

    Write-Host 'Starting Kilnmark'
    Invoke-Up @('-NoBrowser')
    Write-Host ('Restored Kilnmark to the backup taken at ' + $taken + '. Now:')
    Write-Host '  - revoke again any token revoked after that time, and check Settings > Access review;'
    if (-not $keys) { Write-Host "  - enter every stored password again (Sources; AI models' keys; the mail password);" }
    Write-Host '  - run again the scans and pipelines that ran after it, if their results are needed;'
    Write-Host ('  - once all is well, drop the previous database: ' + $script:Engine + ' compose -p ' + $Project + ' exec postgres dropdb -U kilnmark ' + $before)
}

# --- main --------------------------------------------------------------------------------------

switch ($Command) {
    'install' { Invoke-Install $Rest }
    { $_ -in @('up', 'start') } { Start-Log 'up'; Invoke-Up $Rest; Stop-Log }
    { $_ -in @('down', 'stop') } { Start-Log 'down'; Invoke-Down; Stop-Log }
    'restart' { if (-not (Invoke-Compose restart @Rest)) { Fail 'Restart failed. See: kilnmark.cmd logs' } }
    'status' { Show-Status }
    'logs' {
        $base = Get-ComposeArgs
        $follow = @(); $svc = @()
        foreach ($a in $Rest) { if ($a -eq '-f') { $follow = @('-f') } else { $svc += $a } }
        & $script:Engine @base logs --tail 200 @follow @svc
    }
    'check' {
        $running = $false
        if ($script:Root -and (Test-Path (Join-Path $script:Root 'config\.env'))) { $running = Test-Answers ((Get-LocalUrl) + '/q/health/ready') }
        Test-Prerequisites $running
        if ($script:Root -and (Test-Path (Join-Path $script:Root 'config\.env'))) {
            $added = Get-NewSettings (Join-Path (Get-CurrentDir) '.env.example')
            if ($added.Count -gt 0) { Write-Host ("Settings in this version's .env.example that config\.env does not mention: " + ($added -join ' ')) }
        }
        if (Write-Checks) { Write-Host 'All checks passed.' } else { exit 1 }
    }
    'verify' {
        $file = $null
        for ($i = 0; $i -lt $Rest.Count; $i++) { if ($Rest[$i] -eq '-Key') { $i++; $script:KeyOverride = $Rest[$i] } else { $file = $Rest[$i] } }
        if (-not $file) { Fail 'Say which file: kilnmark.cmd verify FILE' }
        Test-FileSignature (Resolve-Path $file).Path
    }
    'load-images' {
        $file = $null
        foreach ($a in $Rest) { if ($a -eq '-SkipVerify') { $script:SkipVerify = $true } else { $file = $a } }
        if (-not $file) { Fail 'Say which file: kilnmark.cmd load-images kilnmark-<version>-images.tar.gz' }
        Import-Images (Resolve-Path $file).Path
    }
    'pull' {
        foreach ($a in $Rest) { if ($a -eq '-SkipVerify') { $script:SkipVerify = $true } else { Fail ('Unknown option ' + $a + '.') } }
        Get-Images (Get-CurrentVersion)
    }
    'build' { Build-Images (Get-CurrentVersion) }
    'backup' { Start-Log 'backup'; Invoke-Backup $Rest; Stop-Log }
    'starter' { Start-Log 'starter'; Invoke-Starter $Rest; Stop-Log }
    'restore' { Start-Log 'restore'; Invoke-Restore $Rest; Stop-Log }
    'upgrade' { Invoke-Upgrade $Rest }
    'uninstall' { Invoke-Uninstall $Rest }
    'version' { if ($script:Root) { Write-Host ('Kilnmark ' + (Get-CurrentVersion)) } else { Write-Host ('Kilnmark ' + (Get-VersionOf $Here) + ' (not installed)') } }
    { $_ -in @('help', '-h', '--help', '/?') } { Show-Usage }
    default { Show-Usage; exit 2 }
}
exit 0
