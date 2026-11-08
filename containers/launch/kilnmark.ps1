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
$OneRun = @('KILNMARK_STARTER_PEOPLE_ACTION', 'KILNMARK_PEOPLE_ACTION', 'KILNMARK_PERSON_EMAIL', 'KILNMARK_PERSON_FIRST_NAME', 'KILNMARK_PERSON_LAST_NAME', 'KILNMARK_PERSON_GROUPS', 'KILNMARK_INSTALL_ID', 'KILNMARK_RUN_DIR')
foreach ($n in (@('KILNMARK_MODE', 'KILNMARK_STARTER', 'KILNMARK_ENV_FILE') + $OneRun)) {
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
                       A production install, unless -Starter: a Starter Pack install, for
                       training and development only, which never becomes a production one,
                       always with the bundled Keycloak; the Starter Pack's example data is
                       turned on, and starter people adds the example people. A production
                       install asks how people sign in (-Auth: the bundled Keycloak). With
                       the bundled Keycloak, install offers to add Kilnmark's first admin.
  people add | people import FILE | people activate EMAIL | people list | people expire
                       With the bundled Keycloak, in either mode: add one person of your own (any
                       of Kilnmark's groups, admins included; install asks for the first admin
                       this way), add people from a CSV file (no admins; on production no one who
                       sees sensitive values, and each waits, disabled, for people activate; the
                       example is people.example.csv), activate one, or list who is there. A
                       temporary password is typed (or piped in), never shown, and goes unused for
                       KILNMARK_PEOPLE_ACTIVATION_HOURS (72) at most: people expire, which a
                       scheduled task runs every hour, disables anyone past it. Someone already
                       there is left as they are.
  starter people [-NewPassword | -Remove]
                       On a Starter Pack install, the five example people in the bundled Keycloak
                       (with KILNMARK_STARTER=allowed): add them, give them all a new password, or
                       remove them. You type the password (or pipe it in): it is never shown.
  up [-NoBrowser]      Start Kilnmark (and Keycloak when .env turns on the auth profile); wait
                       until it answers; open it in the browser. The default command.
  down                 Stop. Every volume, so all data, is kept.
  restart [SERVICE]    Restart everything (a stop, then up), or one service.
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

# The deployment mode (ADR 0051): production, or starter-pack for training and development, in
# ASCII lower case, as the server reads it. The server refuses anything else, and so does this.
function Assert-Mode {
    $v = Get-Setting 'KILNMARK_MODE'
    if (-not ($v -ceq 'production' -or $v -ceq 'starter-pack')) {
        Fail 'KILNMARK_MODE in config\.env is production or starter-pack: Kilnmark does not start with anything else. Nothing was changed.'
    }
}
function Test-StarterMode { return ((Get-Setting 'KILNMARK_MODE') -ceq 'starter-pack') }
# Whether the Starter Pack's example data (its database) may be here: on a Starter Pack install.
# Kept apart from the example people (Test-StarterMode), which only a Starter Pack install has.
function Test-SamplesAllowed { return (Test-StarterMode) }

# The deployment mode Kilnmark recorded in its own database at its first start (ADR 0051), read with
# the database's own tools: what decides, not config\.env alone. Empty when nothing is recorded, or
# the database does not answer.
function Get-RecordedMode { return (Get-RecordedSetting 'deployment_mode') }
# A setting Kilnmark recorded in its own database (deployment_mode, or install_id, the install's own
# id, made at its first start); empty when there is none, or the database does not answer.
function Get-RecordedSetting([string]$Key) {
    $base = Get-ComposeArgs
    $out = (& $script:Engine @base exec -T postgres psql -U kilnmark -d kilnmark -X -q -At -c ("SELECT value FROM kilnmark.server_setting WHERE key = '" + $Key + "'") 2> $null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { return '' }
    return $out
}

# The people commands act only where Kilnmark's own record says the same mode as config\.env:
# refused when they differ, or when the database does not answer. keycloak-setup is then handed the
# recorded mode ($script:PeopleMode), never config\.env's alone. Why not, or nothing.
$script:PeopleMode = ''
function Get-PeopleModeProblem {
    $script:PeopleMode = ''
    $recorded = Get-RecordedMode
    if (-not $recorded) { return ("Kilnmark's database does not answer, or has no deployment mode recorded, so nobody was made or changed. Start Kilnmark with " + (Join-Path $script:Root 'kilnmark.cmd') + ' up, then try again.') }
    if ($recorded -cne (Get-Setting 'KILNMARK_MODE')) { return 'KILNMARK_MODE in config\.env differs from the mode Kilnmark recorded at its first start, so nobody was made or changed: set it back.' }
    $script:PeopleMode = $recorded
    return ''
}
function Assert-PeopleMode { $why = Get-PeopleModeProblem; if ($why) { Fail $why } }

# The few secrets each one-off needs (keycloak-setup: Keycloak's admin password and the client
# secret; samples-db: the database's password and the samples login's), each set in a file of its
# own, in a new folder only you can open under config\run, for one run: compose mounts them
# read-only (KILNMARK_RUN_DIR, relative to compose.yaml), never config\.env whole. Gone once the
# run is over (Remove-RunSecrets, in a finally); one a killed run left goes a day on.
$script:RunSecrets = $null
function Get-RunSecretLine([string]$Name) {
    $v = Get-Setting $Name
    if (-not $v.Contains("'")) { return ($Name + "='" + $v + "'") }
    if (-not $v.Contains('"')) { return ($Name + '="' + $v + '"') }
    Fail ($Name + ' in config\.env holds both kinds of quote mark, which compose tools do not all read alike: choose another. Nothing was changed.')
}
function Write-RunSecrets {
    if ($script:RunSecrets) { return }
    $base = Join-Path $script:Root 'config\run'
    if (-not (Test-Path $base)) { New-Item -ItemType Directory $base | Out-Null }
    Get-ChildItem $base -Directory -Filter 'r.*' -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-1) } |
        ForEach-Object { Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
    $name = 'r.' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $dir = Join-Path $base $name
    New-Item -ItemType Directory $dir | Out-Null
    Protect-Folder $dir
    $script:RunSecrets = $dir
    Write-UnixText (Join-Path $dir 'keycloak-setup.env') @((Get-RunSecretLine 'KILNMARK_KEYCLOAK_ADMIN_PASSWORD'), (Get-RunSecretLine 'KILNMARK_OIDC_CLIENT_SECRET'))
    Write-UnixText (Join-Path $dir 'samples-db.env') @((Get-RunSecretLine 'KILNMARK_DB_PASSWORD'), (Get-RunSecretLine 'KILNMARK_SAMPLES_DB_PASSWORD'))
    $env:KILNMARK_RUN_DIR = '../../config/run/' + $name
}
function Remove-RunSecrets {
    if ($script:RunSecrets) { Remove-Item $script:RunSecrets -Recurse -Force -ErrorAction SilentlyContinue }
    $script:RunSecrets = $null
    Remove-Item Env:KILNMARK_RUN_DIR -ErrorAction SilentlyContinue
}

# --- the hourly expiry of unused temporary passwords (ADR 0051) ---------------------------------

# With the bundled Keycloak, people expire runs every hour from a scheduled task of yours, while you
# are signed in to Windows (the Podman machine or Docker Desktop runs only then too), so a temporary
# password left unused is disabled at most an hour after its window, whether or not Kilnmark starts
# again. up sets it up, and removes it without the bundled Keycloak; uninstall removes it.
# KILNMARK_SCHTASKS is for the tests only, and counts only with their marker, which no release sets.
function Test-TestHooks { return ($env:KILNMARK_TEST_HOOKS -ceq 'only-in-kilnmark-tests') }
function Get-ExpiryTaskName { return ('Kilnmark ' + $Project + ' people expire') }
function Invoke-Schtasks {
    $exe = 'schtasks.exe'; if ((Test-TestHooks) -and $env:KILNMARK_SCHTASKS) { $exe = $env:KILNMARK_SCHTASKS }
    & $exe @args *> $null
    return ($LASTEXITCODE -eq 0)
}
function Test-ExpiryTask { return (Invoke-Schtasks /Query /TN (Get-ExpiryTaskName)) }
# Who other than you, SYSTEM and the Administrators may change $Path (or, for a folder, what is in
# it): their names, or nothing.
function Get-OthersWriting([string]$Path) {
    $me = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $ok = @($me, 'S-1-5-18', 'S-1-5-32-544', 'S-1-3-0')
    # writing, appending, attributes, adding or deleting what is in a folder, deleting, permissions,
    # ownership, and the generic write and all
    $mask = [long](0x2 -bor 0x4 -bor 0x10 -bor 0x40 -bor 0x100 -bor 0x10000 -bor 0x40000 -bor 0x80000 -bor 0x40000000 -bor 0x10000000)
    $out = @()
    foreach ($ace in (Get-Acl $Path).Access) {
        if ($ace.AccessControlType -ne 'Allow') { continue }
        if (($ace.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly) -ne 0) { continue }
        if (([long]$ace.FileSystemRights -band $mask) -eq 0) { continue }
        $sid = $null
        try { $sid = $ace.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value } catch { $sid = '' + $ace.IdentityReference }
        if ($ok -notcontains $sid) { $out += ('' + $ace.IdentityReference) }
    }
    # unrolled here, gathered again with @() by the caller
    return $out
}

# Whether $Path's owner is you, SYSTEM or the Administrators: an owner may rewrite its permissions
# whenever they like.
function Test-OwnerOk([string]$Path) {
    $me = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $sid = ''
    try { $sid = (New-Object Security.Principal.NTAccount((Get-Acl $Path).Owner)).Translate([Security.Principal.SecurityIdentifier]).Value } catch { $sid = '' }
    return (@($me, 'S-1-5-18', 'S-1-5-32-544') -contains $sid)
}

# Why someone other than you may change what an hourly run uses, or nothing (ADR 0051): the task
# runs kilnmark.ps1 from the install root as you, and that run reads the current version's
# compose.yaml and keycloak-setup's script, config\.env and current.txt. Each, and the folders that
# hold them, must be owned by you (or SYSTEM, or the Administrators) and writable by nobody else.
function Get-TreeProblem {
    $dir = Get-CurrentDir
    foreach ($p in @($script:Root, (Join-Path $script:Root 'kilnmark.ps1'), (Join-Path $script:Root 'current.txt'),
            (Join-Path $script:Root 'versions'), $dir, (Join-Path $dir 'compose.yaml'),
            (Join-Path $dir 'containers\keycloak\setup.sh'), (Join-Path $script:Root 'config'),
            (Join-Path $script:Root 'config\.env'))) {
        if (-not (Test-Path -LiteralPath $p)) { return ($p + ' is not there') }
        if (-not (Test-OwnerOk $p)) { return ($p + ' is owned by someone other than you, who may change it whenever they like') }
        $others = @(Get-OthersWriting $p)
        if ($others.Count -gt 0) { return ($p + ' may be changed by ' + (($others | Select-Object -Unique) -join ', ')) }
    }
    return ''
}

# The task runs kilnmark.ps1 from the install root as you, every hour: the root (with versions\ and
# the launchers in it) is made yours alone first, and the task is not set up while anyone else owns
# or may still change what a run uses, since they would run what they wrote as you.
function Set-ExpiryTask {
    Protect-Folder $script:Root
    $why = Get-TreeProblem
    if ($why) {
        Remove-ExpiryTask
        Write-Host ('Warning: the hourly expiry of unused temporary passwords was not set up: ' + $why + ', and the task would run it as you. Make it yours alone (or install where only you and the administrators can write), then run up again. Each start expires them too.') -ForegroundColor Yellow
        return
    }
    $had = Test-ExpiryTask
    $run = 'powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File ' + (Join-Path $script:Root 'kilnmark.ps1') + ' people expire -Project ' + $Project
    if (Invoke-Schtasks /Create /SC HOURLY /TN (Get-ExpiryTaskName) /TR $run /F) {
        if (-not $had) { Write-Host ('Set up the hourly expiry of unused temporary passwords: the scheduled task "' + (Get-ExpiryTaskName) + '" (it runs while you are signed in to Windows).') }
    } else {
        Write-Host ('Warning: the hourly expiry of unused temporary passwords could not be set up (Task Scheduler). Run ' + (Join-Path $script:Root 'kilnmark.cmd') + ' people expire every hour yourself; each start expires them too.') -ForegroundColor Yellow
    }
}
function Remove-ExpiryTask {
    if (-not (Test-ExpiryTask)) { return }
    if (Invoke-Schtasks /Delete /TN (Get-ExpiryTaskName) /F) { Write-Host ('Removed the hourly expiry of unused temporary passwords (the scheduled task "' + (Get-ExpiryTaskName) + '").') }
}
function Get-ExpiryState {
    if (Test-ExpiryTask) { return ('hourly (the scheduled task "' + (Get-ExpiryTaskName) + '", while you are signed in to Windows)') }
    return 'not set up'
}

function Assert-Project {
    if ($Project -cnotmatch '^[a-z0-9][a-z0-9_-]*$') { Fail 'KILNMARK_PROJECT is lower-case letters, digits, - and _, starting with a letter or a digit. Nothing was changed.' }
}

function Get-ComposeArgs {
    # a compose project's name, as compose and the hourly expiry's scheduled task take it
    Assert-Project
    Select-Engine
    $env = Join-Path $script:Root 'config\.env'
    if (-not (Test-Path $env)) { Fail ('There is no ' + $env + '. Run: kilnmark.cmd install') }
    $list = @('compose', '-p', $Project, '--env-file', $env, '-f', (Join-Path (Get-CurrentDir) 'compose.yaml'))
    # Some compose tools read COMPOSE_PROFILES from the env file and some do not: name each one.
    foreach ($p in ((Get-Setting 'COMPOSE_PROFILES') -split ',')) {
        if ($p.Trim()) { $list += @('--profile', $p.Trim()) }
    }
    # The Starter Pack's database is made only on a Starter Pack install (ADR 0051).
    if (Test-SamplesAllowed) { $list += @('--profile', 'starter-pack') }
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

function Start-Log([string]$Name, [switch]$Daily) {
    if (-not $script:Root) { return }
    $dir = Join-Path $script:Root 'logs'
    if (-not (Test-Path $dir)) { return }
    # the hourly expiry keeps one log a day, not one a run
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'); if ($Daily) { $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd') }
    $file = Join-Path $dir ($Name + '-' + $stamp + '.log')
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
    # The deployment mode (ADR 0051), fixed at the first start: -Starter makes a Starter Pack install,
    # for training and development, which never becomes a production one.
    if ($script:WithStarter) {
        Set-Line $env 'KILNMARK_MODE' 'starter-pack'
        Set-Line $env 'KILNMARK_SAMPLES_DB_PASSWORD' (New-RandomValue)
    } else {
        Set-Line $env 'KILNMARK_MODE' 'production'
    }
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
    # Every install since deployment modes (ADR 0051) has one; an install from before them (0.1.1 or
    # earlier, a preview) is never upgraded.
    if (-not (Get-Setting 'KILNMARK_MODE')) {
        Fail 'This install predates deployment modes (release 0.1.1 or earlier, a preview), so it cannot be upgraded: install Kilnmark afresh. Back up first anything you mean to keep by hand. Nothing was changed.'
    }
    Assert-Mode
    # Each is for one run of keycloak-setup, set by this script only: in config\.env every start would take it.
    foreach ($n in $OneRun) {
        if (Get-Setting $n) { Fail ($n + ' is for one run of the control script only: delete it from config\.env. Nothing was changed.') }
    }
    # A Starter Pack install signs in with the bundled Keycloak, where its example people are (ADR 0051).
    if ((Test-StarterMode) -and -not (',' + ((Get-Setting 'COMPOSE_PROFILES') -replace '\s', '') + ',').Contains(',auth,')) {
        Fail 'A Starter Pack install (KILNMARK_MODE=starter-pack) needs the bundled Keycloak, where its example people are: put auth in COMPOSE_PROFILES in config\.env, with the Keycloak settings install writes. Nothing was changed.'
    }
    $hours = Get-Setting 'KILNMARK_PEOPLE_ACTIVATION_HOURS'
    if ($hours -and -not ($hours -cmatch '^[0-9]{1,3}$' -and [int]$hours -ge 1 -and [int]$hours -le 720)) {
        Fail 'KILNMARK_PEOPLE_ACTIVATION_HOURS in config\.env is a whole number of hours from 1 to 720. Nothing was changed.'
    }
    if ((Test-SamplesAllowed) -and -not (Get-Setting 'KILNMARK_SAMPLES_DB_PASSWORD')) {
        Set-Line $env 'KILNMARK_SAMPLES_DB_PASSWORD' (New-RandomValue)
        Write-Host "Added KILNMARK_SAMPLES_DB_PASSWORD to config\.env: the Starter Pack's database login (ADR 0051)."
    }
    # A folder of your own, by its full path: a bare name (keys, pgdata) would be a volume of Kilnmark's own.
    $files = Get-Setting 'KILNMARK_FILES_DIR'
    if ($files -and -not ($files -match '^[A-Za-z]:[\\/]' -or $files.StartsWith('\\') -or $files.StartsWith('/'))) {
        Fail 'KILNMARK_FILES_DIR in config\.env is the full path of your own folder, for example D:\Shares\kilnmark-files. Nothing was changed.'
    }
    # Upgrading: an earlier version's compose.yaml edited to mount a folder of your own at /srv/files.
    if (-not $files) {
        $old = @(Get-ChildItem (Join-Path $script:Root 'versions') -Filter 'compose.yaml' -Recurse -ErrorAction SilentlyContinue |
            Where-Object { [IO.File]::ReadAllText($_.FullName) -match '(?m)^\s*-\s*[^#\s][^#\n]*:/srv/files:ro' })
        if ($old.Count -gt 0) {
            Write-Host 'Warning: an earlier version mounted a folder of your own at /srv/files. Now set KILNMARK_FILES_DIR in' -ForegroundColor Yellow
            Write-Host 'config\.env to its full path: Kilnmark sees it read-only at /srv/share, so point its file sources there.' -ForegroundColor Yellow
        }
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
    # what the scripts said, yours only (an install from before kept it open)
    $logs = Join-Path $script:Root 'logs'
    if (Test-Path -LiteralPath $logs) { Protect-Folder $logs }
    try {
        Write-RunSecrets
        Set-InstallIdForSetup
        if (-not (Invoke-ComposeQuietly up -d)) { Fail 'Kilnmark could not start. See why with: kilnmark.cmd logs' }
        if (-not (Wait-For 'Kilnmark' ((Get-LocalUrl) + '/q/health/ready') 90)) { Fail 'Kilnmark did not answer within 3 minutes. See why with: kilnmark.cmd logs kilnmark' }
        # the one-offs this start ran: waited for (keycloak-setup tells Kilnmark what it did), then their secrets go
        if (Test-KeycloakOn) {
            $kc = Get-Setting 'KILNMARK_KEYCLOAK_PORT'; if (-not $kc) { $kc = '8180' }
            if (-not (Wait-For 'Keycloak' ('http://localhost:' + $kc + '/realms/kilnmark') 150)) { Fail 'Keycloak did not answer within 5 minutes. See why with: kilnmark.cmd logs keycloak' }
            Invoke-PeopleAfterUp
            # after the server's first start: the realm is told the id it made
            if (-not $env:KILNMARK_INSTALL_ID) {
                $id = Get-RecordedSetting 'install_id'
                if ($id) {
                    $env:KILNMARK_PEOPLE_ACTION = 'stamp'; $env:KILNMARK_INSTALL_ID = $id
                    try {
                        $base = Get-ComposeArgs
                        $stamped = @(& $script:Engine @base run --rm -T keycloak-setup 2>&1 | ForEach-Object { '' + $_ } | Where-Object { -not (Test-ComposeCommand $_) })
                        if ($LASTEXITCODE -ne 0) { Write-Host "Warning: Keycloak's realm was not told which install it belongs to; the next start tells it. See: kilnmark.cmd logs keycloak-setup" -ForegroundColor Yellow }
                        else { Send-OwnPeopleDone $stamped 'stamp' }
                    } finally { Remove-Item Env:KILNMARK_PEOPLE_ACTION -ErrorAction SilentlyContinue }
                }
            }
        }
        if ((Test-SamplesAllowed) -and -not (Wait-OneOff 'samples-db')) { Write-Host 'Warning: samples-db did not finish well, so the Starter Pack may not find its database. See: kilnmark.cmd logs samples-db' -ForegroundColor Yellow }
    } finally {
        Remove-RunSecrets
        Remove-Item Env:KILNMARK_INSTALL_ID -ErrorAction SilentlyContinue
    }
    if (Test-KeycloakOn) { Set-ExpiryTask } else { Remove-ExpiryTask }
    $url = Get-Setting 'KILNMARK_URL'; if (-not $url) { $url = Get-LocalUrl }
    $url = $url.TrimEnd('/')
    Write-Host ('Kilnmark ' + $v + ' is ready at ' + $url)
    if ($browser) { Start-Process $url }
}

# The install's id and mode, as the server recorded them, handed to keycloak-setup at every start to
# keep on the realm (a restore checks a backup's Keycloak database by them). Read with the database
# started first; before the server's first start there is none yet (Invoke-Up then stamps the realm).
# A mode in config\.env other than the recorded one stops here: the server would refuse it anyway.
function Set-InstallIdForSetup {
    Remove-Item Env:KILNMARK_INSTALL_ID -ErrorAction SilentlyContinue
    if (-not (Test-KeycloakOn)) { return }
    if (-not (Invoke-ComposeQuietly up -d postgres)) { Fail "Kilnmark's database could not start. See why with: kilnmark.cmd logs postgres" }
    $base = Get-ComposeArgs
    for ($i = 0; $i -lt 60; $i++) {
        & $script:Engine @base exec -T postgres pg_isready -h 127.0.0.1 -U kilnmark -d kilnmark *> $null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 2
    }
    $recorded = Get-RecordedMode
    if ($recorded -and $recorded -cne (Get-Setting 'KILNMARK_MODE')) { Fail 'KILNMARK_MODE in config\.env differs from the mode Kilnmark recorded at its first start: set it back. Kilnmark was not started.' }
    $id = Get-RecordedSetting 'install_id'
    if ($id) { $env:KILNMARK_INSTALL_ID = $id }
}

# Everything: a stop, then up, so the one-offs run again with this start's settings. One service:
# compose restarts it (never a one-off, which runs at every up).
function Invoke-Restart([string[]]$Services) {
    if ($Services.Count -eq 0) { Invoke-Down; Invoke-Up @('-NoBrowser'); return }
    foreach ($s in $Services) { if ($s -eq 'keycloak-setup' -or $s -eq 'samples-db') { Fail ($s + ' runs once at every start: restart everything (kilnmark.cmd restart), or run up.') } }
    if (-not (Invoke-Compose restart @Services)) { Fail 'Restart failed. See: kilnmark.cmd logs' }
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
    if (Test-KeycloakOn) { Write-Host ('People expiry: ' + (Get-ExpiryState)) }
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
    foreach ($d in @('config', 'certs', 'backups', 'logs')) { Protect-Folder (Join-Path $script:Root $d) }
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

# How people sign in to a production install, asked at a console (with input redirected, or with
# -Auth, nothing is asked): the organisation's own identity provider, the bundled Keycloak, or API
# tokens only. Only the bundled Keycloak changes what install writes; the others are config\.env's.
$script:OwnProvider = $false
function Read-SignIn {
    if ([Console]::IsInputRedirected) { return }
    Write-Host 'How will people sign in to Kilnmark?'
    Write-Host "  1  Your organisation's own identity provider (recommended for banks and other regulated organisations)"
    Write-Host "  2  The bundled Keycloak, which this install sets up, with Kilnmark's first admin"
    Write-Host '  3  API tokens only, for now'
    $answer = Read-Host '1, 2 or 3 [1]'
    if (-not $answer) { $answer = '1' }
    switch ($answer.Trim()) {
        '1' { $script:OwnProvider = $true }
        '2' { $script:WithAuth = $true }
        '3' { }
        default { Fail 'Answer 1, 2 or 3. Nothing was installed.' }
    }
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
    # A Starter Pack install always has the bundled Keycloak: its example people live there.
    if ($script:WithStarter) { $script:WithAuth = $true }
    if (-not $script:WithAuth) { Read-SignIn }
    # never into a root someone else owns: they could rewrite its permissions whenever they like
    if ((Test-Path -LiteralPath $script:Root) -and -not (Test-OwnerOk $script:Root)) {
        Fail ($script:Root + ' exists and is owned by someone other than you: install into a folder of your own, or have its owner remove it. Nothing was installed.')
    }
    New-Folders
    Start-Log 'install'
    Install-Release $Here $v
    New-Settings (Join-Path (Join-Path (Join-Path $script:Root 'versions') $v) '.env.example')
    Get-ImagesFor $v
    # Asked before the start, which takes a while, so nobody waits to answer.
    $script:PeopleSoft = $true
    $admin = $null
    if ($start -and $script:WithAuth) { $admin = Read-Person 'kilnmark-admins' }
    if ($start) {
        Invoke-Up @('-NoBrowser')
        if ($admin) { [void](New-Person $admin) }
        if ($script:WithStarter) {
            Invoke-StarterOn
            if ($script:WithAuth) {
                Write-Host ('Add the example people, with a password you type: ' + (Join-Path $script:Root 'kilnmark.cmd') + ' starter people')
            }
        }
    }
    $envFile = Join-Path $script:Root 'config\.env'
    if ($script:OwnProvider) {
        Write-Host ''
        Write-Host ("To sign in with your organisation's identity provider, fill in the KILNMARK_OIDC_ settings in " + $envFile + ',')
        Write-Host ('then run ' + (Join-Path $script:Root 'kilnmark.cmd') + ' restart (INSTALL.md, "Sign in with your identity provider").')
    }
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
    # An install from before deployment modes is never upgraded (ADR 0051): refused before anything
    # changes, by config\.env and by what the database recorded.
    Update-Settings
    $recorded = Get-RecordedMode
    if (-not $recorded) { Fail "Kilnmark's database has no deployment mode recorded (or does not answer: start it with kilnmark.cmd up first). An install from before deployment modes (release 0.1.1 or earlier, a preview) cannot be upgraded: install Kilnmark afresh. Nothing was changed." }
    if ($recorded -cne (Get-Setting 'KILNMARK_MODE')) { Fail 'KILNMARK_MODE in config\.env differs from the mode Kilnmark recorded at its first start: set it back. Nothing was changed.' }
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
    Assert-Project
    Remove-ExpiryTask
    if (-not $purge) {
        [void](Invoke-Compose down)
        Write-Host ('Kilnmark is stopped and will not start again by itself. Its data and ' + $script:Root + ' are kept.')
        Write-Host 'To delete the data too: kilnmark.cmd uninstall -Purge'
        return
    }
    Write-Host "This deletes all of Kilnmark's data: its database, the key to stored passwords, and Keycloak's."
    Write-Host 'Back up first (kilnmark.cmd backup) if you might want any of it. Type DELETE to go on:'
    # typed at a console, or one line on standard input (a pipe)
    if ([Console]::IsInputRedirected) { $answer = [Console]::In.ReadLine(); if ($null -eq $answer) { $answer = '' }; $answer = $answer.Trim() } else { $answer = Read-Host }
    if ($answer -cne 'DELETE') { Fail 'Nothing was deleted.' }
    [void](Invoke-Compose --profile auth --profile ai down -v)
    # An image another install on this machine still runs is kept: removing it, or its name, would
    # stop that install starting again.
    $kept = @()
    foreach ($i in (Get-ImageNames $v)) {
        $users = (& $script:Engine ps -a --filter ('ancestor=' + $i) --format '{{.Names}}' 2> $null | Out-String).Trim()
        if ($users) { $kept += $i } else { & $script:Engine rmi $i *> $null }
    }
    if ($kept.Count -gt 0) { Write-Host ('Kept the images ' + ($kept -join ' ') + ': other containers on this machine use them (another install, say).') }
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
    Write-Host 'The Starter Pack is on: example data, for training and development only. Turn it off under Settings > Starter Pack.'
}

# keycloak-setup runs at every start: waits for it, and on a Starter Pack install tells Kilnmark what
# it did to the example people (brought an earlier version's into the pack, or removed them). On a
# production install it refuses a realm that has the example people: that stops here.
function Invoke-PeopleAfterUp {
    if (-not (Wait-OneOff 'keycloak-setup')) {
        if (-not (Test-StarterMode)) { Fail 'keycloak-setup refused or did not finish. See why with: kilnmark.cmd logs keycloak-setup' }
        Write-Host 'Warning: keycloak-setup did not finish well. See why with: kilnmark.cmd logs keycloak-setup' -ForegroundColor Yellow
    }
    $id = (& $script:Engine ps -a -q --filter ('label=com.docker.compose.project=' + $Project) --filter 'label=com.docker.compose.service=keycloak-setup' | Out-String).Trim()
    if (-not $id) { return }
    $id = ($id -split "`r?`n")[0].Trim()
    $lines = @(& $script:Engine logs $id 2>&1 | ForEach-Object { '' + $_ } | Where-Object { -not (Test-ComposeCommand $_) })
    # Only its latest run: it says "started" first.
    $start = 0
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i].StartsWith('keycloak-setup: started')) { $start = $i } }
    $latest = @($lines[$start..($lines.Count - 1)])
    Send-OwnPeopleDone $latest 'expire'
    # anyone the pack's removal only took out of its group, not one of the example people
    foreach ($line in $latest) { if ($line.StartsWith('keycloak-setup: took ')) { Write-Host $line.Substring(16) } }
    if (Test-StarterMode) { Send-PeopleDone $latest }
}

# A request to Kilnmark's own API as the admin: the token reaches curl in a settings file in a new
# folder only this account can open, deleted at once, never on a command line. The HTTP code.
function Invoke-KilnmarkApi([string]$Method, [string]$Path, [string]$Body) {
    $work = Join-Path ([IO.Path]::GetTempPath()) ('kilnmark-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory $work | Out-Null
    Protect-Folder $work
    $configFile = Join-Path $work 'curl.conf'
    $bodyFile = Join-Path $work 'body.json'
    try {
        $config = 'header = "Authorization: Bearer ' + (Get-Setting 'KILNMARK_ADMIN_TOKEN') + '"' + "`n" + 'header = "Content-Type: application/json"'
        [IO.File]::WriteAllText($configFile, $config + "`n", (New-Object System.Text.ASCIIEncoding))
        $config = $null
        [IO.File]::WriteAllText($bodyFile, $Body, (New-Object System.Text.ASCIIEncoding))
        return (& curl.exe -k -s -K $configFile -X $Method --max-time 600 -o NUL --data-binary ('@' + $bodyFile) -w '%{http_code}' ((Get-LocalUrl) + $Path) | Out-String).Trim()
    } finally {
        Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# Tells Kilnmark what keycloak-setup did to the example people (its lines), so Kilnmark's audit log
# holds it (starter.people): Kilnmark holds no Keycloak credential to find out itself.
function Send-PeopleDone([string[]]$Lines) {
    $patterns = [ordered]@{
        'add' = '^keycloak-setup: added ([a-z.]+@kilnmark\.com) \('
        'new-password' = '^keycloak-setup: gave ([a-z.]+@kilnmark\.com) the new password$'
        'remove' = '^keycloak-setup: removed ([a-z.]+@kilnmark\.com) \(a Starter Pack example person\)$'
    }
    foreach ($action in $patterns.Keys) {
        $people = @($Lines | ForEach-Object { if ($_ -cmatch $patterns[$action]) { '"' + $Matches[1] + '"' } })
        if ($people.Count -eq 0) { continue }
        $code = Invoke-KilnmarkApi 'POST' '/api/v1/starter-pack/people' ('{"action":"' + $action + '","people":[' + ($people -join ',') + ']}')
        if ($code -ne '204') { Write-Host ("Warning: Kilnmark did not record this in its audit log (HTTP " + $code + "); Keycloak's admin events have it.") -ForegroundColor Yellow }
    }
}

# The password for the example people: typed twice, not shown, at a console; else one line on
# standard input (a pipe). Never shown, never on a command line, never in a file.
# A secure string's text, its unmanaged copy wiped and freed at once.
function ConvertFrom-Secret([Security.SecureString]$Secret) {
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secret)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

function Read-PeoplePassword {
    if ([Console]::IsInputRedirected) {
        $first = [Console]::In.ReadLine()
        if ($null -eq $first) { $first = '' }
        $first = $first.TrimEnd("`r")
    } else {
        $a = Read-Host -AsSecureString 'A password for the example people (12 characters or more, not shown)'
        $b = Read-Host -AsSecureString 'The same again'
        $first = ConvertFrom-Secret $a
        $second = ConvertFrom-Secret $b
        if ($first -cne $second) { Fail 'The two did not match. Nothing was changed.' }
        $second = $null
    }
    if ($first.Length -lt 12) { Fail "The example people's password is 12 characters or more. Nothing was changed." }
    if ($first -match '\p{Cc}') { Fail 'The password holds a control character (a tab, say), which it may not. Nothing was changed.' }
    return $first
}

# The Starter Pack's five example people, made and removed by keycloak-setup with Keycloak's own
# admin: Kilnmark holds no Keycloak credential. Their password is the one you type (or pipe in),
# handed to that one run of keycloak-setup on its standard input: never shown, never in its
# environment, a command line, config\.env or logs\. People already there keep theirs unless
# -NewPassword. What setup did goes to Kilnmark's audit log.
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
    if (-not (Test-StarterMode)) { Fail "This is a production install: it never has the Starter Pack's example people. Nothing was changed." }
    $password = ''
    if ($action -ne 'remove') {
        if ((Get-Setting 'KILNMARK_STARTER') -ne 'allowed') { Fail 'Set KILNMARK_STARTER=allowed in config\.env first: the example people are only for trials and training.' }
        $password = Read-PeoplePassword
    }
    if ((Get-RecordedMode) -cne 'starter-pack') { $password = $null; Fail "Kilnmark's own record does not say this is a Starter Pack install (or its database does not answer: start it with kilnmark.cmd up). Nothing was changed." }
    if ($action -eq 'remove') { Write-Host 'Asking keycloak-setup to remove the example people ...' } else { Write-Host 'Asking keycloak-setup to add the example people ...' }
    $base = Get-ComposeArgs
    $id = Get-RecordedSetting 'install_id'
    $env:KILNMARK_STARTER_PEOPLE_ACTION = $action
    $env:KILNMARK_MODE = 'starter-pack'
    $env:KILNMARK_INSTALL_ID = $id
    try {
        Write-RunSecrets
        # one line on its standard input (Windows PowerShell can put a byte-order mark first: setup drops it)
        $out = @($password | & $script:Engine @base run --rm -T keycloak-setup 2>&1 | ForEach-Object { '' + $_ } |
            Where-Object { -not (Test-ComposeCommand $_) })
        $ok = ($LASTEXITCODE -eq 0)
    } finally {
        $password = $null
        Remove-RunSecrets
        foreach ($n in @('KILNMARK_STARTER_PEOPLE_ACTION', 'KILNMARK_MODE', 'KILNMARK_INSTALL_ID')) { Remove-Item ('Env:' + $n) -ErrorAction SilentlyContinue }
    }
    $out | Out-Host
    if (-not $ok) { Fail 'keycloak-setup did not finish: see what it said above. Nothing more was done.' }
    Send-PeopleDone $out
    if ($action -eq 'remove') {
        Write-Host 'The example people the Starter Pack made are removed from Keycloak; anyone else is left as they are.'
    } elseif ($action -eq 'new-password') {
        Write-Host 'The example people now all sign in with the password you gave.'
    } else {
        Write-Host 'The example people added just now sign in with the password you gave.'
        Write-Host '(Any that were there already keep theirs: -NewPassword gives them all a new one.)'
    }
}

# People of your own in the bundled Keycloak, in either mode (ADR 0051, decision 10): made by one
# run of keycloak-setup with Keycloak's own admin, never by Kilnmark, which holds no Keycloak
# credential. Each is given a temporary password, so Keycloak has them choose their own at their
# first sign-in, and the one-time code applies as for everyone. Never in the Starter Pack's group,
# so its removal never touches them; never at kilnmark.com. The password is typed, not shown, or
# piped in: never shown, never in config\.env, a command line or logs\; it goes to keycloak-setup
# on its standard input. Keycloak's own admin (KILNMARK_KEYCLOAK_ADMIN) signs in to Keycloak's
# console only, never to Kilnmark.
$script:PeopleSoft = $false
# Nobody made: the people command stops; install (PeopleSoft) says so and goes on.
function Write-NoPerson([string]$Message) {
    if ($script:PeopleSoft) {
        Write-Host ($Message + ' Add them later with: ' + (Join-Path $script:Root 'kilnmark.cmd') + ' people add') -ForegroundColor Yellow
    } else {
        Fail $Message
    }
}

function Read-Secret([string]$Prompt) {
    $a = Read-Host -AsSecureString $Prompt
    return (ConvertFrom-Secret $a)
}

# Asks for one person: at a console, the password twice, not shown; else lines on standard input
# (email, first name, last name, groups unless -Groups gives them, password). -Groups: what
# install's first admin gets (kilnmark-admins). Nothing when nobody is to be made. The quick checks
# here save typing a password for nothing; keycloak-setup checks it all again.
function Read-Person([string]$Groups) {
    $redirected = [Console]::IsInputRedirected
    if ($redirected) {
        $email = [Console]::In.ReadLine()
    } else {
        if ($Groups) { Write-Host "Kilnmark's first admin: a person of your own, who signs in to Kilnmark with Keycloak." }
        $prompt = 'Their email, which is their user name'; if ($Groups) { $prompt += ' (Enter to skip)' }
        $email = Read-Host $prompt
    }
    if ($null -eq $email) { $email = '' }
    $email = ($email -replace '\s', '').ToLowerInvariant()
    if (-not $email) {
        if ($Groups) {
            Write-Host ('No first admin was made. Add people later with ' + (Join-Path $script:Root 'kilnmark.cmd') + ' people add (or people import, or Keycloak''s console: INSTALL.md, "Keycloak, if you have no identity provider").')
        } else {
            Write-NoPerson 'No email was given. Nobody was made.'
        }
        return $null
    }
    if ($email -cnotmatch '^[a-z0-9._%+-]+@[a-z0-9-]+(\.[a-z0-9-]+)+$') { Write-NoPerson 'That is not an email address. Nobody was made.'; return $null }
    if ($email.EndsWith('@kilnmark.com') -or $email.EndsWith('.kilnmark.com')) {
        Write-NoPerson "Use an address of your own organisation: kilnmark.com addresses are the Starter Pack's example people. Nobody was made."
        return $null
    }
    if ($redirected) {
        $first = [Console]::In.ReadLine(); $last = [Console]::In.ReadLine()
        if ($Groups) { $groupList = $Groups } else { $groupList = [Console]::In.ReadLine() }
        $password = [Console]::In.ReadLine()
    } else {
        $first = Read-Host 'Their first name'
        $last = Read-Host 'Their last name'
        if ($Groups) { $groupList = $Groups } else {
            $groupList = Read-Host 'Their groups, separated by ; (kilnmark-viewers, kilnmark-stewards, kilnmark-designers, kilnmark-admins, kilnmark-sensitive-data)'
        }
        $password = Read-Secret 'A temporary password for them, 12 characters or more (not shown; they choose their own at their first sign-in)'
        $again = Read-Secret 'The same again'
        if ($password -cne $again) { $password = $null; $again = $null; Write-NoPerson 'The two did not match. Nobody was made.'; return $null }
        $again = $null
    }
    if ($null -eq $first) { $first = '' }
    if ($null -eq $last) { $last = '' }
    if ($null -eq $groupList) { $groupList = '' }
    if ($null -eq $password) { $password = '' }
    $first = $first.TrimEnd("`r"); $last = $last.TrimEnd("`r"); $groupList = $groupList.TrimEnd("`r"); $password = $password.TrimEnd("`r")
    if (-not $first -or -not $last) { $password = $null; Write-NoPerson 'A person needs a first and a last name. Nobody was made.'; return $null }
    if (-not ($groupList -replace '[\s;,]', '')) { $password = $null; Write-NoPerson "A person needs at least one of Kilnmark's groups. Nobody was made."; return $null }
    if ($password.Length -lt 12) { $password = $null; Write-NoPerson 'The temporary password is 12 characters or more. Nobody was made.'; return $null }
    if ($password -match '\p{Cc}') { $password = $null; Write-NoPerson 'The password holds a control character (a tab, say), which it may not. Nobody was made.'; return $null }
    return @{ Email = $email; First = $first; Last = $last; Groups = $groupList; Password = $password }
}

# One run of keycloak-setup for the people command, these lines on its standard input (UTF-8, no
# byte-order mark). Its output, filtered; $script:PeopleOk says whether it worked.
$script:PeopleOk = $false
function Invoke-PeopleSetup([string]$Action, $Person, [string[]]$Lines) {
    if (-not $script:PeopleMode) { Fail "Kilnmark's own record of its deployment mode was not read, so nobody was made or changed." }
    $base = Get-ComposeArgs
    $id = Get-RecordedSetting 'install_id'
    # the mode Kilnmark recorded, never config\.env's alone, and the install's id
    $env:KILNMARK_MODE = $script:PeopleMode
    $env:KILNMARK_INSTALL_ID = $id
    $env:KILNMARK_PEOPLE_ACTION = $Action
    if ($Person) {
        $env:KILNMARK_PERSON_EMAIL = $Person.Email; $env:KILNMARK_PERSON_FIRST_NAME = $Person.First
        $env:KILNMARK_PERSON_LAST_NAME = $Person.Last; $env:KILNMARK_PERSON_GROUPS = $Person.Groups
    }
    $encoding = $OutputEncoding
    try {
        Write-RunSecrets
        $OutputEncoding = New-Object System.Text.UTF8Encoding $false
        $out = @($Lines | & $script:Engine @base run --rm -T keycloak-setup 2>&1 | ForEach-Object { '' + $_ } |
            Where-Object { -not (Test-ComposeCommand $_) })
        $script:PeopleOk = ($LASTEXITCODE -eq 0)
    } finally {
        $OutputEncoding = $encoding
        $Lines = $null
        Remove-RunSecrets
        foreach ($n in @('KILNMARK_PEOPLE_ACTION', 'KILNMARK_PERSON_EMAIL', 'KILNMARK_PERSON_FIRST_NAME', 'KILNMARK_PERSON_LAST_NAME', 'KILNMARK_PERSON_GROUPS', 'KILNMARK_MODE', 'KILNMARK_INSTALL_ID')) {
            Remove-Item ('Env:' + $n) -ErrorAction SilentlyContinue
        }
    }
    return , $out
}

# Tells Kilnmark what keycloak-setup did (its lines; add, import, activate, or expire for an ordinary
# start), so Kilnmark's audit log holds it: auth.person_added for each person made, with their
# groups, auth.people_imported for each import, auth.person_activated, and auth.person_disabled for
# each person disabled for an unused temporary password. Never a password.
function Send-OwnPeopleDone([string[]]$Lines, [string]$Via) {
    $made = @(); $kept = @(); $refused = '0'; $activated = @(); $disabled = @()
    foreach ($line in $Lines) {
        if ($line -cmatch '^keycloak-setup: made ([a-z0-9._%+@-]+) \(([a-z;-]+)\): (they choose their own password|waiting to be activated)') { $made += ('{"person":"' + $Matches[1] + '","groups":"' + $Matches[2] + '"}') }
        elseif ($line -cmatch '^keycloak-setup: left ([a-z0-9._%+@-]+) as they are: someone with this address is here already') { $kept += ('"' + $Matches[1] + '"') }
        elseif ($line -cmatch '^keycloak-setup: people import: made [0-9]+, already there [0-9]+, refused ([0-9]+)') { $refused = $Matches[1] }
        elseif ($line -cmatch '^keycloak-setup: activated ([a-z0-9._%+@-]+): they choose their own password') { $activated += ('"' + $Matches[1] + '"') }
        elseif ($line -cmatch '^keycloak-setup: disabled ([a-z0-9._%+@-]+): their temporary password was not used') { $disabled += ('"' + $Matches[1] + '"') }
    }
    $bodies = @()
    # what an ordinary run of setup did, before the command's own work: its own record, 500 people
    # at a time (Kilnmark takes at most 1000 in one)
    for ($i = 0; $i -lt $disabled.Count; $i += 500) {
        $chunk = @($disabled[$i..([Math]::Min($i + 499, $disabled.Count - 1))])
        $bodies += ('{"via":"expire","disabled":[' + ($chunk -join ',') + ']}')
    }
    if ($Via -eq 'activate') {
        if ($activated.Count -gt 0) { $bodies += ('{"via":"activate","activated":[' + ($activated -join ',') + ']}') }
    } elseif ($Via -ne 'expire' -and ($made.Count -gt 0 -or $kept.Count -gt 0 -or $Via -eq 'import')) {
        $bodies += ('{"via":"' + $Via + '","made":[' + ($made -join ',') + '],"kept":[' + ($kept -join ',') + '],"refused":' + $refused + '}')
    }
    if (@($Lines | Where-Object { $_.StartsWith('keycloak-setup: stamped the realm') }).Count -gt 0) { $bodies += '{"via":"stamp"}' }
    $undo = @()
    foreach ($b in $bodies) {
        $code = Invoke-KilnmarkApi 'POST' '/api/v1/auth/people' $b
        if ($code -ne '204') {
            Write-Host ("Warning: Kilnmark did not record this in its audit log (HTTP " + $code + "); Keycloak's admin events have it.") -ForegroundColor Yellow
            if ($b.StartsWith('{"via":"add"') -or $b.StartsWith('{"via":"import"') -or $b.StartsWith('{"via":"activate"')) {
                foreach ($line in $Lines) {
                    if ($line -cmatch '^keycloak-setup: (made|activated) ([a-z0-9._%+@-]+)[ :]') { $undo += $Matches[2] }
                }
            }
        }
    }
    # Nobody stays able to sign in without that record (fail closed): disabled again.
    if ($undo.Count -gt 0) {
        $out = Invoke-PeopleSetup 'disable' $null $undo
        if ($script:PeopleOk -and @($out | Where-Object { $_ -match '^keycloak-setup: disabled .* again' }).Count -gt 0) {
            Write-Host 'So they were disabled again, until Kilnmark answers: then give each a temporary password with people activate EMAIL.' -ForegroundColor Yellow
        } else {
            Write-Host "Warning: they could not be disabled again either: disable them in Keycloak's console." -ForegroundColor Yellow
        }
    }
}

# A temporary password, typed twice, not shown, at a console; else one line on standard input: $Whose
# says whose. Never shown. Each person chooses their own at their first sign-in.
function Read-TemporaryPassword([string]$Whose) {
    if ([Console]::IsInputRedirected) {
        $password = [Console]::In.ReadLine(); if ($null -eq $password) { $password = '' }; $password = $password.TrimEnd("`r")
    } else {
        $password = Read-Secret ('A temporary password for ' + $Whose + ', 12 characters or more (not shown)')
        $again = Read-Secret 'The same again'
        if ($password -cne $again) { $password = $null; $again = $null; Fail 'The two did not match. Nothing was changed.' }
        $again = $null
    }
    if ($password.Length -lt 12) { $password = $null; Fail 'The temporary password is 12 characters or more. Nothing was changed.' }
    if ($password -match '\p{Cc}') { $password = $null; Fail 'The password holds a control character (a tab, say), which it may not. Nothing was changed.' }
    return $password
}

# Kilnmark must answer, as its admin, before anyone is made: a person is never made without being
# recorded in its audit log.
function Assert-KilnmarkAnswers {
    $code = Invoke-KilnmarkApi 'GET' '/api/v1/audit?limit=1' ''
    if ($code -ne '200') { Fail ('Kilnmark does not answer (HTTP ' + $code + '), so nobody was made or changed: a person is never made without a record in its audit log. Start it with kilnmark.cmd up, then try again.') }
}

# Makes the person Read-Person asked for, then records it. Keycloak must be up.
function New-Person($Person) {
    Assert-KilnmarkAnswers
    $why = Get-PeopleModeProblem
    if ($why) { $Person.Password = $null; Write-NoPerson $why; return $false }
    Write-Host ('Asking keycloak-setup to add ' + $Person.Email + ' ...')
    $out = Invoke-PeopleSetup 'add' $Person @($Person.Password)
    $Person.Password = $null
    $out | Out-Host
    if (-not $script:PeopleOk) { Send-OwnPeopleDone $out 'add'; Write-NoPerson 'keycloak-setup stopped: see what it said above.'; return $false }
    Send-OwnPeopleDone $out 'add'
    if (@($out | Where-Object { $_.StartsWith('keycloak-setup: made ' + $Person.Email + ' (', [StringComparison]::Ordinal) }).Count -gt 0) {
        Write-Host ($Person.Email + ' signs in to Kilnmark with that password, then chooses their own and sets up a one-time code.')
    } else {
        Write-Host ($Person.Email + ' was here already, so was left as they are: nothing was changed.')
    }
    return $true
}

function Invoke-People([string[]]$Options) {
    $what = $null; if ($Options.Count -gt 0) { $what = $Options[0] }
    # people expire -Project NAME: the scheduled task names the compose project it is for
    if ($what -eq 'expire') {
        if ($Options.Count -eq 3 -and $Options[1] -eq '-Project' -and $Options[2] -cmatch '^[a-z0-9][a-z0-9_-]*$') { $script:Project = $Options[2] }
        elseif ($Options.Count -ne 1) { Fail 'people expire takes no options.' }
    }
    [void](Get-CurrentVersion)
    Update-Settings
    if (-not (Test-KeycloakOn)) { Fail "This install does not use the bundled Keycloak: add people in your organisation's own identity provider, or with API tokens only, make tokens under API tokens. The people commands are for the bundled Keycloak (the auth profile)." }
    switch ($what) {
        'add' {
            if ($Options.Count -gt 1) { Fail 'people add takes no options: it asks.' }
            Assert-KilnmarkAnswers
            Assert-PeopleMode
            $person = Read-Person ''
            if ($person) { [void](New-Person $person) }
        }
        'import' {
            if ($Options.Count -ne 2 -or -not (Test-Path -PathType Leaf $Options[1])) { Fail 'Say which file: kilnmark.cmd people import people.csv (an example: containers\keycloak\people.example.csv in the release).' }
            $file = (Resolve-Path $Options[1]).Path
            Assert-KilnmarkAnswers
            Assert-PeopleMode
            # A class on a Starter Pack install shares one temporary password; on production nobody is
            # given one: each waits, disabled, for people activate. By the recorded mode.
            $lines = @([IO.File]::ReadAllLines($file, [Text.Encoding]::UTF8))
            if ($script:PeopleMode -ceq 'starter-pack') { $lines = @(Read-TemporaryPassword 'everyone in the file') + $lines }
            Write-Host 'Asking keycloak-setup to add the people in the file ...'
            $out = Invoke-PeopleSetup 'import' $null $lines
            $lines = $null
            $out | Out-Host
            Send-OwnPeopleDone $out 'import'
            if (-not $script:PeopleOk) { Fail 'keycloak-setup stopped: see what it said above (how many it made, if any, before it stopped).' }
            if ($script:PeopleMode -ceq 'starter-pack') {
                Write-Host 'Each person made signs in with that password, then chooses their own and sets up a one-time code.'
            } else {
                Write-Host ('Each person made waits, disabled, until you give them a temporary password of their own: ' + (Join-Path $script:Root 'kilnmark.cmd') + ' people activate EMAIL')
            }
        }
        'activate' {
            if ($Options.Count -ne 2) { Fail 'Say whom: kilnmark.cmd people activate EMAIL' }
            $email = ($Options[1] -replace '\s', '').ToLowerInvariant()
            if ($email -cnotmatch '^[a-z0-9._%+-]+@[a-z0-9-]+(\.[a-z0-9-]+)+$') { Fail 'That is not an email address. Nothing was changed.' }
            Assert-KilnmarkAnswers
            Assert-PeopleMode
            $password = Read-TemporaryPassword $email
            Write-Host ('Asking keycloak-setup to activate ' + $email + ' ...')
            $out = Invoke-PeopleSetup 'activate' @{ Email = $email; First = ''; Last = ''; Groups = '' } @($password)
            $password = $null
            $out | Out-Host
            Send-OwnPeopleDone $out 'activate'
            if (-not $script:PeopleOk) { Fail 'Nobody was activated: see what keycloak-setup said above.' }
            Write-Host ($email + ' signs in to Kilnmark with that password, then chooses their own and sets up a one-time code.')
        }
        'list' {
            if ($Options.Count -gt 1) { Fail 'people list takes no options.' }
            Assert-PeopleMode
            $out = Invoke-PeopleSetup 'list' $null @()
            if (-not $script:PeopleOk) { $out | Out-Host; Fail 'keycloak-setup did not finish: see what it said above.' }
            Send-OwnPeopleDone $out 'expire'
            foreach ($line in $out) {
                if ($line.StartsWith('keycloak-setup: disabled ')) { Write-Host $line.Substring(16) }
                elseif ($line.StartsWith('keycloak-setup: person ')) { Write-Host $line.Substring(23) }
                elseif ($line.StartsWith('keycloak-setup: people: ')) { Write-Host ('people: ' + $line.Substring(24)) }
            }
            Write-Host '(status=waiting-to-be-activated: give them a temporary password with people activate EMAIL; must-choose-password=yes: they have not yet signed in with it.)'
        }
        'expire' {
            # Every hour, from the scheduled task up sets up (which names the project); by hand too.
            # Never what someone else may have changed (ADR 0051): refused, and recorded.
            $why = Get-TreeProblem
            if ($why) {
                [void](Invoke-KilnmarkApi 'POST' '/api/v1/auth/people' '{"via":"expire-refused"}')
                Fail ('people expire was refused: ' + $why + '. Make it yours alone, then run it again.')
            }
            # Nothing to do while Keycloak is stopped: nobody signs in then, and each start expires them first.
            $kc = Get-Setting 'KILNMARK_KEYCLOAK_PORT'; if (-not $kc) { $kc = '8180' }
            if (-not (Test-Answers ('http://localhost:' + $kc + '/realms/kilnmark'))) {
                Write-Host 'Keycloak does not answer, so nothing was expired now: each start of Kilnmark expires unused temporary passwords too.'
                return
            }
            Assert-PeopleMode
            $out = Invoke-PeopleSetup 'expire' $null @()
            if (-not $script:PeopleOk) { $out | Out-Host; Fail 'keycloak-setup did not finish: see what it said above.' }
            Send-OwnPeopleDone $out 'expire'
            foreach ($line in $out) { if ($line.StartsWith('keycloak-setup: disabled ')) { Write-Host $line.Substring(16) } }
            Write-Host 'people expire: done.'
        }
        default { Fail 'Say what: kilnmark.cmd people add | people import FILE | people activate EMAIL | people list | people expire' }
    }
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
    # yours only, wherever it is: it holds the database, the keys and, with Keycloak, its secrets
    Protect-Folder $dir
    $bad = { param($what) Fail ('Backup failed: ' + $what + '. Nothing usable is in ' + $dir + '; delete it and run again.') }
    $pg = Get-ContainerId 'postgres'
    # One consistent snapshot while Kilnmark keeps working, read back in full as a restore would.
    if (-not (Invoke-Compose exec -T postgres sh -c 'pg_dump -U kilnmark -d kilnmark --format=custom -f /tmp/kilnmark-db.dump && pg_restore -f /dev/null /tmp/kilnmark-db.dump')) { & $bad 'the database could not be dumped, or the dump cannot be read back' }
    & $script:Engine cp ($pg + ':/tmp/kilnmark-db.dump') (Join-Path $dir 'kilnmark-db.dump') | Out-Host
    if ($LASTEXITCODE -ne 0) { & $bad 'the dump could not be copied out' }
    [void](Invoke-Compose exec -T postgres rm -f /tmp/kilnmark-db.dump)
    $migration = Read-Compose exec -T postgres psql -U kilnmark -d kilnmark -Atc 'SELECT version FROM kilnmark.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1'
    if (-not $migration) { & $bad "the database's migration could not be read" }
    # The install's deployment mode and its own id, made at its first start (ADR 0051): a restore takes
    # the backup only into an install of the same mode, and checks Keycloak's database against the id.
    $mode = Get-RecordedMode
    $installId = Get-RecordedSetting 'install_id'
    if (-not $mode -or -not $installId) { & $bad 'the database has no deployment mode or install id recorded yet (Kilnmark records them as it starts: start it, then back up)' }
    # Not the keys an earlier restore set aside, nor its note for the audit log.
    $km = Get-ContainerId 'kilnmark'
    if (-not (Invoke-Compose exec -T kilnmark tar -C /var/lib/kilnmark --exclude=./before-restore-* --exclude=./restored.properties -cf /tmp/kilnmark-keys.tar .)) { & $bad 'the keys volume could not be copied' }
    & $script:Engine cp ($km + ':/tmp/kilnmark-keys.tar') (Join-Path $dir 'kilnmark-keys.tar') | Out-Host
    if ($LASTEXITCODE -ne 0) { & $bad 'the copy of the keys volume could not be copied out' }
    [void](Invoke-Compose exec -T kilnmark rm -f /tmp/kilnmark-keys.tar)
    $list = (& tar.exe -tf (Join-Path $dir 'kilnmark-keys.tar') | Out-String)
    if ($LASTEXITCODE -ne 0) { & $bad 'the copy of the keys volume cannot be read back' }
    if ($list -notmatch 'secret\.key') { Write-Host 'Note: no source password is stored yet, so the keys volume holds no key.' }
    # With the bundled Keycloak, its database the same way (people, groups, one-time code setups),
    # dumped inside its container as its own user, so no password is needed or shown.
    $keycloak = Test-KeycloakOn
    $files = @('kilnmark-db.dump', 'kilnmark-keys.tar', 'backup.txt')
    if ($keycloak) {
        $kdb = Get-ContainerId 'keycloak-db'
        if (-not (Invoke-Compose exec -T keycloak-db sh -c 'pg_dump -U keycloak -d keycloak --format=custom -f /tmp/keycloak-db.dump && pg_restore -f /dev/null /tmp/keycloak-db.dump')) { & $bad "Keycloak's database could not be dumped, or the dump cannot be read back" }
        & $script:Engine cp ($kdb + ':/tmp/keycloak-db.dump') (Join-Path $dir 'keycloak-db.dump') | Out-Host
        if ($LASTEXITCODE -ne 0) { & $bad "the dump of Keycloak's database could not be copied out" }
        [void](Invoke-Compose exec -T keycloak-db rm -f /tmp/keycloak-db.dump)
        $files = @('keycloak-db.dump') + $files
    }
    $kcLine = 'keycloak=no'; if ($keycloak) { $kcLine = 'keycloak=yes' }
    Write-UnixText (Join-Path $dir 'backup.txt') @(('taken_at=' + $now.ToString('yyyy-MM-ddTHH:mm:ssZ')), ('kilnmark_version=' + $v), ('migration=' + $migration.Trim()), $kcLine, ('deployment_mode=' + $mode), ('install_id=' + $installId))
    Write-Sums $dir $files
    Write-Host ('Backed up Kilnmark ' + $v + ' to ' + $dir)
    Write-Host ('  kilnmark-db.dump   the database (' + (Get-Item (Join-Path $dir 'kilnmark-db.dump')).Length + ' bytes)')
    Write-Host '  kilnmark-keys.tar  the key for stored source passwords, and drivers: keep it apart from the dumps'
    if ($keycloak) { Write-Host ("  keycloak-db.dump   Keycloak's database: its people, groups and one-time code setups (" + (Get-Item (Join-Path $dir 'keycloak-db.dump')).Length + ' bytes)') }
}

# Whether the bundled Keycloak is on (the auth profile): backup and restore take its database too.
function Test-KeycloakOn { return (',' + ((Get-Setting 'COMPOSE_PROFILES') -replace '\s', '') + ',').Contains(',auth,') }

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
    # Whether the backup has Keycloak's database: backup.txt says so, and then its checksum must match.
    $withKeycloak = [bool](@([IO.File]::ReadAllLines((Join-Path $src 'backup.txt')) | Where-Object { $_ -ceq 'keycloak=yes' }).Count)
    if ($withKeycloak) { $files += 'keycloak-db.dump' }
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
    $restoreKeycloak = $false
    if ($withKeycloak -and (Test-KeycloakOn)) {
        $restoreKeycloak = $true
    } elseif ($withKeycloak) {
        Fail "The backup has the bundled Keycloak's database, but Keycloak is not on here (the auth profile): turn it on, then restore again. Nothing was changed."
    } elseif (Test-KeycloakOn) {
        Write-Host "Note: the backup has no Keycloak database (taken without the bundled Keycloak, or by an earlier version): Keycloak's people stay as they are now."
    }
    if (-not $yes) {
        Write-Host ('This puts Kilnmark back to the backup taken at ' + $taken + ' (Kilnmark ' + $from + ').')
        if ($restoreKeycloak) { Write-Host 'The bundled Keycloak goes back to it too: its people, groups and one-time code setups.' }
        Write-Host ('The database and keys it replaces are kept, to go back to. To go on: kilnmark.cmd restore ' + $src + ' -Yes')
        exit 2
    }
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')
    $before = 'kilnmark_before_' + $stamp
    $kcBefore = 'keycloak_before_' + $stamp
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

    if ($restoreKeycloak) {
        Write-Host "Restoring Keycloak's database into a database of its own (Keycloak keeps running)"
        [void](Invoke-Compose up -d keycloak-db)
        $ready = $false
        for ($i = 0; $i -lt 60; $i++) {
            $base = Get-ComposeArgs
            & $script:Engine @base exec -T keycloak-db pg_isready -h 127.0.0.1 -U keycloak -d postgres *> $null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Seconds 2
        }
        if (-not $ready) { Fail "Keycloak's database did not become ready. Kilnmark and its database are as they were. See: kilnmark.cmd logs keycloak-db" }
        $kdb = Get-ContainerId 'keycloak-db'
        & $script:Engine cp (Join-Path $src 'keycloak-db.dump') ($kdb + ':/tmp/restore-keycloak.dump') | Out-Host
        if ($LASTEXITCODE -ne 0) { Fail "Keycloak's dump could not be copied into its database container. Kilnmark and Keycloak are as they were." }
        $ok = Invoke-Compose exec -T keycloak-db sh -c 'dropdb -U keycloak --if-exists keycloak_restoring && createdb -U keycloak keycloak_restoring && pg_restore -U keycloak -d keycloak_restoring --exit-on-error --single-transaction /tmp/restore-keycloak.dump; rc=$?; rm -f /tmp/restore-keycloak.dump; exit $rc'
        if (-not $ok) { Fail "Keycloak's database could not be restored into keycloak_restoring. Kilnmark and Keycloak are as they were." }
    }

    # The backup's deployment mode (ADR 0051) must be this install's: a backup from before there were
    # modes (0.1.1 or earlier, a preview) is never restored, nor one of the other mode. Checked on the
    # restored copy, before anything is stopped or replaced.
    $modeSql = "SELECT value FROM kilnmark.server_setting WHERE key = 'deployment_mode'"
    $base = Get-ComposeArgs
    $backupMode = (& $script:Engine @base exec -T postgres psql -U kilnmark -d kilnmark_restoring -X -q -At -c $modeSql 2> $null | Out-String).Trim()
    $liveMode = Get-RecordedMode
    if (-not $backupMode) { Fail 'The backup has no deployment mode recorded: it is from before deployment modes (release 0.1.1 or earlier, a preview), which cannot be restored into this release. Install Kilnmark afresh. Nothing was changed.' }
    # backup.txt names the mode and the install's id, as the dump recorded them: both must agree, so a
    # dump of another install, or a backup.txt changed since, is refused.
    $backupId = (& $script:Engine @base exec -T postgres psql -U kilnmark -d kilnmark_restoring -X -q -At -c "SELECT value FROM kilnmark.server_setting WHERE key = 'install_id'" 2> $null | Out-String).Trim()
    $fileMode = $info['deployment_mode']; $fileId = $info['install_id']
    if (-not $fileMode -or -not $fileId -or -not $backupId) { Fail 'The backup does not say which install it is of, and in which mode (taken by an earlier version, before they were recorded): take a new backup with this version. Nothing was changed.' }
    if ($fileMode -cne $backupMode -or $fileId -cne $backupId) { Fail "The backup's backup.txt does not match its database (the dump is of another install, or backup.txt was changed): the backup is not whole. Nothing was changed." }
    if (($liveMode -and $backupMode -cne $liveMode) -or $backupMode -cne (Get-Setting 'KILNMARK_MODE')) {
        Fail ('The backup is of a ' + $backupMode + ' install, and this is not one: an install never changes mode (ADR 0051). Restore it into an install of its own mode. Nothing was changed.')
    }
    # Keycloak's database must be this backup's install's: the id and mode its realm carries (the
    # people commands keep them there), where it has them. A production install's never holds the
    # Starter Pack's group (its mark) or example people (by their user names, which nobody can change).
    if ($restoreKeycloak) {
        $kcSql = {
            param($q)
            $b = Get-ComposeArgs
            $o = (& $script:Engine @b exec -T keycloak-db psql -U keycloak -d keycloak_restoring -X -q -At -v ON_ERROR_STOP=1 -c $q 2> $null | Out-String).Trim()
            if ($LASTEXITCODE -ne 0) { Fail "Keycloak's database in the backup could not be checked. Nothing was changed." }
            return $o
        }
        $attr = "SELECT ra.value FROM realm_attribute ra JOIN realm r ON r.id = ra.realm_id WHERE r.name = 'kilnmark' AND ra.name = '{0}'"
        $kcId = & $kcSql ($attr -f 'kilnmark_install_id')
        $kcMode = & $kcSql ($attr -f 'kilnmark_mode')
        if (($kcId -and $kcId -cne $backupId) -or ($kcMode -and $kcMode -cne $backupMode)) { Fail "Keycloak's database in the backup belongs to another Kilnmark install, or another mode: the backup is not whole. Nothing was changed." }
        if ($backupMode -ceq 'production' -and (-not $kcId -or -not $kcMode)) { Fail "Keycloak's database in the backup does not say which install it belongs to (taken before the control script kept that on its realm), so it cannot be told from another install's: take a new backup with this version. Nothing was changed." }
        if ($backupMode -ceq 'production') {
            $pack = & $kcSql "SELECT count(*) FROM group_attribute WHERE name = 'kilnmark_starter_pack'"
            $examples = & $kcSql "SELECT count(*) FROM user_entity u JOIN realm r ON r.id = u.realm_id WHERE r.name = 'kilnmark' AND u.username IN ('vera.lane@kilnmark.com', 'sam.okafor@kilnmark.com', 'dana.reyes@kilnmark.com', 'priya.shah@kilnmark.com', 'alex.morgan@kilnmark.com')"
            if ($pack -ne '0' -or $examples -ne '0') { Fail "Keycloak's database in the backup has the Starter Pack's group or example people, which a production install never has: it is not this install's. Nothing was changed." }
        }
    }

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

    if ($restoreKeycloak) {
        Write-Host 'Stopping Keycloak'
        [void](Invoke-Compose stop keycloak)
        Write-Host ("Putting Keycloak's restored database in place; the previous one is kept as " + $kcBefore)
        if (-not (Invoke-Compose exec -T keycloak-db psql -U keycloak -d postgres -q -v ON_ERROR_STOP=1 -c ('ALTER DATABASE keycloak RENAME TO ' + $kcBefore)) -or
            -not (Invoke-Compose exec -T keycloak-db psql -U keycloak -d postgres -q -v ON_ERROR_STOP=1 -c 'ALTER DATABASE keycloak_restoring RENAME TO keycloak')) {
            Fail ("Keycloak's restored database could not be put in place. Kilnmark and Keycloak are stopped; Kilnmark's database and keys are restored, and Keycloak's previous database is " + $kcBefore + ' (or still keycloak): see the guide, "Going back".')
        }
    }

    Write-Host 'Starting Kilnmark'
    Invoke-Up @('-NoBrowser')
    Write-Host ('Restored Kilnmark to the backup taken at ' + $taken + '. Now:')
    Write-Host '  - revoke again any token revoked after that time, and check Settings > Access review;'
    if (-not $keys) { Write-Host "  - enter every stored password again (Sources; AI models' keys; the mail password);" }
    Write-Host '  - run again the scans and pipelines that ran after it, if their results are needed;'
    Write-Host ('  - once all is well, drop the previous database: ' + $script:Engine + ' compose -p ' + $Project + ' exec postgres dropdb -U kilnmark ' + $before)
    if ($restoreKeycloak) {
        Write-Host '  - Keycloak is back to the backup too: people added or changed since then are gone, and anyone'
        Write-Host '    removed since is back (remove them again). Then drop its previous database:'
        Write-Host ('    ' + $script:Engine + ' compose -p ' + $Project + ' exec keycloak-db dropdb -U keycloak ' + $kcBefore)
    }
}

# --- main --------------------------------------------------------------------------------------

switch ($Command) {
    'install' { Invoke-Install $Rest }
    { $_ -in @('up', 'start') } { Start-Log 'up'; Invoke-Up $Rest; Stop-Log }
    { $_ -in @('down', 'stop') } { Start-Log 'down'; Invoke-Down; Stop-Log }
    'restart' { Start-Log 'restart'; Invoke-Restart $Rest; Stop-Log }
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
            if (Test-KeycloakOn) {
                $expiry = Get-ExpiryState
                Write-Host ('People expiry: ' + $expiry)
                if ($expiry -eq 'not set up') { [void]$script:Warnings.Add('The hourly expiry of unused temporary passwords is not set up: kilnmark.cmd up sets it up (a scheduled task).') }
            }
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
    'people' { if ($Rest.Count -gt 0 -and $Rest[0] -eq 'expire') { Start-Log 'people-expire' -Daily } else { Start-Log 'people' }; Invoke-People $Rest; Stop-Log }
    'restore' { Start-Log 'restore'; Invoke-Restore $Rest; Stop-Log }
    'upgrade' { Invoke-Upgrade $Rest }
    'uninstall' { Invoke-Uninstall $Rest }
    'version' { if ($script:Root) { Write-Host ('Kilnmark ' + (Get-CurrentVersion)) } else { Write-Host ('Kilnmark ' + (Get-VersionOf $Here) + ' (not installed)') } }
    { $_ -in @('help', '-h', '--help', '/?') } { Show-Usage }
    default { Show-Usage; exit 2 }
}
exit 0
