# Kilnmark's control script for Windows (INSTALL.md; ADR 0044, ADR 0051 decision 17). Windows
# PowerShell 5.1 or later; run it through kilnmark.cmd, which passes -ExecutionPolicy Bypass:
#   kilnmark.cmd install [-Answers install.conf] [-Offline IMAGES]   the guided install
#   kilnmark.cmd up | down | restart | status | logs | check | configure | backup | restore | ...
#   kilnmark.cmd help
# install, upgrade and uninstall ask Windows for an administrator (UAC) for the one step that
# changes C:\Program Files and C:\ProgramData; every other command runs as the operator, the account
# that runs Kilnmark, never elevated. Run with no command, it starts Kilnmark (up). The program
# folder (C:\Program Files\Kilnmark, the Administrators') holds versions\<version>\ (a release,
# never edited), current.txt (which version runs), the launchers and data-folder.txt, which names
# the data folder (C:\ProgramData\Kilnmark, the operator's, SYSTEM's and the Administrators'):
# config\.env (this site's settings and secrets), config\install.conf (the install's answers),
# certs\, backups\ and logs\. Data stays in the compose project's volumes. No command prints a
# secret. This file is ASCII only: Windows PowerShell 5.1 misreads anything else.

$ErrorActionPreference = 'Continue'   # native tools are judged by their exit codes, not stderr
# Podman says on every compose command that it runs an external compose tool: not news here.
$env:PODMAN_COMPOSE_WARNING_LOGS = 'false'
$Here = $PSScriptRoot
$Registry = 'ghcr.io/jporeilly'
$DefaultProgram = Join-Path $env:ProgramFiles 'Kilnmark'
$DefaultData = Join-Path $env:ProgramData 'Kilnmark'
# The compose project (set once the data folder is known, below): KILNMARK_PROJECT from the
# environment, else config\.env's, else kilnmark.
$Project = ''; if ($env:KILNMARK_PROJECT) { $Project = $env:KILNMARK_PROJECT }
# Compose fills ${NAME} from this process's environment before config\.env. These must mean what
# config\.env says, as this script reads them (ADR 0051), so a value set outside never counts.
$OneRun = @('KILNMARK_STARTER_PEOPLE_ACTION', 'KILNMARK_PEOPLE_ACTION', 'KILNMARK_PERSON_EMAIL', 'KILNMARK_PERSON_FIRST_NAME', 'KILNMARK_PERSON_LAST_NAME', 'KILNMARK_PERSON_GROUPS', 'KILNMARK_INSTALL_ID', 'KILNMARK_RUN_DIR')
foreach ($n in (@('KILNMARK_MODE', 'KILNMARK_STARTER', 'KILNMARK_ENV_FILE', 'KILNMARK_CERTS_DIR', 'KILNMARK_SECRETS_DIR', 'KILNMARK_KEYCLOAK_BIND', 'KILNMARK_OIDC_VIEWER_GROUPS', 'KILNMARK_OIDC_STEWARD_GROUPS', 'KILNMARK_OIDC_DESIGNER_GROUPS', 'KILNMARK_OIDC_ADMIN_GROUPS', 'KILNMARK_OIDC_UNMASK_GROUPS') + $OneRun)) {
    Remove-Item ('Env:' + $n) -ErrorAction SilentlyContinue
}
$Launchers = @('kilnmark.ps1', 'kilnmark.cmd', 'start-kilnmark.cmd', 'stop-kilnmark.cmd', 'create-shortcut.ps1', 'kilnmark.ico')

$Command = 'up'
$Rest = @()
if ($args.Count -gt 0) { $Command = [string]$args[0]; if ($args.Count -gt 1) { $Rest = @($args[1..($args.Count - 1)]) } }

$script:Root = $null
$script:Data = $null
$script:Engine = $null
$script:SkipVerify = $false
$script:KeyOverride = $null
$script:Offline = $null
$script:Build = $false
$script:WithStarter = $false
$script:Problems = New-Object System.Collections.ArrayList
$script:Fixes = New-Object System.Collections.ArrayList
$script:Warnings = New-Object System.Collections.ArrayList
$script:WarningFixes = New-Object System.Collections.ArrayList
$script:Passed = New-Object System.Collections.ArrayList
$script:Logging = $false

# --- progress: [n/N] steps, each OK or FAILED, with the time it took ---------------------------
$script:StepN = 0; $script:StepTotal = 0; $script:StepTitle = ''; $script:StepClock = $null
function Set-Steps([int]$Total, [int]$Done = 0) { $script:StepTotal = $Total; $script:StepN = $Done }
function Get-Took { if ($script:StepClock) { return ('{0:0.0} s' -f $script:StepClock.Elapsed.TotalSeconds) }; return '0.0 s' }
function Start-Step([string]$Title) {
    $script:StepN++; $script:StepTitle = $Title; $script:StepClock = [Diagnostics.Stopwatch]::StartNew()
    Write-Host ('[' + $script:StepN + '/' + $script:StepTotal + '] ' + $Title)
}
function Complete-Step {
    if ($script:StepTitle) { Write-Host ('[' + $script:StepN + '/' + $script:StepTotal + '] OK (' + (Get-Took) + '): ' + $script:StepTitle) -ForegroundColor Green }
    $script:StepTitle = ''
}

# A failure inside a step ends it FAILED, with the time it took and one line saying why.
function Fail([string]$Message) {
    if ($script:StepTitle) {
        Write-Host ('[' + $script:StepN + '/' + $script:StepTotal + '] FAILED (' + (Get-Took) + '): ' + $script:StepTitle + ': ' + $Message) -ForegroundColor Red
        $script:StepTitle = ''
    } else {
        Write-Host ('Error: ' + $Message) -ForegroundColor Red
    }
    if ($script:ResultFile) { try { [IO.File]::WriteAllText($script:ResultFile, ('FAILED ' + $Message)) } catch { } }
    if ($script:Logging) { try { Stop-Transcript | Out-Null } catch { } }
    exit 1
}
$script:ResultFile = $null

function Show-Usage {
    @'
Kilnmark control script.

Installing (it asks Windows for an administrator, UAC, for the step that needs one, and says why):
  install [-Answers FILE] [-Starter] [-ProgramDir DIR] [-DataDir DIR] [-Operator NAME]
          [-Offline IMAGES.tar.gz] [-Build] [-SkipVerify] [-NoStart] [-DryRun]
                       The guided install: it asks the questions (the mode, how people sign in,
                       the email domain, the host name, the ports, TLS, the first admin), each
                       with its default and checked as you answer, or reads them from an answers
                       file (install.conf.example says every key; it holds no secrets). Then the
                       program folder (C:\Program Files\Kilnmark, the Administrators'), the data
                       folder (C:\ProgramData\Kilnmark, yours, SYSTEM's and the Administrators'),
                       settings with new secrets, the images, the start and the first admin, each
                       step OK or FAILED. Run it from a prompt that is not elevated: Podman runs
                       as you. -DryRun checks the answers and the prerequisites, changing nothing.
  upgrade ARCHIVE [-Offline IMAGES] [-NoBackup] [-SkipVerify]
                       Back up, place the new version beside this one, and start it.
  uninstall [-Purge]   Stop Kilnmark, remove its hourly task and the program folder. -Purge also
                       deletes its volumes, images and the data folder (backups too).

Every day, as the operator, from a prompt that is not elevated:
  up [-NoBrowser]      Start Kilnmark (and Keycloak with the bundled Keycloak); wait until it
                       answers; open it in the browser. The default command.
  down                 Stop. Every volume, so all data, is kept.
  restart [SERVICE]    Restart everything (a stop, then up), or one service.
  status               Version, folders, containers, health and the last backup.
  logs [SERVICE] [-f]  The last 200 lines of the logs, or follow them.
  compose ARGS ...     Run compose as Kilnmark runs it (its project, settings, secrets and
                       profiles), for a command by hand: compose ps, compose exec postgres ...
  check                Check the prerequisites and the install, for its mode, changing nothing:
                       each problem with the command that fixes it.
  configure [KEY=VALUE ...]
                       Show the install's answers, or change one: checked, written to
                       config\.env, and only what it affects run again. The mode never changes.
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
  verify FILE [-Key PUB]   Check a downloaded file's signature (cosign) against Kilnmark's key.
  load-images FILE [-SkipVerify]   Load the offline images file.
  pull [-SkipVerify]   Pull this version's images from ghcr.io and check their signatures.
  build                Build the images here from containers\, instead of pulling or loading.
  backup [DIR]         Back up the database and the keys (default: the data folder's backups).
  restore BACKUP [-Yes] [-WithoutKeys]   Put Kilnmark back to a backup.
  version | help
'@ | Write-Host
}

# --- where things are ------------------------------------------------------------------------

# The program folder: this script's folder when it holds versions\, else two folders up when this
# script runs from versions\<version>\, else (a release folder not yet installed) none.
function Find-Root {
    if ($env:KILNMARK_ROOT) { return $env:KILNMARK_ROOT }
    if (Test-Path (Join-Path $Here 'versions')) { return $Here }
    $parent = Split-Path $Here -Parent
    if ((Split-Path $parent -Leaf) -eq 'versions') { return (Split-Path $parent -Parent) }
    return $null
}
$script:Root = Find-Root

# The data folder, as data-folder.txt in the program folder names it (install writes it; only the
# Administrators may change it): none when Kilnmark is not installed here.
function Find-Data {
    if (-not $script:Root) { return $null }
    $f = Join-Path $script:Root 'data-folder.txt'
    if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { return $null }
    $d = ([IO.File]::ReadAllText($f)).Trim()
    if (-not $d) { return $null }
    return $d
}
$script:Data = Find-Data

function Get-VersionOf([string]$Dir) {
    $file = Join-Path $Dir 'compose.yaml'
    if (-not (Test-Path -LiteralPath $file)) { return $null }
    foreach ($line in [IO.File]::ReadAllLines($file)) {
        if ($line -match '^\s*image: .*/kilnmark-server:(\S+)\s*$') { return $Matches[1] }
    }
    return $null
}

function Get-CurrentDir {
    if (-not $script:Root) { Fail 'Kilnmark is not installed here. Install it with: kilnmark.cmd install, from the unpacked release folder' }
    $marker = Join-Path $script:Root 'current.txt'
    if (-not (Test-Path -LiteralPath $marker)) { Fail ('No version is installed in ' + $script:Root + '.') }
    if (-not $script:Data) { Fail ((Join-Path $script:Root 'data-folder.txt') + " does not name Kilnmark's data folder: install Kilnmark afresh.") }
    $v = ([IO.File]::ReadAllText($marker)).Trim()
    return (Join-Path (Join-Path $script:Root 'versions') $v)
}

function Get-CurrentVersion { return (Get-VersionOf (Get-CurrentDir)) }

# A path relative to a folder, with forward slashes, as compose takes it on every engine (some
# compose tools split a Windows path at its drive letter's colon); none across drives.
function Get-RelativePath([string]$From, [string]$To) {
    $a = @([IO.Path]::GetFullPath($From).TrimEnd('\') -split '\\')
    $b = @([IO.Path]::GetFullPath($To).TrimEnd('\') -split '\\')
    if ($a[0] -ne $b[0]) { return $null }
    $i = 0
    while ($i -lt $a.Count -and $i -lt $b.Count -and $a[$i] -eq $b[$i]) { $i++ }
    $parts = @()
    for ($j = $i; $j -lt $a.Count; $j++) { $parts += '..' }
    for ($j = $i; $j -lt $b.Count; $j++) { $parts += $b[$j] }
    if ($parts.Count -eq 0) { return '.' }
    return ($parts -join '/')
}

# Whether this runs elevated (as an administrator, after UAC).
function Test-Elevated {
    if ((Test-TestHooks) -and $env:KILNMARK_TEST_ELEVATED) { return ($env:KILNMARK_TEST_ELEVATED -ceq 'yes') }
    $p = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    return $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# --- engine and compose ------------------------------------------------------------------------

# The engine's program by its full path: KILNMARK_ENGINE (a path, or a program's name), else podman
# or docker as PATH finds them. A program only, never a script of that name, and run by this path,
# so what the hourly run's checks passed (Get-EngineProblem) is what runs. Empty when there is none.
function Get-EnginePath {
    $e = $env:KILNMARK_ENGINE
    if ($e) {
        if ([IO.Path]::IsPathRooted($e)) { return $e }
        $c = Get-Command $e -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($c) { return $c.Source }
        return ''
    }
    foreach ($n in @('podman', 'docker')) {
        $c = Get-Command $n -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($c) { return $c.Source }
    }
    return ''
}
# The programs a run starts, looked up once a run, so what the hourly run's checks passed
# (Get-EngineProblem) is what runs: the engine by its full path (Get-EnginePath), and the compose
# tool. With Podman, PODMAN_COMPOSE_PROVIDER if set, else docker-compose, else podman-compose as PATH
# finds them, each a program by its full path, which PODMAN_COMPOSE_PROVIDER is then set to, so
# Podman launches that one and looks for no other. With Docker, every folder it looks in for its
# compose plugin (DOCKER_CONFIG's or yours, ProgramData's, Program Files' and Docker Desktop's).
$script:Tools = $null
function Get-Tools {
    if ($script:Tools) { return $script:Tools }
    $e = Get-EnginePath
    $name = ''; if ($e) { $name = [IO.Path]::GetFileNameWithoutExtension($e) }
    $compose = ''; $plugins = @(); $unread = ''
    if ($name -eq 'podman') {
        $want = $env:PODMAN_COMPOSE_PROVIDER
        if ($want) {
            if ([IO.Path]::IsPathRooted($want)) { $compose = $want }
            else { $c = Get-Command $want -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1; if ($c) { $compose = $c.Source } }
        } else {
            foreach ($n in @('docker-compose', 'podman-compose')) {
                $c = Get-Command $n -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
                if ($c) { $compose = $c.Source; break }
            }
        }
        if ($compose) { $env:PODMAN_COMPOSE_PROVIDER = $compose } else { Remove-Item Env:PODMAN_COMPOSE_PROVIDER -ErrorAction SilentlyContinue }
    } elseif ($name -eq 'docker') {
        $config = $env:DOCKER_CONFIG; if (-not $config) { $config = Join-Path $env:USERPROFILE '.docker' }
        $plugins = @((Join-Path $config 'cli-plugins'), (Join-Path $env:ProgramData 'Docker\cli-plugins'),
            (Join-Path $env:ProgramFiles 'Docker\cli-plugins'), (Join-Path $env:ProgramFiles 'Docker\Docker\resources\cli-plugins'))
        # and the folders config.json adds (cliPluginsExtraDirs); one that cannot be read is refused
        $json = Join-Path $config 'config.json'
        if (Test-Path -LiteralPath $json -PathType Leaf) {
            try { foreach ($x in @((Get-Content -LiteralPath $json -Raw | ConvertFrom-Json).cliPluginsExtraDirs)) { if ($x) { $plugins += [string]$x } } }
            catch { $unread = $json }
        }
    }
    $script:Tools = @{ Engine = $e; Compose = $compose; PluginDirs = $plugins; Unread = $unread }
    return $script:Tools
}
$script:EngineName = ''
function Select-Engine {
    if ($script:Engine) { return }
    $script:Engine = (Get-Tools).Engine
    if (-not $script:Engine) {
        if ($env:KILNMARK_ENGINE) { Fail 'KILNMARK_ENGINE names no program on this machine: give the full path of podman.exe or docker.exe, or unset it.' }
        Fail 'Neither Podman nor Docker is installed. Install Podman Desktop or Docker Desktop first (INSTALL.md, "What to install first").'
    }
    # its name, for the commands this script prints
    $script:EngineName = [IO.Path]::GetFileNameWithoutExtension($script:Engine)
    if ($script:EngineName -eq 'podman') {
        & $script:Engine info *> $null
        if ($LASTEXITCODE -ne 0) {
            Write-Host 'Starting the Podman machine ...'
            & $script:Engine machine start | Out-Host
            & $script:Engine info *> $null
            if ($LASTEXITCODE -ne 0) { Fail 'The Podman machine could not start. If Podman has none yet, make one with: podman machine init' }
        }
    }
    $lead = @(Get-ComposeLead)
    & $script:Engine @lead version *> $null
    if ($LASTEXITCODE -ne 0) { Fail ("'" + $script:EngineName + " compose' does not work: Podman needs a compose tool (winget install Docker.DockerCompose). Then run this again.") }
}

# A setting from config\.env as compose reads it (an export in front too); empty when it is not set.
function Get-Setting([string]$Name) {
    if (-not $script:Data) { return '' }
    $file = Join-Path $script:Data 'config\.env'
    if (-not (Test-Path -LiteralPath $file)) { return '' }
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
if (-not $Project) { $Project = Get-Setting 'KILNMARK_PROJECT' }
if (-not $Project) { $Project = 'kilnmark' }

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

# How a message names this script so it can be pasted into PowerShell: & and the path in quotes
# (C:\Program Files has a space). In Command Prompt, leave out the "& ".
function Get-SelfCall([string]$Folder) { return ('& "' + (Join-Path $Folder 'kilnmark.cmd') + '"') }

# The deployment mode Kilnmark recorded in its own database at its first start (ADR 0051), read with
# the database's own tools: what decides, not config\.env alone. Empty when nothing is recorded, or
# the database does not answer.
function Get-RecordedMode { return (Get-RecordedSetting 'deployment_mode') }
# A setting Kilnmark recorded in its own database (deployment_mode, or install_id, the install's own
# id, made at its first start); empty when there is none, or the database does not answer.
function Get-RecordedSetting([string]$Key) {
    $base = Get-ComposeArgs
    # Asks first whether the table is there: on a fresh database, before Kilnmark's first start
    # makes it, the answer is then empty rather than an error, which Windows PowerShell's transcript
    # (the install log) keeps even when it is sent to $null.
    # Never this script's standard input, which a person's lines may be waiting on.
    $have = ($null | & $script:Engine @base exec -T postgres psql -U kilnmark -d kilnmark -X -q -At -c "SELECT to_regclass('kilnmark.server_setting') IS NOT NULL" 2> $null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $have -ne 't') { return '' }
    $out = ($null | & $script:Engine @base exec -T postgres psql -U kilnmark -d kilnmark -X -q -At -c ("SELECT value FROM kilnmark.server_setting WHERE key = '" + $Key + "'") 2> $null | Out-String).Trim()
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
    if (-not $recorded) { return ("Kilnmark's database does not answer, or has no deployment mode recorded, so nobody was made or changed. Start Kilnmark with " + (Get-SelfCall $script:Root) + ' up, then try again.') }
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
    $base = Join-Path $script:Data 'config\run'
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
    # relative to compose.yaml, on the same drive (some compose tools split a drive letter's colon)
    $rel = Get-RelativePath (Get-CurrentDir) $dir
    if (-not $rel) { Remove-RunSecrets; Fail 'The data folder is on another drive than the program folder, which compose cannot take: install Kilnmark afresh with both on one drive.' }
    $env:KILNMARK_RUN_DIR = $rel
}
function Remove-RunSecrets {
    if ($script:RunSecrets) { Remove-Item $script:RunSecrets -Recurse -Force -ErrorAction SilentlyContinue }
    $script:RunSecrets = $null
    Remove-Item Env:KILNMARK_RUN_DIR -ErrorAction SilentlyContinue
}

# --- the hourly expiry of unused temporary passwords (ADR 0051) ---------------------------------

# With the bundled Keycloak, people expire runs every hour from a scheduled task of the operator's,
# while they are signed in to Windows (the Podman machine or Docker Desktop runs only then too), so
# a temporary password left unused is disabled at most an hour after its window, whether or not
# Kilnmark starts again. up sets it up, and removes it without the bundled Keycloak; uninstall
# removes it. KILNMARK_SCHTASKS is for the tests only, and counts only with their marker, which no
# release sets.
function Test-TestHooks { return ($env:KILNMARK_TEST_HOOKS -ceq 'only-in-kilnmark-tests') }
function Get-ExpiryTaskName { return ('Kilnmark ' + $Project + ' people expire') }
function Invoke-Schtasks {
    $exe = 'schtasks.exe'; if ((Test-TestHooks) -and $env:KILNMARK_SCHTASKS) { $exe = $env:KILNMARK_SCHTASKS }
    & $exe @args *> $null
    return ($LASTEXITCODE -eq 0)
}
function Test-ExpiryTask { return (Invoke-Schtasks /Query /TN (Get-ExpiryTaskName)) }
# Rights that change a file or folder, or what is in a folder: writing, appending, attributes,
# adding or deleting what is in it, deleting, permissions, ownership, and the generic write and all.
$TreeWrites = [long](0x2 -bor 0x4 -bor 0x10 -bor 0x40 -bor 0x100 -bor 0x10000 -bor 0x40000 -bor 0x80000 -bor 0x40000000 -bor 0x10000000)
# Above a root, what lets someone move a folder (or what is in it) away, or change who may:
# deleting what is in it, deleting it, permissions, ownership, and all.
$AboveWrites = [long](0x40 -bor 0x10000 -bor 0x40000 -bor 0x80000 -bor 0x10000000)
$TrustedInstaller = 'S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464'
# Who else counts as you here: the operator, for the step UAC elevates, which may run as another
# administrator (Invoke-InstallElevated, Invoke-PlaceVersion).
$script:AlsoTrusted = @()
# Who other than you, SYSTEM and the Administrators (above a root, TrustedInstaller too, which
# holds C:\Program Files) holds any of $Mask's rights on $Path: their names, or nothing. An app
# container's or a capability's SID (S-1-15-2-, S-1-15-3-) never counts: it only narrows what a
# process of someone who holds the right anyway may do. A conditional entry for anyone else
# counts, whatever it grants: what it grants hangs on more than who they are.
function Get-OthersWriting([string]$Path, [long]$Mask = $TreeWrites, [switch]$Above) {
    $me = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $ok = @($me, 'S-1-5-18', 'S-1-5-32-544', 'S-1-3-0') + $script:AlsoTrusted; if ($Above) { $ok += $TrustedInstaller }
    $out = @()
    $acl = Get-Acl -LiteralPath $Path
    foreach ($ace in $acl.Access) {
        if ($ace.AccessControlType -ne 'Allow') { continue }
        if (($ace.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly) -ne 0) { continue }
        if (([long]$ace.FileSystemRights -band $Mask) -eq 0) { continue }
        $sid = $null
        try { $sid = $ace.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value } catch { $sid = '' + $ace.IdentityReference }
        if ($sid -like 'S-1-15-2-*' -or $sid -like 'S-1-15-3-*') { continue }
        if ($ok -notcontains $sid) { $out += ('' + $ace.IdentityReference) }
    }
    $alias = @{ 'SY' = 'S-1-5-18'; 'BA' = 'S-1-5-32-544'; 'CO' = 'S-1-3-0' }
    foreach ($m in [regex]::Matches($acl.Sddl, '\((XA|ZA);[^;]*;[^;]*;[^;]*;[^;]*;([^;\)]*)')) {
        $sid = $m.Groups[2].Value
        if ($alias.ContainsKey($sid)) { $sid = $alias[$sid] }
        if ($ok -notcontains $sid) { $out += ('a conditional entry for ' + $sid) }
    }
    # unrolled here, gathered again with @() by the caller
    return $out
}

# Whether $Path's owner is you, SYSTEM or the Administrators (above a root, TrustedInstaller too,
# which owns C:\ and C:\Program Files): an owner may rewrite its permissions whenever they like.
function Test-OwnerOk([string]$Path, [switch]$Above) {
    $me = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $sid = ''
    try { $sid = (Get-Acl -LiteralPath $Path).GetOwner([Security.Principal.SecurityIdentifier]).Value } catch { $sid = '' }
    $ok = @($me, 'S-1-5-18', 'S-1-5-32-544') + $script:AlsoTrusted; if ($Above) { $ok += $TrustedInstaller }
    return ($ok -contains $sid)
}

# What a link, a junction or a OneDrive file is, said after its path. OneDrive keeps its files and
# folders as reparse points that Windows fetches from the cloud when they are read (the
# recall-on-open, recall-on-data-access, pinned and unpinned attributes), so a release unpacked
# there is refused for being one: said so, with where to unpack it instead.
function Get-ReparseWhy($Item) {
    $cloud = (([long]$Item.Attributes -band (0x40000 -bor 0x80000 -bor 0x100000 -bor 0x400000)) -ne 0)
    foreach ($o in @($env:OneDrive, $env:OneDriveCommercial, $env:OneDriveConsumer)) {
        if ($o -and ($Item.FullName + '\').StartsWith($o.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { $cloud = $true }
    }
    if ($cloud) { return ' is a OneDrive file or folder, which OneDrive keeps in the cloud and Windows fetches when it is read: unpack the release outside OneDrive, in your own Downloads folder (if OneDrive does not hold it) or another folder only you may change' }
    return ' is a link or a junction, which leads wherever someone made it lead'
}

# Why someone other than you, SYSTEM or the Administrators may change $Path, or nothing: it must be
# there, no link or junction, owned by one of them, and writable by nobody else.
function Get-ItemProblem([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return ($Path + ' is not there') }
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return ($Path + (Get-ReparseWhy $item)) }
    if (-not (Test-OwnerOk $Path)) { return ($Path + ' is owned by someone other than you, SYSTEM or the Administrators, who may change it whenever they like') }
    $others = @(Get-OthersWriting $Path)
    if ($others.Count -gt 0) { return ($Path + ' may be changed by ' + (($others | Select-Object -Unique) -join ', ')) }
    return ''
}

# Why a folder above $Top (a root) may let someone else move it away and put another in its place,
# or nothing: every folder up to the drive's must be no link or junction, owned by you, SYSTEM, the
# Administrators or TrustedInstaller, with nobody else able to delete what is in it, delete it, or
# change who may.
function Get-AboveProblem([string]$Top) {
    $dir = Split-Path $Top -Parent
    while ($dir) {
        $why = ''
        $item = Get-Item -LiteralPath $dir -Force -ErrorAction SilentlyContinue
        if (-not $item) { $why = 'could not be read' }
        elseif (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { $why = (Get-ReparseWhy $item).TrimStart(' ') }
        elseif (-not (Test-OwnerOk $dir -Above)) { $why = 'is owned by someone other than you, SYSTEM, the Administrators or TrustedInstaller' }
        else {
            $others = @(Get-OthersWriting $dir $AboveWrites -Above)
            if ($others.Count -gt 0) { $why = 'lets ' + (($others | Select-Object -Unique) -join ', ') + ' delete what is in it or change who may' }
        }
        if ($why) { return ($dir + ', a folder above ' + $Top + ', ' + $why + ', so they could move it away and put another in its place') }
        $parent = Split-Path $dir -Parent
        if (-not $parent -or $parent -eq $dir) { break }
        $dir = $parent
    }
    return ''
}

# Why someone other than you (the operator), SYSTEM, the Administrators or TrustedInstaller may have
# changed a release, or nothing (ADR 0051, decision 17): install and upgrade run what is in it as an
# administrator. $Path is a release folder or an archive. Every folder above it must pass
# Get-AboveProblem, and it, and every file and folder in it, Get-ItemProblem: never a link or a
# junction, which is not followed.
function Get-ReleaseProblem([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $why = Get-AboveProblem $full
    if ($why) { return $why }
    $todo = New-Object System.Collections.Stack
    $todo.Push($full)
    while ($todo.Count -gt 0) {
        $p = $todo.Pop()
        $why = Get-ItemProblem $p
        if ($why) { return $why }
        if (Test-Path -LiteralPath $p -PathType Container) {
            try { $kids = @(Get-ChildItem -LiteralPath $p -Force -ErrorAction Stop) } catch { return ($p + ' could not be read') }
            foreach ($c in $kids) { $todo.Push($c.FullName) }
        }
    }
    return ''
}
# Refuses a release (folder or archive) someone else may have changed, before UAC is asked: with the
# way to fix it.
function Assert-ReleaseItsOwn([string]$Path, [string]$What) {
    Write-Host ('Checking that nobody else may change ' + $Path + ' ...')
    $why = Get-ReleaseProblem $Path
    if (-not $why) { return }
    # Only a fresh copy helps: changing the permissions now would not take back what someone may
    # have changed already, nor a handle to a file they hold open to write.
    $fix = ' Download the release again into your own Downloads folder (or another folder only you and the administrators may change), check it, unpack it there, and run ' + $What + ' from there.'
    Fail ($What + ' was refused, before asking for an administrator: ' + $why + '. Windows would run what is in it as an administrator, so nobody else may change it.' + $fix)
}

# Why someone other than you, SYSTEM, the Administrators or TrustedInstaller may change a program an
# hourly run starts, or nothing (ADR 0051, decision 15, as the Linux edition's tools_problem): the
# program itself (where it is a link, the link's own folders too, then what it leads to), owned by
# one of them and writable by nobody else; the folder it is in, which nobody else may add to (a
# library beside it would be loaded); and every folder above that, as Get-AboveProblem says.
function Get-ToolProblem([string]$Path) {
    $p = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $p -PathType Leaf)) { return ($p + ', a program an hourly run starts, is not there') }
    $item = Get-Item -LiteralPath $p -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        $why = Get-AboveProblem $p
        if ($why) { return $why }
        $to = @($item.Target) | Select-Object -First 1
        if (-not $to) { return ($p + ' is a link whose target Windows does not say') }
        if (-not [IO.Path]::IsPathRooted($to)) { $to = Join-Path (Split-Path $p -Parent) $to }
        $p = [IO.Path]::GetFullPath($to)
        if (-not (Test-Path -LiteralPath $p -PathType Leaf)) { return ($p + ', a program an hourly run starts, is not there') }
        if (((Get-Item -LiteralPath $p -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return ($p + ' is a link to a link') }
    }
    $dir = Split-Path $p -Parent
    foreach ($one in @(@($p, ', a program an hourly run starts,'), @($dir, ', the folder of a program an hourly run starts,'))) {
        if (-not (Test-OwnerOk $one[0] -Above)) { return ($one[0] + $one[1] + ' is owned by someone other than you, SYSTEM, the Administrators or TrustedInstaller') }
        $others = @(Get-OthersWriting $one[0] $TreeWrites -Above)
        if ($others.Count -gt 0) { return ($one[0] + $one[1] + ' may be changed by ' + (($others | Select-Object -Unique) -join ', ')) }
    }
    return (Get-AboveProblem $dir)
}
# The programs an hourly run starts (Get-Tools, the same paths Select-Engine then runs): why one may
# be changed by someone else, or nothing. The engine and Podman's compose tool must pass
# Get-ToolProblem. Each folder Docker looks in for its compose plugin, there or not, must let nobody
# else put one there: one that is there must take nothing from anyone else (and its plugin pass
# Get-ToolProblem); one that is not there, nobody else may make (the nearest folder above that is
# there must let nobody else add to it: C:\ProgramData lets Users make folders), since Docker would
# run a plugin put there.
function Get-EngineProblem {
    $t = Get-Tools
    if (-not $t.Engine) { return '' }
    $why = Get-ToolProblem $t.Engine
    if ($why) { return $why }
    if ($t.Compose) { $why = Get-ToolProblem $t.Compose; if ($why) { return $why } }
    elseif ([IO.Path]::GetFileNameWithoutExtension($t.Engine) -eq 'podman') { return ('the compose tool Podman would launch (PODMAN_COMPOSE_PROVIDER, else docker-compose or podman-compose on PATH) is not a program PATH finds, so it cannot be checked: install one (winget install Docker.DockerCompose), or set PODMAN_COMPOSE_PROVIDER to its full path') }
    if ($t.Unread) { return ($t.Unread + ', which may name more folders Docker runs its compose plugin from, could not be read') }
    foreach ($d in $t.PluginDirs) {
        $f = Join-Path $d 'docker-compose.exe'
        if (Test-Path -LiteralPath $f) { $why = Get-ToolProblem $f; if ($why) { return $why }; continue }
        $at = $d
        while ($at -and -not (Test-Path -LiteralPath $at)) { $at = Split-Path $at -Parent }
        if (-not $at) { continue }
        $item = Get-Item -LiteralPath $at -Force
        $others = @()
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0 -and (Test-OwnerOk $at -Above)) { $others = @(Get-OthersWriting $at $TreeWrites -Above) } else { $others = @('someone else') }
        if ($others.Count -gt 0) {
            if ($at -ceq $d) { return ($d + ', a folder Docker runs its compose plugin from, may be changed by ' + (($others | Select-Object -Unique) -join ', ')) }
            return ($d + ', a folder Docker runs its compose plugin from, is not there, and ' + (($others | Select-Object -Unique) -join ', ') + ' may make it in ' + $at + ' and put one there: make it as an administrator, nobody else able to add to it (an elevated prompt: mkdir "' + $d + '", then icacls "' + $d + '" /inheritance:r /grant:r *S-1-5-32-544:(OI)(CI)F *S-1-5-18:(OI)(CI)F *S-1-5-32-545:(OI)(CI)RX)')
        }
    }
    return ''
}

# Why someone other than you, SYSTEM or the Administrators may change what an hourly run uses, or
# nothing (ADR 0051, decisions 15 and 17): the task runs kilnmark.ps1 from the program folder as
# you, and that run reads current.txt, data-folder.txt, the current version's compose.yaml and
# keycloak-setup's script and kilnmark-env.sh there, and config\.env in the data folder, and writes to its logs\. Each,
# and every folder from its root down to it, must pass Get-ItemProblem, and current.txt must name a
# folder directly in versions\. Above each root, every folder must pass Get-AboveProblem. And the
# programs it starts, the engine and the compose tool Podman launches, Get-EngineProblem.
function Get-TreeProblem {
    $top = [IO.Path]::GetFullPath($script:Root).TrimEnd('\')
    if (-not $script:Data) { return ((Join-Path $top 'data-folder.txt') + " does not name Kilnmark's data folder") }
    $data = [IO.Path]::GetFullPath($script:Data).TrimEnd('\')
    $marker = Join-Path $top 'current.txt'
    $v = ''
    if (Test-Path -LiteralPath $marker -PathType Leaf) { $v = ([IO.File]::ReadAllText($marker)).Trim() }
    if ($v -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._+-]*$') { return ($marker + ' does not name a version in ' + (Join-Path $top 'versions')) }
    $items = @(@($top, 'kilnmark.ps1'), @($top, 'current.txt'), @($top, 'data-folder.txt'), @($top, ('versions\' + $v + '\compose.yaml')),
        @($top, ('versions\' + $v + '\containers\keycloak\setup.sh')), @($top, ('versions\' + $v + '\containers\kilnmark-env.sh')), @($data, 'config\.env'))
    if (Test-Path -LiteralPath (Join-Path $data 'logs')) { $items += , @($data, 'logs') }
    $seen = @{}
    foreach ($item in $items) {
        $p = $item[0]
        $parts = @($item[1] -split '\\')
        for ($i = -1; $i -lt $parts.Count; $i++) {
            if ($i -ge 0) { $p = Join-Path $p $parts[$i] }
            if ($seen.ContainsKey($p)) { continue }
            $seen[$p] = $true
            $why = Get-ItemProblem $p
            if ($why) { return $why }
        }
    }
    $why = Get-AboveProblem $top
    if ($why) { return $why }
    $why = Get-AboveProblem $data
    if ($why) { return $why }
    # and the programs it starts: the engine, and the compose tool Podman launches
    return (Get-EngineProblem)
}

# people expire runs as you every hour: never what someone else may have changed (ADR 0051, decision
# 15). Checked before anything of the install is read or written, its log too; a refusal reads
# nothing more (never config\.env, nor the admin token in it). Task Scheduler's history (an event
# log) keeps the failed run, and a line goes to logs\ where that folder passes the same check.
function Assert-ExpireTree {
    if (-not $script:Root -or -not $script:Data) { Fail 'Kilnmark is not installed here. Install it with: kilnmark.cmd install, from the unpacked release folder' }
    $why = Get-TreeProblem
    if (-not $why) { return }
    $msg = 'people expire was refused: ' + $why + '. Make it so (kilnmark.cmd check says how), then run it again.'
    $logs = Join-Path $script:Data 'logs'
    if ((Test-Path -LiteralPath $logs) -and -not (Get-ItemProblem $logs)) {
        $file = Join-Path $logs ('people-expire-' + (Get-Date).ToUniversalTime().ToString('yyyyMMdd') + '.log')
        if (-not (Test-Path -LiteralPath $file) -or -not (Get-ItemProblem $file)) {
            try { [IO.File]::AppendAllText($file, ((Get-Date).ToUniversalTime().ToString('s') + 'Z ' + $msg + "`r`n")) } catch { }
        }
    }
    Fail $msg
}

# The task runs kilnmark.ps1 from the program folder as you, every hour: it is not set up while
# anyone else owns or may change what a run uses, since they would run what they wrote as you.
function Set-ExpiryTask {
    $why = Get-TreeProblem
    if ($why) {
        Remove-ExpiryTask
        Write-Host ('Warning: the hourly expiry of unused temporary passwords was not set up: ' + $why + ', and the task would run it as you. Make it so (kilnmark.cmd check says how), then run up again. Each start expires them too.') -ForegroundColor Yellow
        return
    }
    $had = Test-ExpiryTask
    # the script's path quoted, for a folder with a space in it (C:\Program Files); Windows
    # PowerShell (and PowerShell before 7.3) hands a native program a quote inside an argument as
    # it is, which would end it there
    $q = '"'
    if ($PSVersionTable.PSVersion.Major -lt 7 -or -not (Get-Variable PSNativeCommandArgumentPassing -ErrorAction SilentlyContinue) -or $PSNativeCommandArgumentPassing -eq 'Legacy') { $q = '\"' }
    # Windows PowerShell by its full path, never whichever powershell.exe the task's PATH finds first
    $shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $run = $q + $shell + $q + ' -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File ' + $q + (Join-Path $script:Root 'kilnmark.ps1') + $q + ' people expire -Project ' + $Project
    if (Invoke-Schtasks /Create /SC HOURLY /TN (Get-ExpiryTaskName) /TR $run /F) {
        if (-not $had) { Write-Host ('Set up the hourly expiry of unused temporary passwords: the scheduled task "' + (Get-ExpiryTaskName) + '" (it runs while you are signed in to Windows).') }
    } else {
        Write-Host ('Warning: the hourly expiry of unused temporary passwords could not be set up (Task Scheduler). Run ' + (Get-SelfCall $script:Root) + ' people expire every hour yourself; each start expires them too.') -ForegroundColor Yellow
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
    $env = Join-Path $script:Data 'config\.env'
    if (-not (Test-Path -LiteralPath $env)) { Fail ('There is no ' + $env + '. Install Kilnmark with: kilnmark.cmd install, from the unpacked release folder') }
    $dir = Get-CurrentDir
    Write-ServiceSecrets $dir
    $list = @(Get-ComposeLead) + @('-p', $Project, '--env-file', $script:ComposeEnv, '-f', (Join-Path $dir 'compose.yaml'))
    # HTTPS's certificate and key, in the data folder: relative to compose.yaml, on the same drive
    $certs = Get-RelativePath $dir (Join-Path $script:Data 'certs\https')
    if ($certs) { $env:KILNMARK_CERTS_DIR = $certs }
    # Some compose tools read COMPOSE_PROFILES from the env file and some do not: name each one.
    foreach ($p in ((Get-Setting 'COMPOSE_PROFILES') -split ',')) {
        if ($p.Trim()) { $list += @('--profile', $p.Trim()) }
    }
    # The Starter Pack's database is made only on a Starter Pack install (ADR 0051).
    if (Test-SamplesAllowed) { $list += @('--profile', 'starter-pack') }
    return $list
}

# The words that start every compose command: compose, and with Podman and podman-compose as its
# compose tool, --podman-path and the engine's full path, which Podman passes on as it is.
# podman-compose otherwise runs podman by its bare name, which Windows looks for in python.exe's
# folder, the current folder and PATH, past the path the hourly run's checks passed.
function Get-ComposeLead {
    $t = Get-Tools
    if ($t.Compose -and [IO.Path]::GetFileNameWithoutExtension($t.Engine) -eq 'podman' -and [IO.Path]::GetFileNameWithoutExtension($t.Compose) -like 'podman-compose*') {
        return @('compose', '--podman-path', $t.Engine)
    }
    return @('compose')
}

# A value Set-Line writes as it is and Get-Setting reads back the same: no space, quote mark, #, $,
# backtick, backslash or control character. For the client secret, which is typed or piped in.
function Test-PlainValue([string]$Value) { return ($Value -and $Value -notmatch '[\s\p{Cc}"''#$`\\]') }

# The secrets of the services that keep running (ADR 0051, decision 13): never a setting of their
# containers, which compose tools put on podman's command line (podman-compose puts an env_file's
# lines there too). A file for each service, NAME=value a line, as Get-Setting reads config\.env,
# which compose mounts read-only into that service alone (/run/kilnmark/env), where
# containers/kilnmark-env.sh reads it into that program's environment. In config\run\services,
# yours alone (as each run's folder), written afresh from config\.env before every compose command.
# They hold nothing config\.env does not. Never written elevated: the folder would then be the
# administrator's alone. A command that stops or looks (down, status, logs, check, uninstall)
# never fails over them: what it does needs no secret. Compose itself reads config\.env without
# its secrets (compose.env beside them, $script:ComposeEnv): compose.yaml names none, and a secret
# compose would read as more (a $) can then never stop compose, down and stop included. A secret
# holding $ or a backtick is refused by its name, as set_line refuses one.
$script:ServiceSecretsFor = $null
$script:ComposeEnv = $null
function Get-ServiceSecretLine([string]$Name, [string]$Setting) {
    $v = Get-Setting $Setting
    if ($v) { return ($Name + '=' + $v) }
}
function Write-ServiceSecrets([string]$VersionDir) {
    $dir = Join-Path $script:Data 'config\run\services'
    # relative to compose.yaml, on the same drive (some compose tools split a drive letter's colon)
    $rel = Get-RelativePath $VersionDir $dir
    if (-not $rel) { Fail 'The data folder is on another drive than the program folder, which compose cannot take: install Kilnmark afresh with both on one drive.' }
    $env:KILNMARK_SECRETS_DIR = $rel
    $envFile = Join-Path $script:Data 'config\.env'
    $composeEnv = Join-Path $dir 'compose.env'
    # elevated (check), the files you wrote, never written: compose still finds them
    if (Test-Elevated) {
        if (Test-Path -LiteralPath $composeEnv -PathType Leaf) { $script:ComposeEnv = $composeEnv } else { $script:ComposeEnv = $envFile }
        return
    }
    $text = [IO.File]::ReadAllText($envFile)
    if ($script:ServiceSecretsFor -cne $text -or -not (Test-Path -LiteralPath $dir)) {
        try {
            $base = Join-Path $script:Data 'config\run'
            if (-not (Test-Path -LiteralPath $base)) { New-Item -ItemType Directory $base -ErrorAction Stop | Out-Null }
            if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory $dir -ErrorAction Stop | Out-Null }
        } catch { Fail ('Could not make ' + $dir + ", yours alone, for the services' secrets. Nothing was started.") }
        if ((Get-Item -LiteralPath $dir -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { Fail ($dir + ' is a link or a junction: remove it, then run this again. Nothing was started.') }
        Protect-Folder $dir
        # config\.env as compose reads it: every line but a secret's (a name ending _PASSWORD,
        # _SECRET or _TOKEN)
        $kept = @(foreach ($l in [IO.File]::ReadAllLines($envFile)) {
            if ($l -match '^\s*(export\s+)?([A-Za-z0-9_]+)\s*=' -and $Matches[2] -match '(_PASSWORD|_SECRET|_TOKEN)$') { continue }
            $l
        })
        try { Write-UnixText $composeEnv $kept } catch { Fail ('Could not write ' + $composeEnv + '. Nothing was started.') }
        $script:ComposeEnv = $composeEnv
        $why = ''
        foreach ($s in @('KILNMARK_DB_PASSWORD', 'KILNMARK_ADMIN_TOKEN')) {
            if (-not $why -and -not (Get-Setting $s)) { $why = $s + ' is not set in ' + $envFile + ': Kilnmark has no database password or admin token without it. Put back the line install wrote. Nothing was started.' }
        }
        foreach ($s in @('KILNMARK_DB_PASSWORD', 'KILNMARK_ADMIN_TOKEN', 'KILNMARK_SAMPLES_DB_PASSWORD', 'KILNMARK_OIDC_CLIENT_SECRET', 'KILNMARK_KEYCLOAK_DB_PASSWORD', 'KILNMARK_KEYCLOAK_ADMIN_PASSWORD')) {
            if (-not $why -and (Get-Setting $s) -match '[$`]') { $why = $s + ' in config\.env holds $ or `, which compose tools would read as the start of another setting''s name: put back the value install made, or one without them. Nothing was started.' }
        }
        if ($why) {
            # what stops or looks needs no secret, and never fails over one
            if ($Command -cin @('down', 'stop', 'status', 'logs', 'check', 'uninstall', '_stop-for-uninstall')) { return }
            Fail $why
        }
        $files = @{
            'postgres.env' = @(Get-ServiceSecretLine 'POSTGRES_PASSWORD' 'KILNMARK_DB_PASSWORD')
            'kilnmark.env' = @((Get-ServiceSecretLine 'QUARKUS_DATASOURCE_PASSWORD' 'KILNMARK_DB_PASSWORD'), (Get-ServiceSecretLine 'KILNMARK_ADMIN_TOKEN' 'KILNMARK_ADMIN_TOKEN'),
                (Get-ServiceSecretLine 'KILNMARK_SAMPLES_DB_PASSWORD' 'KILNMARK_SAMPLES_DB_PASSWORD'), (Get-ServiceSecretLine 'QUARKUS_OIDC_CREDENTIALS_SECRET' 'KILNMARK_OIDC_CLIENT_SECRET'))
            'keycloak-db.env' = @(Get-ServiceSecretLine 'POSTGRES_PASSWORD' 'KILNMARK_KEYCLOAK_DB_PASSWORD')
            'keycloak.env' = @((Get-ServiceSecretLine 'KC_DB_PASSWORD' 'KILNMARK_KEYCLOAK_DB_PASSWORD'), (Get-ServiceSecretLine 'KC_BOOTSTRAP_ADMIN_PASSWORD' 'KILNMARK_KEYCLOAK_ADMIN_PASSWORD'))
        }
        try {
            foreach ($f in $files.Keys) {
                $lines = @($files[$f] | Where-Object { $_ })
                $enc = New-Object System.Text.UTF8Encoding($false)
                $body = ''; if ($lines.Count -gt 0) { $body = ($lines -join "`n") + "`n" }
                [IO.File]::WriteAllText((Join-Path $dir $f), $body, $enc)
            }
        } catch { Fail ("Could not write the services' secrets in " + $dir + '. Nothing was started.') }
        $script:ServiceSecretsFor = $text
    }
    $script:ComposeEnv = $composeEnv
}

# podman-compose 1.0.x prints each podman command it runs, with every setting of the container on
# it (-e NAME=value: passwords, the client secret), joined with spaces or as a Python list. Those
# lines are never shown or kept in logs\.
function ConvertTo-Said($Item) {
    if ($Item -is [Management.Automation.ErrorRecord]) { $s = '' + $Item.Exception.Message } else { $s = '' + $Item }
    if (-not $s.Trim() -or $s -ceq 'System.Management.Automation.RemoteException' -or $s -cmatch '^[0-9a-f]{12,64}$') { return }
    return $s
}
function Test-ComposeCommand([string]$Line) { return ($Line -match 'podman.*[\s''",\[](-e|--env)([\s=,''"]|$)') }

# Runs compose with the given arguments; its output reaches the screen and the log.
function Invoke-Compose {
    $base = Get-ComposeArgs
    & $script:Engine @base @args 2>&1 | ForEach-Object { ConvertTo-Said $_ } | Where-Object { -not (Test-ComposeCommand $_) } | Out-Host
    return ($LASTEXITCODE -eq 0)
}

# Runs compose without showing what it prints (container ids and names) unless it fails, when
# all of it is shown. For up and down, whose own lines say what happened.
function Invoke-ComposeQuietly {
    $base = Get-ComposeArgs
    $out = & $script:Engine @base @args 2>&1 | ForEach-Object { ConvertTo-Said $_ }
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
    if (-not $script:Data) { return }
    $dir = Join-Path $script:Data 'logs'
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

# When a copy of $File into $Dir failed: says so if the drive has no room for it, naming the folder
# and the space it needs.
function Assert-Room([string]$File, [string]$Dir) {
    $need = 0; $free = -1
    try { $need = (Get-Item -LiteralPath $File -Force).Length } catch { return }
    try { $free = (New-Object IO.DriveInfo ([IO.Path]::GetPathRoot([IO.Path]::GetFullPath($Dir)))).AvailableFreeSpace } catch { return }
    if ($free -ge 0 -and $free -lt $need) {
        Fail ('There is not room for a copy of ' + (Split-Path $File -Leaf) + ' in ' + $Dir + ' (your TEMP folder): it needs ' + [math]::Ceiling($need / 1MB) + ' MB, and ' + [math]::Floor($free / 1MB) + ' MB are free. Free that much on its drive, then run this again.')
    }
}

function Import-Images([string]$File) {
    if (-not (Test-Path $File)) { Fail ('There is no file ' + $File + '.') }
    # copied into a folder of yours alone first, with its signature: the copy is what is checked and
    # what is loaded, so nothing can be put in its place between the two
    $work = New-WorkFolder
    try {
        $copy = Join-Path $work (Split-Path $File -Leaf)
        try {
            Assert-Room $File $work
            Copy-Item -LiteralPath $File -Destination $copy -ErrorAction Stop
            if (Test-Path -LiteralPath ($File + '.sigstore.json')) { Copy-Item -LiteralPath ($File + '.sigstore.json') -Destination ($copy + '.sigstore.json') -ErrorAction Stop }
        } catch { Assert-Room $File $work; Fail ((Split-Path $File -Leaf) + ' could not be copied into a folder of yours to check it.') }
        Test-FileSignature $copy
        Select-Engine
        Write-Host ('Loading the images from ' + (Split-Path $File -Leaf) + ' ...')
        & $script:Engine load -i $copy | Out-Host
        if ($LASTEXITCODE -ne 0) { Fail 'The images could not be loaded.' }
    } finally { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
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
        if ($LASTEXITCODE -ne 0) { Fail ($i + " does not carry Kilnmark's signature. Do not run it; remove it with: " + $script:EngineName + ' rmi ' + $i) }
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

# What check found: each problem and warning with the command that fixes it, and what passed.
function Add-Problem([string]$What, [string]$Fix = '') { [void]$script:Problems.Add($What); [void]$script:Fixes.Add($Fix) }
function Add-Warning([string]$What, [string]$Fix = '') { [void]$script:Warnings.Add($What); [void]$script:WarningFixes.Add($Fix) }
function Add-Passed([string]$What) { [void]$script:Passed.Add($What) }

# A port as the install's answers or config\.env set it, else as the environment does, else the default.
function Get-Port([string]$Name, [int]$Default, [string]$Key) {
    $v = ''
    if ($Key -and $script:Answers.ContainsKey($Key)) { $v = $script:Answers[$Key] }
    if (-not $v -and $script:Data -and (Test-Path -LiteralPath (Join-Path $script:Data 'config\.env'))) { $v = Get-Setting $Name }
    if (-not $v) { $v = [Environment]::GetEnvironmentVariable($Name) }
    $n = 0
    if ($v -and [int]::TryParse($v, [ref]$n)) { return $n }
    return $Default
}

function Test-PortBusy([int]$Port) {
    if ($Port -lt 1 -or $Port -gt 65535) { return $false }
    $c = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    return [bool]$c
}
# A free port near $Port, for a fix to name.
function Get-FreePort([int]$Port) {
    $p = $Port + 1
    for ($i = 0; $i -lt 200; $i++) {
        if ($p -gt 65535) { $p = 20000 }
        if (-not (Test-PortBusy $p)) { return $p }
        $p++
    }
    return $Port
}
# The command that moves Kilnmark off a busy port: configure on an install, else the answer.
function Get-PortFix([int]$Port) {
    $key = 'port'
    if ($Port -eq (Get-Port 'KILNMARK_HTTPS_PORT' 8443 'https_port')) { $key = 'https_port' }
    if ($Port -eq (Get-Port 'KILNMARK_DB_PORT' 5442 'db_port')) { $key = 'db_port' }
    if ($Port -eq (Get-Port 'KILNMARK_KEYCLOAK_PORT' 8180 'keycloak_port')) { $key = 'keycloak_port' }
    $alt = Get-FreePort $Port
    if ($script:Data -and (Test-Path -LiteralPath (Join-Path $script:Data 'config\install.conf'))) { return ((Get-SelfCall $script:Root) + ' configure ' + $key + '=' + $alt) }
    return ('answer ' + $alt + ' for ' + $key + ' (in an answers file: ' + $key + '=' + $alt + ')')
}

# WSL's version, as wsl.exe reports it (empty when it says nothing). KILNMARK_WSL is the tests'.
function Get-WslVersion {
    $w = 'wsl.exe'; if ((Test-TestHooks) -and $env:KILNMARK_WSL) { $w = $env:KILNMARK_WSL }
    $text = ''
    try { $text = ((& $w --version 2> $null | Out-String) -replace "`0", '') } catch { $text = '' }
    if ($text -match 'WSL[^0-9\r\n]*([0-9][0-9.]*)') { return $Matches[1] }
    return ''
}
$WslFix = 'Invoke-WebRequest https://github.com/microsoft/WSL/releases/download/2.7.11/wsl.2.7.11.0.x64.msi -OutFile $env:TEMP\wsl.msi; Start-Process msiexec.exe -Verb RunAs -Wait -ArgumentList "/i $env:TEMP\wsl.msi"; wsl --shutdown'

# The prerequisites: the engine and its compose, its version, its machine, cgroups, memory, WSL,
# disk, the tools, and (when nothing runs) free ports. -Running: Kilnmark answers; -Keycloak: it
# has the bundled Keycloak (5 GB of memory, not 4). Never starts the Podman machine.
function Test-Prerequisites([bool]$Running, [bool]$Keycloak = $false) {
    # by the full path every other command runs it by (a program, never a script of its name)
    $engine = (Get-Tools).Engine
    if (-not $engine) {
        Add-Problem 'Neither Podman nor Docker is installed.' 'winget install RedHat.Podman-Desktop (then make its machine: podman machine init --rootful)'
    } else {
        $docker = ([IO.Path]::GetFileNameWithoutExtension($engine) -like '*docker*')
        & $engine @(Get-ComposeLead) version *> $null
        if ($LASTEXITCODE -eq 0) { Add-Passed ([IO.Path]::GetFileNameWithoutExtension($engine) + ' compose works') }
        elseif ($docker) { Add-Problem ("'" + [IO.Path]::GetFileNameWithoutExtension($engine) + " compose' does not work.") 'winget install Docker.DockerCompose' }
        else { Add-Problem ("'" + [IO.Path]::GetFileNameWithoutExtension($engine) + " compose' does not work: Podman needs a compose tool.") 'winget install Docker.DockerCompose' }
        $info = $true
        & $engine info *> $null
        if ($LASTEXITCODE -ne 0) {
            $info = $false
            if ($docker) { Add-Problem 'Docker Desktop is not running.' 'start Docker Desktop' }
            else { Add-Problem 'The Podman machine is not running (or there is none).' 'podman machine start (none yet: podman machine init --rootful, then podman machine start)' }
        }
        if (-not $docker) {
            $v = (& $engine version --format '{{.Client.Version}}' 2> $null | Select-Object -First 1 | Out-String).Trim()
            $major = 0; [void][int]::TryParse(($v -split '\.')[0], [ref]$major)
            if ($major -ge 5) { Add-Passed ('Podman ' + $v) }
            else { $shown = $v; if (-not $shown -or $major -eq 0) { $shown = 'of an unknown version' }; Add-Problem ('Podman ' + $shown + ' is too old: Kilnmark needs Podman 5 or later.') 'winget upgrade RedHat.Podman-Desktop' }
            if ($info) {
                $cg = (& $engine info --format '{{.Host.CgroupsVersion}}' 2> $null | Select-Object -First 1 | Out-String).Trim()
                $ctl = (& $engine info --format '{{.Host.CgroupControllers}}' 2> $null | Select-Object -First 1 | Out-String).Trim()
                if ($cg -eq 'v1') { Add-Problem 'The Podman machine uses cgroups v1: rootless Podman needs cgroups v2.' 'add kernelCommandLine = cgroup_no_v1=all under [wsl2] in %UserProfile%\.wslconfig, then wsl --shutdown' }
                elseif ($cg -eq 'v2' -and (' ' + ($ctl -replace '[\[\]]', ' ') + ' ') -notmatch ' pids ') { Add-Problem "Podman cannot use cgroups' pids controller, so no container starts (WSL 3.0.x breaks crun's pids controller: Podman needs WSL 2.7.11)." $WslFix }
                elseif ($cg -eq 'v2') { Add-Passed 'cgroups v2, with the pids controller' }
            }
            $wv = Get-WslVersion
            if ($wv -match '^2\.7\.11(\.|$)') { Add-Passed ('WSL ' + $wv) }
            elseif (-not $wv) { Add-Warning "Cannot tell WSL's version: Podman needs WSL 2.7.11, and WSL 3.0.x breaks crun's pids controller." 'wsl --version' }
            elseif ($wv -match '^(3\.|2\.9\.)') { Add-Problem ('WSL ' + $wv + " breaks crun's pids controller, so no Podman container starts: Podman needs WSL 2.7.11.") $WslFix }
            else { Add-Warning ('WSL ' + $wv + " is not the one Podman is known to work with here: WSL 2.7.11 (3.0.x breaks crun's pids controller).") $WslFix }
        }
        if ($info) {
            $mem = (& $engine info --format $(if ($docker) { '{{.MemTotal}}' } else { '{{.Host.MemTotal}}' }) 2> $null | Select-Object -First 1 | Out-String).Trim()
            $bytes = [int64]0
            $want = 4; if ($Keycloak) { $want = 5 }
            if ([int64]::TryParse($mem, [ref]$bytes) -and $bytes -gt 0) {
                if ($bytes -lt ($want * 1GB - 256MB)) {
                    $fix = 'podman machine stop; podman machine set --memory 6144; podman machine start'; if ($docker) { $fix = 'Docker Desktop: Settings, Resources, Memory' }
                    $with = ''; if ($Keycloak) { $with = ' with the bundled Keycloak' }
                    Add-Warning ('The container engine has ' + [int]($bytes / 1MB) + ' MB of memory; Kilnmark wants ' + $want + ' GB' + $with + '.') $fix
                } else { Add-Passed ([string][int]($bytes / 1MB) + ' MB of memory for containers') }
            }
        }
    }
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) { Add-Passed 'curl.exe' } else { Add-Problem 'curl.exe is missing (it comes with Windows 10 1803 and later).' 'update Windows (Settings, Windows Update)' }
    if (Get-Command tar.exe -ErrorAction SilentlyContinue) { Add-Passed 'tar.exe' } else { Add-Problem 'tar.exe is missing (it comes with Windows 10 1803 and later).' 'update Windows (Settings, Windows Update)' }
    if (-not (Get-Cosign)) { Add-Warning "cosign is not installed: downloads and images cannot be checked against Kilnmark's signature (-SkipVerify goes on without)." 'winget install Sigstore.Cosign' }
    $target = $script:Data
    if (-not $target) { $target = Get-Answer 'data_dir' }
    $drive = (Split-Path $target -Qualifier).TrimEnd(':')
    $d = Get-PSDrive $drive -ErrorAction SilentlyContinue
    if ($d -and $d.Free -lt 10GB) { Add-Warning ('Less than 10 GB free on ' + $drive + ':; images, data and backups need more.') 'podman system prune (removes unused images), or free space on that drive' }
    elseif ($d) { Add-Passed ([string][int]($d.Free / 1GB) + ' GB free on ' + $drive + ':') }
    if (-not $Running) {
        $ports = @((Get-Port 'KILNMARK_PORT' 8780 'port'), (Get-Port 'KILNMARK_HTTPS_PORT' 8443 'https_port'), (Get-Port 'KILNMARK_DB_PORT' 5442 'db_port'))
        if ($Keycloak) { $ports += (Get-Port 'KILNMARK_KEYCLOAK_PORT' 8180 'keycloak_port') }
        foreach ($p in ($ports | Select-Object -Unique)) {
            if (Test-PortBusy $p) { Add-Problem ('Port ' + $p + ' is in use.') (Get-PortFix $p) } else { Add-Passed ('port ' + $p + ' is free') }
        }
    }
}

# Why a folder is not as install leaves it, or nothing: no link or junction; owned by the
# Administrators (or SYSTEM, or for the data folder the operator); and nobody but $Allowed (SIDs)
# able to change it.
function Get-FolderProblem([string]$Dir, [string[]]$Allowed, [string[]]$Owners) {
    if (-not (Test-Path -LiteralPath $Dir)) { return ($Dir + ' is missing') }
    $item = Get-Item -LiteralPath $Dir -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return ($Dir + ' is a link or a junction') }
    $owner = ''
    try { $owner = (Get-Acl -LiteralPath $Dir).GetOwner([Security.Principal.SecurityIdentifier]).Value } catch { $owner = '' }
    if ($Owners -notcontains $owner) { return ($Dir + ' is owned by someone install did not give it to') }
    $acl = Get-Acl -LiteralPath $Dir
    foreach ($ace in $acl.Access) {
        if ($ace.AccessControlType -ne 'Allow') { continue }
        if (([long]$ace.FileSystemRights -band $TreeWrites) -eq 0) { continue }
        $sid = ''
        try { $sid = $ace.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value } catch { $sid = '' + $ace.IdentityReference }
        if ($sid -like 'S-1-15-2-*' -or $sid -like 'S-1-15-3-*') { continue }
        if ($Allowed -notcontains $sid) { return ($Dir + ' may be changed by ' + $ace.IdentityReference + ', whom install did not give it to') }
    }
    return ''
}

# The install itself, by its mode (ADR 0051): who runs this, who owns the folders, what is running.
function Test-Install([bool]$Running) {
    $mode = Get-Setting 'KILNMARK_MODE'
    $kc = Get-SelfCall $script:Root
    if ($mode -ceq 'production' -or $mode -ceq 'starter-pack') { Add-Passed ('a ' + $mode + ' install') }
    else { Add-Problem 'KILNMARK_MODE in config\.env is not production or starter-pack: Kilnmark does not start.' ('set KILNMARK_MODE back to the mode it was installed with in ' + (Join-Path $script:Data 'config\.env')) }
    $me = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $operator = Get-OperatorSid
    if (Test-Elevated) { Add-Warning "check runs elevated here: Kilnmark's day-to-day commands run as the operator, from a prompt that is not elevated." ('open a prompt that is not elevated, then: ' + $kc + ' check') }
    elseif ($operator -and $operator -ne $me) { Add-Warning ('Kilnmark runs as ' + (Get-Answer 'operator') + ', not as you, so you cannot start or change it.') ('sign in to Windows as ' + (Get-Answer 'operator') + ', then: ' + $kc + ' check') }
    if (-not $operator) { $operator = $me }
    $ba = 'S-1-5-32-544'; $sy = 'S-1-5-18'; $ti = $TrustedInstaller
    $why = Get-FolderProblem $script:Root @($ba, $sy, $ti) @($ba, $sy, $ti)
    if (-not $why) { Add-Passed 'the program folder is the Administrators'', and nobody else may change it' }
    else { Add-Problem $why ('in an elevated prompt: icacls "' + $script:Root + '" /setowner *S-1-5-32-544 /T /C && icacls "' + $script:Root + '" /inheritance:r /grant:r *S-1-5-32-544:(OI)(CI)F *S-1-5-18:(OI)(CI)F *S-1-5-32-545:(OI)(CI)RX /T /C') }
    $why = Get-FolderProblem $script:Data @($ba, $sy, $operator) @($ba, $sy, $operator)
    if (-not $why) { Add-Passed 'the data folder is the operator''s, SYSTEM''s and the Administrators'' alone' }
    else { Add-Problem $why ('in an elevated prompt: icacls "' + $script:Data + '" /inheritance:r /grant:r *S-1-5-32-544:(OI)(CI)F *S-1-5-18:(OI)(CI)F *' + $operator + ':(OI)(CI)M /T /C') }
    if (Test-KeycloakOn) {
        # the second line of defence: what the hourly run uses, as it checks it
        $why = Get-TreeProblem
        if (-not $why) { Add-Passed 'nobody else may change what the hourly run uses' }
        else { Add-Problem ($why + '.') ('in an elevated prompt: icacls "' + $script:Root + '" /reset /T /C, then run install''s permissions again: icacls "' + $script:Root + '" /inheritance:r /grant:r *S-1-5-32-544:(OI)(CI)F *S-1-5-18:(OI)(CI)F *S-1-5-32-545:(OI)(CI)RX /T /C') }
    }
    $envFile = Join-Path $script:Data 'config\.env'
    if ($mode -ceq 'starter-pack') {
        if (-not (Test-KeycloakOn)) { Add-Problem 'A Starter Pack install needs the bundled Keycloak (auth in COMPOSE_PROFILES): it does not start without.' ('put COMPOSE_PROFILES=auth back in ' + $envFile + ', then ' + $kc + ' up') }
        if ((Get-Setting 'KILNMARK_STARTER') -cne 'allowed') { Add-Warning 'KILNMARK_STARTER is not allowed in config\.env, so the example people cannot be added (and are removed at the next start).' ('add KILNMARK_STARTER=allowed to ' + $envFile) }
    } elseif ($mode -ceq 'production') {
        if (Get-Setting 'KILNMARK_STARTER') { Add-Problem 'KILNMARK_STARTER is set on a production install, which never has the example people.' ('delete the line KILNMARK_STARTER from ' + $envFile) }
        if (-not (Test-KeycloakOn) -and (Get-Setting 'KILNMARK_OIDC_ENABLED') -eq 'true') {
            if ((Get-Setting 'KILNMARK_OIDC_ISSUER') -and (Get-Setting 'KILNMARK_OIDC_CLIENT_ID') -and (Get-Setting 'KILNMARK_OIDC_CLIENT_SECRET')) { Add-Passed 'sign-in with your identity provider is set up' }
            else { Add-Problem 'Sign-in with your identity provider lacks its issuer, client id or client secret.' ($kc + ' configure identity=own') }
        }
    }
    $hostName = Get-Answer 'host'
    if ($hostName -and -not (Test-LocalHost $hostName) -and (Get-Answer 'tls') -eq 'off') {
        Add-Warning ('Kilnmark is reached as ' + $hostName + ' over plain HTTP: its sign-in cookies need HTTPS.') ($kc + ' configure tls=on tls_cert=FILE tls_key=FILE (or tls=proxy behind a proxy that ends TLS)')
    }
    if ($Running) {
        Add-Passed ('Kilnmark answers at ' + (Get-LocalUrl))
        $recorded = Get-RecordedMode
        if ($recorded -and $recorded -cne $mode) { Add-Problem ('KILNMARK_MODE in config\.env (' + $mode + ') differs from the mode Kilnmark recorded at its first start (' + $recorded + ').') ('set KILNMARK_MODE=' + $recorded + ' in ' + $envFile) }
        if (Test-KeycloakOn) {
            $port = Get-Setting 'KILNMARK_KEYCLOAK_PORT'; if (-not $port) { $port = '8180' }
            if (Test-Answers ('http://127.0.0.1:' + $port + '/realms/kilnmark')) { Add-Passed 'Keycloak answers' }
            else { Add-Problem 'Keycloak does not answer.' ($kc + ' restart, then ' + $kc + ' logs keycloak') }
        }
    } else {
        Add-Warning 'Kilnmark is not running.' ($kc + ' up')
    }
    if (Test-KeycloakOn) {
        $expiry = Get-ExpiryState
        Write-Host ('People expiry: ' + $expiry)
        if ($expiry -eq 'not set up') { Add-Warning 'The hourly expiry of unused temporary passwords is not set up.' ($kc + ' up') }
    }
    $last = Get-ChildItem (Join-Path $script:Data 'backups') -Directory -Filter 'kilnmark-*' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
    if (-not $last) { Add-Warning ('There is no backup in ' + (Join-Path $script:Data 'backups') + '.') ($kc + ' backup') }
    elseif ($last.LastWriteTime -lt (Get-Date).AddHours(-26)) { Add-Warning 'The last backup is more than a day old.' ($kc + ' backup (every day, from Task Scheduler)') }
    else { Add-Passed 'a backup from the last day' }
}

function Write-Checks {
    foreach ($p in $script:Passed) { Write-Host ('OK: ' + $p) }
    for ($i = 0; $i -lt $script:Warnings.Count; $i++) {
        Write-Host ('Warning: ' + $script:Warnings[$i]) -ForegroundColor Yellow
        if ($script:WarningFixes[$i]) { Write-Host ('  Fix: ' + $script:WarningFixes[$i]) }
    }
    for ($i = 0; $i -lt $script:Problems.Count; $i++) {
        Write-Host ('Problem: ' + $script:Problems[$i]) -ForegroundColor Red
        if ($script:Fixes[$i]) { Write-Host ('  Fix: ' + $script:Fixes[$i]) }
    }
    return ($script:Problems.Count -eq 0)
}

function Invoke-Check([string[]]$Options) {
    if ($Options.Count -gt 0) { Fail 'check takes no options.' }
    if ($script:Data -and (Test-Path -LiteralPath (Join-Path $script:Data 'config\.env'))) {
        [void](Read-InstalledAnswers)
        $running = Test-Answers ((Get-LocalUrl) + '/q/health/ready')
        Write-Host ('Checking Kilnmark in ' + $script:Root + ' (data in ' + $script:Data + '), a ' + (Get-Setting 'KILNMARK_MODE') + ' install')
        Test-Prerequisites $running (Test-KeycloakOn)
        Test-Install $running
        $added = Get-NewSettings (Join-Path (Get-CurrentDir) '.env.example')
        if ($added.Count -gt 0) { Write-Host ("Settings in this version's .env.example that config\.env does not mention: " + ($added -join ' ')) }
    } else {
        Write-Host 'Checking this machine for a Kilnmark install'
        Write-Host '(install asks Windows for an administrator, UAC, for the one step that needs one, and says why)'
        Test-Prerequisites $false $false
    }
    if (Write-Checks) { Write-Host 'All checks passed.' } else { exit 1 }
}

# Settings this version's .env.example names that config\.env does not mention at all.
function Get-NewSettings([string]$Example) {
    $env = Join-Path $script:Data 'config\.env'
    if (-not ((Test-Path -LiteralPath $Example) -and (Test-Path -LiteralPath $env))) { return @() }
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

# Sets KEY=VALUE in the file: replaces a "KEY=" or "# KEY=" line, or adds one at the end. Never a
# value with $ or a backtick: compose reads ${NAME} in config\.env as another setting's value, which
# would hand that one (the admin token, say) to whatever reads this one.
function Set-Line([string]$File, [string]$Key, [string]$Value) {
    if ($Value -match '[$`]') { Fail ($Key + ' was not written to config\.env: its value holds $ or `, which compose would read as the start of another setting''s name.') }
    $lines = [IO.File]::ReadAllLines($File)
    $done = $false
    $out = foreach ($l in $lines) {
        if (-not $done -and ($l -match ('^(# *)?' + $Key + '='))) { $done = $true; $Key + '=' + $Value } else { $l }
    }
    $out = @($out)
    if (-not $done) { $out += ($Key + '=' + $Value) }
    Write-UnixText $File $out
}

# Permissions of yours alone, by your SID: nothing inherited, full control for you, for what is in
# the folder too, and nobody else (ADR 0051, decision 15).
function New-OwnSecurity {
    $me = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $sec = New-Object Security.AccessControl.DirectorySecurity
    $sec.SetAccessRuleProtection($true, $false)
    $inherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    $sec.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($me, [Security.AccessControl.FileSystemRights]::FullControl,
        $inherit, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)))
    return $sec
}

# Only the current user may read the folder and what is in it: its permissions written afresh (any
# other entry, inherited or not, goes). Nothing goes on when that fails.
function Protect-Folder([string]$Dir) {
    try {
        # only what changed (who may do what), never the audit settings, which need a privilege
        $di = New-Object IO.DirectoryInfo($Dir)
        if (-not $di.Exists) { throw (New-Object IO.DirectoryNotFoundException) }
        if ($PSVersionTable.PSVersion.Major -ge 6) { [IO.FileSystemAclExtensions]::SetAccessControl($di, (New-OwnSecurity)) } else { $di.SetAccessControl((New-OwnSecurity)) }
    }
    catch { Fail ('Could not make ' + $Dir + ' yours alone (its permissions could not be set), so nothing more was done. Check who owns it, and that you may change its permissions.') }
}

# Secrets later versions added, as config\.env now asks: run before every start, so upgrade and up
# keep them right. Each new secret is random.
function Update-Settings {
    $env = Join-Path $script:Data 'config\.env'
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
        $old = @(Get-ChildItem -LiteralPath (Join-Path $script:Root 'versions') -Filter 'compose.yaml' -Recurse -ErrorAction SilentlyContinue |
            Where-Object { [IO.File]::ReadAllText($_.FullName) -match '(?m)^\s*-\s*[^#\s][^#\n]*:/srv/files:ro' })
        if ($old.Count -gt 0) {
            Write-Host 'Warning: an earlier version mounted a folder of your own at /srv/files. Now set KILNMARK_FILES_DIR in' -ForegroundColor Yellow
            Write-Host 'config\.env to its full path: Kilnmark sees it read-only at /srv/share, so point its file sources there.' -ForegroundColor Yellow
        }
    }
}

# --- the install's answers (ADR 0051, decision 17) ---------------------------------------------

# Each answer install asks for, or reads from an answers file (install.conf.example says each). An
# answers file holds no secrets: secrets are generated, or typed (not shown) or piped in when asked.
$script:Answers = @{}
$script:AnswerLines = @{}
# answers configure leaves unchecked because the change is not to them (the certificate's files)
$script:SkipKeys = @()
$AnswerKeys = @('mode', 'identity', 'email_domain', 'host', 'port', 'https_port', 'db_port', 'keycloak_port', 'tls', 'tls_cert', 'tls_key',
    'oidc_issuer', 'oidc_client_id', 'oidc_provider_name', 'oidc_viewer_groups', 'oidc_steward_groups', 'oidc_designer_groups',
    'oidc_admin_groups', 'oidc_unmask_groups', 'admin_email', 'admin_first_name', 'admin_last_name', 'project', 'program_dir', 'data_dir', 'user', 'operator')
# Answers no configure changes: each needs a new install.
$FixedKeys = @('mode', 'project', 'program_dir', 'data_dir', 'operator')
function Get-AnswerDefault([string]$Key) {
    switch -CaseSensitive ($Key) {
        'mode' { return 'production' }
        'identity' { if ((Get-Answer 'mode') -ceq 'starter-pack') { return 'bundled' } else { return 'own' } }
        'host' { return 'localhost' }
        'port' { return '8780' }
        'https_port' { return '8443' }
        'db_port' { return '5442' }
        'keycloak_port' { return '8180' }
        'tls' { return 'off' }
        'oidc_provider_name' { return 'your organisation' }
        'oidc_viewer_groups' { return 'kilnmark-viewers' }
        'oidc_steward_groups' { return 'kilnmark-stewards' }
        'oidc_designer_groups' { return 'kilnmark-designers' }
        'oidc_admin_groups' { return 'kilnmark-admins' }
        'oidc_unmask_groups' { return 'kilnmark-sensitive-data' }
        'project' { return 'kilnmark' }
        'program_dir' { return $DefaultProgram }
        'data_dir' { return $DefaultData }
        'operator' { return [Security.Principal.WindowsIdentity]::GetCurrent().Name }
    }
    return ''
}
function Get-Answer([string]$Key) { if ($script:Answers.ContainsKey($Key)) { return [string]$script:Answers[$Key] }; return (Get-AnswerDefault $Key) }
function Test-KnownKey([string]$Key) { return ($AnswerKeys -ccontains $Key) }
# Whether an answer means anything with the others: the identity provider's for own, Keycloak's and
# the first admin's for bundled, the certificate's for tls=on.
function Test-Relevant([string]$Key) {
    if ($Key.StartsWith('oidc_')) { return ((Get-Answer 'identity') -ceq 'own') }
    switch -CaseSensitive ($Key) {
        'keycloak_port' { return ((Get-Answer 'identity') -ceq 'bundled') }
        'admin_email' { return ((Get-Answer 'identity') -ceq 'bundled') }
        'admin_first_name' { return ((Get-Answer 'identity') -ceq 'bundled' -and [bool](Get-Answer 'admin_email')) }
        'admin_last_name' { return ((Get-Answer 'identity') -ceq 'bundled' -and [bool](Get-Answer 'admin_email')) }
        'tls_cert' { return ((Get-Answer 'tls') -ceq 'on') }
        'tls_key' { return ((Get-Answer 'tls') -ceq 'on') }
        # the Linux edition's: the account that runs Kilnmark there
        'user' { return $false }
    }
    return $true
}
function Test-LocalHost([string]$Name) { return ($Name -ceq 'localhost' -or $Name -ceq '127.0.0.1') }
# The operator's SID, from the answers (or you): empty when Windows does not know the name.
function Get-OperatorSid {
    $name = Get-Answer 'operator'
    if (-not $name) { return '' }
    if ($name -like 'S-1-*') { return $name }
    try { return (New-Object Security.Principal.NTAccount($name)).Translate([Security.Principal.SecurityIdentifier]).Value } catch { return '' }
}

# Why an answer cannot be taken, or nothing. Never the value itself.
function Get-AnswerProblem([string]$Key, [string]$Value) {
    $v = $Value
    # Compose reads ${NAME} in config\.env as another setting's value (the admin token, say), which
    # the unauthenticated sign-in page would then show as the provider's name: no answer holds one.
    if ($v -match '[$`]') { return ($Key + ' may not hold $ or `, which compose would read as the start of another setting''s name') }
    switch -CaseSensitive ($Key) {
        'mode' { if ($v -cnotmatch '^(production|starter-pack)$') { return 'mode is production or starter-pack' } }
        'identity' {
            if ($v -cnotmatch '^(bundled|own)$') { return "identity is bundled (Kilnmark's own Keycloak) or own (your organisation's identity provider)" }
            if ($v -ceq 'own' -and (Get-Answer 'mode') -ceq 'starter-pack') { return 'a Starter Pack install signs in with the bundled Keycloak only: identity is bundled' }
        }
        'email_domain' {
            if ($v -and $v -cnotmatch '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$') { return 'email_domain is a domain name in lower case, such as bank.example' }
            if ($v -ceq 'kilnmark.com' -or $v.EndsWith('.kilnmark.com')) { return "kilnmark.com is the Starter Pack's example people's domain: give your organisation's" }
        }
        'host' {
            if (-not ((Test-LocalHost $v) -or $v -cmatch '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$' -or $v -cmatch '^[0-9]{1,3}(\.[0-9]{1,3}){3}$')) {
                return 'host is a host name in lower case (kilnmark.bank.example), an IPv4 address or localhost'
            }
        }
        'tls' { if ($v -cnotmatch '^(off|on|proxy)$') { return 'tls is off, on (Kilnmark ends TLS with your certificate) or proxy (a proxy in front ends it)' } }
        'tls_cert' {
            $ok = $false; try { $ok = (Test-Path -LiteralPath $v -PathType Leaf) -and ([IO.File]::ReadAllText($v) -match 'BEGIN CERTIFICATE') } catch { $ok = $false }
            if (-not $ok) { return 'tls_cert is the full path of a PEM certificate (with its chain) that install can read' }
        }
        'tls_key' {
            $ok = $false; try { $ok = (Test-Path -LiteralPath $v -PathType Leaf) -and ([IO.File]::ReadAllText($v) -match 'PRIVATE KEY') } catch { $ok = $false }
            if (-not $ok) { return "tls_key is the full path of the certificate's PEM private key, which install can read" }
        }
        'oidc_issuer' { if ($v -cnotmatch '^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[^\s"''\\]*)?$') { return "oidc_issuer is your identity provider's issuer URL, starting https://" } }
        'oidc_client_id' { if ($v -cnotmatch '^[A-Za-z0-9._:/@-]+$') { return 'oidc_client_id is the client id your identity provider gave Kilnmark' } }
        'admin_email' {
            if (-not $v) { return '' }
            if ($v -cnotmatch '^[a-z0-9._%+-]+@[a-z0-9-]+(\.[a-z0-9-]+)+$') { return 'admin_email is an email address in lower case' }
            if ($v.EndsWith('@kilnmark.com') -or $v.EndsWith('.kilnmark.com')) { return "kilnmark.com addresses are the Starter Pack's example people: give one of your own organisation" }
            $d = Get-Answer 'email_domain'
            if ($d -and -not ($v.EndsWith('@' + $d) -or $v.EndsWith('.' + $d))) { return 'admin_email is not at email_domain' }
        }
        'project' { if ($v -cnotmatch '^[a-z0-9][a-z0-9_-]*$') { return 'project is lower-case letters, digits, - and _, starting with a letter or a digit' } }
        'operator' { if (-not (Get-OperatorSid)) { return 'operator is an account Windows knows, as DOMAIN\name or name' } }
    }
    if ($Key -cin @('port', 'https_port', 'db_port', 'keycloak_port')) {
        $n = 0
        if ($v -cnotmatch '^[0-9]{1,5}$' -or -not [int]::TryParse($v, [ref]$n) -or $n -lt 1 -or $n -gt 65535) { return ($Key + ' is a port number from 1 to 65535') }
    }
    if ($Key -cin @('oidc_provider_name', 'oidc_viewer_groups', 'oidc_steward_groups', 'oidc_designer_groups', 'oidc_admin_groups', 'oidc_unmask_groups')) {
        if (-not $v -or $v -match '[\p{Cc}"''\\]') { return ($Key + ' is needed, with no quote, backslash or control character') }
    }
    if ($Key -cin @('admin_first_name', 'admin_last_name')) { if (-not $v -or $v -match '\p{Cc}') { return ($Key + ' is needed for the first admin') } }
    if ($Key -cin @('program_dir', 'data_dir')) {
        if ($v -cnotmatch '^[A-Za-z]:\\' -or $v -match '[\p{Cc}"*?<>|%]' -or $v.Substring(2).Contains(':')) { return ($Key + ' is a full path, such as C:\Program Files\Kilnmark, with no " * ? < > | % in it') }
    }
    return ''
}
# Why the answers do not go together, one line each. -Ports: busy ports count too.
function Get-AnswersProblems([switch]$Ports) {
    $out = @(); $seen = @{}
    foreach ($k in @('port', 'https_port', 'db_port', 'keycloak_port')) {
        if (-not (Test-Relevant $k)) { continue }
        $p = Get-Answer $k
        if ($seen.ContainsKey($p)) { $out += ($k + ' is a port another answer uses already') }
        $seen[$p] = $true
        $n = 0
        if ($Ports -and [int]::TryParse($p, [ref]$n) -and (Test-PortBusy $n)) { $out += ($k + ': port ' + $n + ' is in use (port ' + (Get-FreePort $n) + ' is free)') }
    }
    if (-not (Test-LocalHost (Get-Answer 'host')) -and (Get-Answer 'tls') -ceq 'off') {
        $out += 'tls: people sign in over HTTPS only, so from other machines Kilnmark needs tls on or proxy (or host localhost)'
    } elseif (-not (Test-LocalHost (Get-Answer 'host')) -and (Get-Answer 'identity') -ceq 'bundled' -and (Get-Answer 'tls') -cne 'proxy') {
        $out += 'tls: from other machines the bundled Keycloak is reached through a proxy that ends TLS (its realm asks for HTTPS), so tls is proxy (or host localhost)'
    }
    $a = (Get-Answer 'program_dir').TrimEnd('\'); $b = (Get-Answer 'data_dir').TrimEnd('\')
    if ($a -eq $b -or ($b + '\').StartsWith($a + '\', [StringComparison]::OrdinalIgnoreCase) -or ($a + '\').StartsWith($b + '\', [StringComparison]::OrdinalIgnoreCase)) {
        $out += 'program_dir and data_dir are two folders, neither inside the other'
    } elseif ($a.Length -ge 2 -and $b.Length -ge 2 -and $a.Substring(0, 2) -ne $b.Substring(0, 2)) {
        $out += 'data_dir must be on the same drive as program_dir: compose reaches it by a relative path'
    }
    return $out
}
# Every answer that means something, checked; each problem one line, by key (and line in a file).
function Test-AllAnswers([switch]$Ports) {
    $errs = @()
    foreach ($k in $AnswerKeys) {
        if (-not (Test-Relevant $k)) { continue }
        if ($script:SkipKeys -ccontains $k) { continue }
        $why = Get-AnswerProblem $k (Get-Answer $k)
        if ($why) {
            $at = ''; if ($script:AnswerLines.ContainsKey($k)) { $at = ' (line ' + $script:AnswerLines[$k] + ')' }
            $errs += ($k + $at + ': ' + $why)
        }
    }
    $errs += @(Get-AnswersProblems -Ports:$Ports)
    foreach ($e in $errs) { Write-Host ('Answer: ' + $e) -ForegroundColor Red }
    return ($errs.Count -eq 0)
}

# Reads an answers file: KEY=VALUE lines, # comments; refuses a secret, an unknown key or a key twice.
function Read-Answers([string]$File) {
    if (-not (Test-Path -LiteralPath $File -PathType Leaf)) { Fail 'There is no answers file that install can read there.' }
    $errs = @(); $n = 0
    foreach ($raw in [IO.File]::ReadAllLines($File)) {
        $n++
        $line = $raw.TrimEnd("`r")
        if ($line -match '^\s*(#.*)?$') { continue }
        if ($line -cnotmatch '^\s*([a-z_]+)\s*=(.*)$') { $errs += ('line ' + $n + ' is not KEY=VALUE'); continue }
        $k = $Matches[1]; $v = $Matches[2].Trim()
        if ($k -match '(password|secret|token)') { $errs += ('line ' + $n + ': ' + $k + ' is a secret, and an answers file holds none: install generates them, or asks'); continue }
        if (-not (Test-KnownKey $k)) { $errs += ('line ' + $n + ': ' + $k + ' is not an answer install knows (install.conf.example lists them)'); continue }
        if ($script:AnswerLines.ContainsKey($k)) { $errs += ('line ' + $n + ': ' + $k + ' is given twice'); continue }
        if ($v.Length -ge 2 -and (($v.StartsWith('"') -and $v.EndsWith('"')) -or ($v.StartsWith("'") -and $v.EndsWith("'")))) { $v = $v.Substring(1, $v.Length - 2) }
        $script:Answers[$k] = $v; $script:AnswerLines[$k] = $n
    }
    if ($errs.Count -gt 0) {
        foreach ($e in $errs) { Write-Host ('Answers file: ' + $e) -ForegroundColor Red }
        Fail 'The answers file has the problems above, so nothing was changed.'
    }
}
# The answers this install was made with, from config\install.conf in the data folder.
function Read-InstalledAnswers {
    if (-not $script:Data) { return $false }
    $f = Join-Path $script:Data 'config\install.conf'
    if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { return $false }
    Read-Answers $f
    $script:AnswerLines = @{}
    return $true
}
# config\install.conf: the answers that mean something, never a secret.
function Write-Answers([string]$File) {
    $lines = @("# Kilnmark's install answers (no secrets). configure changes them; the mode never changes.", '# Every key is explained in install.conf.example, in the release.')
    foreach ($k in $AnswerKeys) { if (Test-Relevant $k) { $lines += ($k + '=' + (Get-Answer $k)) } }
    Write-UnixText $File $lines
}
function Show-Answers { foreach ($k in $AnswerKeys) { if (Test-Relevant $k) { Write-Host ('  ' + $k + '=' + (Get-Answer $k)) } } }

# Asks one question at a console, with its default, until the answer is one install can take.
function Read-Answer([string]$Key, [string]$Question) {
    $d = Get-Answer $Key
    $shown = $d; if (-not $shown) { $shown = 'none' }
    while ($true) {
        $v = Read-Host ($Question + ' [' + $shown + ']')
        if ($null -eq $v) { Fail 'No answer was given, so nothing was installed.' }
        $v = $v.Trim(); if (-not $v) { $v = $d }
        if ($v -ceq 'none' -and -not $d) { $v = '' }
        $script:Answers[$Key] = $v
        $why = Get-AnswerProblem $Key $v
        $n = 0
        if (-not $why -and $Key.EndsWith('port') -and [int]::TryParse($v, [ref]$n) -and (Test-PortBusy $n)) { $why = 'port ' + $n + ' is in use (port ' + (Get-FreePort $n) + ' is free)' }
        if (-not $why) { return }
        Write-Host ('  ' + $why + '. Try again.') -ForegroundColor Yellow
        $script:Answers.Remove($Key)
    }
}

# A secret typed twice, not shown, at a console, else one line on standard input: in
# $script:SecretValue, never shown or written anywhere but config\.env (the client secret) or Keycloak.
$script:SecretValue = ''
function Read-SecretValue([string]$Question) {
    if ([Console]::IsInputRedirected) {
        $s = [Console]::In.ReadLine(); if ($null -eq $s) { $s = '' }; $script:SecretValue = $s.TrimEnd("`r")
    } else {
        $a = Read-Secret ($Question + ' (not shown)')
        $b = Read-Secret 'The same again'
        if ($a -cne $b) { $a = $null; $b = $null; Fail 'The two did not match. Nothing was changed.' }
        $script:SecretValue = $a; $a = $null; $b = $null
    }
    if ($script:SecretValue -match '\p{Cc}') { $script:SecretValue = ''; Fail 'It holds a control character (a tab, say), which it may not. Nothing was changed.' }
}

# The questions, each with its default and checked as it is answered.
function Read-AllAnswers {
    Write-Host 'Answer each question, or press Enter for the default in [brackets].'
    Read-Answer 'mode' 'Mode: production, for real work, or starter-pack, for training and development only (it never becomes production)'
    if ((Get-Answer 'mode') -ceq 'starter-pack') {
        $script:Answers['identity'] = 'bundled'
        Write-Host 'A Starter Pack install signs in with the bundled Keycloak, where its example people are.'
    } else {
        Read-Answer 'identity' "How people sign in: own (your organisation's identity provider, recommended for banks) or bundled (Kilnmark's own Keycloak)"
    }
    Read-Answer 'email_domain' "Your organisation's email domain, which people's addresses end in (Enter for none)"
    Read-Answer 'host' 'The host name people reach Kilnmark on'
    Read-Answer 'tls' 'TLS: off, on (Kilnmark ends TLS with your certificate) or proxy (a proxy in front ends it)'
    if ((Get-Answer 'tls') -ceq 'on') {
        Read-Answer 'tls_cert' 'Your certificate, with its chain: the full path of its PEM file'
        Read-Answer 'tls_key' 'Its private key: the full path of its PEM file'
        Read-Answer 'port' "Kilnmark's port for plain HTTP, which it sends on to HTTPS"
        Read-Answer 'https_port' "Kilnmark's HTTPS port"
    } else {
        Read-Answer 'port' "Kilnmark's port"
        # published whether or not TLS is on, so it must be free either way
        Read-Answer 'https_port' "Kilnmark's HTTPS port, published for when TLS is on"
    }
    Read-Answer 'db_port' "The port of Kilnmark's own database, on this machine only"
    if ((Get-Answer 'identity') -ceq 'bundled') {
        Read-Answer 'keycloak_port' "Keycloak's port, on this machine only"
        Read-Answer 'admin_email' "Kilnmark's first admin, a person of your own: their email, which is their user name (Enter to skip)"
        if (Get-Answer 'admin_email') {
            Read-Answer 'admin_first_name' 'Their first name'
            Read-Answer 'admin_last_name' 'Their last name'
        }
    } else {
        Read-Answer 'oidc_issuer' "Your identity provider's issuer URL"
        Read-Answer 'oidc_client_id' 'The client id it gave Kilnmark'
        Read-Answer 'oidc_provider_name' 'Its name, for the sign-in button (Sign in with ...)'
        Read-Answer 'oidc_admin_groups' "Its group (or groups, comma-separated) of Kilnmark's admins"
        Read-Answer 'oidc_designer_groups' 'Its group of designers'
        Read-Answer 'oidc_steward_groups' 'Its group of stewards'
        Read-Answer 'oidc_viewer_groups' 'Its group of viewers'
        Read-Answer 'oidc_unmask_groups' 'Its group of designers who see sensitive values'
    }
}

# The secrets the answers need, asked before anything changes: the identity provider's client
# secret (own), or the first admin's temporary password (bundled, with a first admin).
$script:AdminPassword = ''
# The identity provider's client secret, asked before anything changes. It goes into config\.env and
# Kilnmark's own file of secrets (Write-ServiceSecrets), so it holds nothing compose would read as
# more than itself.
function Read-ClientSecret {
    Read-SecretValue 'The client secret your identity provider gave Kilnmark'
    if (-not $script:SecretValue) { Fail 'The client secret is needed for sign-in with your identity provider. Nothing was changed.' }
    if (-not (Test-PlainValue $script:SecretValue)) { $script:SecretValue = ''; Fail 'The client secret holds a space, a quote mark, #, $, ` or \, which compose tools do not all read alike: ask your identity provider for another. Nothing was changed.' }
}
function Read-InstallSecrets {
    if ((Get-Answer 'identity') -ceq 'own') {
        Read-ClientSecret
    } elseif (Get-Answer 'admin_email') {
        Read-SecretValue 'A temporary password for the first admin, 12 characters or more (they choose their own at their first sign-in)'
        $script:AdminPassword = $script:SecretValue; $script:SecretValue = ''
        if ($script:AdminPassword.Length -lt 12) { $script:AdminPassword = ''; Fail "The first admin's temporary password is 12 characters or more (pipe it in on one line, or leave admin_email empty). Nothing was changed." }
    }
}

# --- secrets and settings from the answers ----------------------------------------------------

# Removes KEY from the file, as a commented line.
function Remove-Line([string]$File, [string]$Key) {
    $lines = @([IO.File]::ReadAllLines($File))
    $hit = $false
    $out = foreach ($l in $lines) { if ($l -cmatch ('^\s*(export\s+)?' + $Key + '\s*=')) { $hit = $true; '# ' + $Key + '=' } else { $l } }
    if ($hit) { Write-UnixText $File @($out) }
}
# COMPOSE_PROFILES with the auth profile (-On) or without it, keeping the others.
function Set-AuthProfile([string]$File, [bool]$On) {
    $list = @(((Get-Setting 'COMPOSE_PROFILES') -replace '\s', '') -split ',' | Where-Object { $_ -and $_ -cne 'auth' })
    if ($On) { $list += 'auth' }
    if ($list.Count -gt 0) { Set-Line $File 'COMPOSE_PROFILES' ($list -join ',') } else { Remove-Line $File 'COMPOSE_PROFILES' }
}
function Set-NewSecret([string]$File, [string]$Key, [string]$Value) { if (-not (Get-Setting $Key)) { Set-Line $File $Key $Value } }
# config\.env from the answers: every setting an answer decides, written again each time, and each
# secret new where there is none (-NewClient: the bundled Keycloak's client secret afresh).
function Write-Env([switch]$NewClient) {
    $env = Join-Path $script:Data 'config\.env'
    if (-not (Test-Path -LiteralPath $env)) { Copy-Item -LiteralPath (Join-Path (Get-CurrentDir) '.env.example') -Destination $env }
    Set-NewSecret $env 'KILNMARK_DB_PASSWORD' (New-RandomValue)
    Set-NewSecret $env 'KILNMARK_ADMIN_TOKEN' ('km_' + (New-RandomValue))
    Set-Line $env 'KILNMARK_MODE' (Get-Answer 'mode')
    if ((Get-Answer 'mode') -ceq 'starter-pack') { Set-NewSecret $env 'KILNMARK_SAMPLES_DB_PASSWORD' (New-RandomValue) }
    Set-Line $env 'KILNMARK_PROJECT' (Get-Answer 'project')
    if (Get-Answer 'email_domain') { Set-Line $env 'KILNMARK_EMAIL_DOMAIN' (Get-Answer 'email_domain') } else { Remove-Line $env 'KILNMARK_EMAIL_DOMAIN' }
    Set-Line $env 'KILNMARK_PORT' (Get-Answer 'port')
    Set-Line $env 'KILNMARK_HTTPS_PORT' (Get-Answer 'https_port')
    Set-Line $env 'KILNMARK_DB_PORT' (Get-Answer 'db_port')
    $hostName = Get-Answer 'host'
    switch -CaseSensitive (Get-Answer 'tls') {
        'on' {
            $url = 'https://' + $hostName; if ((Get-Answer 'https_port') -ne '443') { $url += ':' + (Get-Answer 'https_port') }
            Set-Line $env 'KILNMARK_TLS_CERT_FILE' '/etc/kilnmark/tls/server.crt'
            Set-Line $env 'KILNMARK_TLS_KEY_FILE' '/etc/kilnmark/tls/server.key'
            Set-Line $env 'KILNMARK_HTTP_PLAIN' 'redirect'
            Remove-Line $env 'KILNMARK_OIDC_BEHIND_TLS_PROXY'
        }
        'proxy' {
            $url = 'https://' + $hostName
            foreach ($k in @('KILNMARK_TLS_CERT_FILE', 'KILNMARK_TLS_KEY_FILE', 'KILNMARK_HTTP_PLAIN')) { Remove-Line $env $k }
            Set-Line $env 'KILNMARK_HTTP_HSTS' 'always'
            Set-Line $env 'KILNMARK_OIDC_BEHIND_TLS_PROXY' 'true'
        }
        default {
            $url = 'http://' + $hostName + ':' + (Get-Answer 'port')
            foreach ($k in @('KILNMARK_TLS_CERT_FILE', 'KILNMARK_TLS_KEY_FILE', 'KILNMARK_HTTP_PLAIN', 'KILNMARK_OIDC_BEHIND_TLS_PROXY')) { Remove-Line $env $k }
        }
    }
    Set-Line $env 'KILNMARK_URL' $url
    Set-Line $env 'KILNMARK_OIDC_ENABLED' 'true'
    if ((Get-Answer 'identity') -ceq 'bundled') {
        Set-AuthProfile $env $true
        Set-NewSecret $env 'KILNMARK_KEYCLOAK_DB_PASSWORD' (New-RandomValue)
        Set-NewSecret $env 'KILNMARK_KEYCLOAK_ADMIN_PASSWORD' (New-RandomValue)
        if ($NewClient -or -not (Get-Setting 'KILNMARK_OIDC_CLIENT_SECRET')) { Set-Line $env 'KILNMARK_OIDC_CLIENT_SECRET' (New-RandomValue) }
        Set-Line $env 'KILNMARK_KEYCLOAK_PORT' (Get-Answer 'keycloak_port')
        # Keycloak is published on this machine only: from other machines, a proxy that ends TLS
        if (Test-LocalHost $hostName) { $kc = 'http://localhost:' + (Get-Answer 'keycloak_port') } else { $kc = 'https://' + $hostName + ':' + (Get-Answer 'keycloak_port') }
        Set-Line $env 'KILNMARK_KEYCLOAK_URL' $kc
        if (Test-LocalHost $hostName) { Set-Line $env 'KILNMARK_KEYCLOAK_BIND' '127.0.0.1' } else { Set-Line $env 'KILNMARK_KEYCLOAK_BIND' '0.0.0.0' }
        Set-Line $env 'KILNMARK_OIDC_ISSUER' 'http://keycloak:8080/realms/kilnmark'
        Set-Line $env 'KILNMARK_OIDC_CLIENT_ID' 'kilnmark'
        Set-Line $env 'KILNMARK_OIDC_PROVIDER_NAME' 'Keycloak'
        Set-Line $env 'KILNMARK_OIDC_VIEWER_GROUPS' 'kilnmark-viewers'
        Set-Line $env 'KILNMARK_OIDC_STEWARD_GROUPS' 'kilnmark-stewards'
        Set-Line $env 'KILNMARK_OIDC_DESIGNER_GROUPS' 'kilnmark-designers'
        Set-Line $env 'KILNMARK_OIDC_ADMIN_GROUPS' 'kilnmark-admins'
        Set-Line $env 'KILNMARK_OIDC_UNMASK_GROUPS' 'kilnmark-sensitive-data'
        # a Starter Pack install's example people may be added (ADR 0051)
        if ((Get-Answer 'mode') -ceq 'starter-pack') { Set-Line $env 'KILNMARK_STARTER' 'allowed' } else { Remove-Line $env 'KILNMARK_STARTER' }
    } else {
        Set-AuthProfile $env $false
        Remove-Line $env 'KILNMARK_STARTER'
        Set-Line $env 'KILNMARK_OIDC_ISSUER' (Get-Answer 'oidc_issuer')
        Set-Line $env 'KILNMARK_OIDC_CLIENT_ID' (Get-Answer 'oidc_client_id')
        if ($script:SecretValue) { Set-Line $env 'KILNMARK_OIDC_CLIENT_SECRET' $script:SecretValue }
        Set-Line $env 'KILNMARK_OIDC_PROVIDER_NAME' (Get-Answer 'oidc_provider_name')
        Set-Line $env 'KILNMARK_OIDC_VIEWER_GROUPS' (Get-Answer 'oidc_viewer_groups')
        Set-Line $env 'KILNMARK_OIDC_STEWARD_GROUPS' (Get-Answer 'oidc_steward_groups')
        Set-Line $env 'KILNMARK_OIDC_DESIGNER_GROUPS' (Get-Answer 'oidc_designer_groups')
        Set-Line $env 'KILNMARK_OIDC_ADMIN_GROUPS' (Get-Answer 'oidc_admin_groups')
        Set-Line $env 'KILNMARK_OIDC_UNMASK_GROUPS' (Get-Answer 'oidc_unmask_groups')
    }
    $script:SecretValue = ''
}
# The certificate and key for tls=on, into certs\https.
function Copy-Certs {
    if ((Get-Answer 'tls') -cne 'on') { return }
    try {
        Copy-Item -LiteralPath (Get-Answer 'tls_cert') -Destination (Join-Path $script:Data 'certs\https\server.crt') -Force -ErrorAction Stop
        Copy-Item -LiteralPath (Get-Answer 'tls_key') -Destination (Join-Path $script:Data 'certs\https\server.key') -Force -ErrorAction Stop
    } catch { Fail ('The certificate or its key could not be copied into ' + (Join-Path $script:Data 'certs\https') + '.') }
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
    & curl.exe -k -L -s -f --max-time 10 -o NUL $Url *> $null
    return ($LASTEXITCODE -eq 0)
}

function Wait-For([string]$What, [string]$Url, [int]$Tries) {
    Write-Host ('Waiting for ' + $What + ' ...')
    $Url = $Url -replace '://localhost:', '://127.0.0.1:'
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
                        $stamped = @(& $script:Engine @base run --rm --no-deps -T keycloak-setup 2>&1 | ForEach-Object { ConvertTo-Said $_ } | Where-Object { -not (Test-ComposeCommand $_) })
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
    Write-Host ('Program folder: ' + $script:Root + '; data folder: ' + $script:Data)
    $last = Get-ChildItem (Join-Path $script:Data 'backups') -Directory -Filter 'kilnmark-*' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
    if ($last) { Write-Host ('Last backup: ' + $last.Name) } else { Write-Host ('Last backup: none in ' + (Join-Path $script:Data 'backups')) }
}

# --- install, upgrade, uninstall --------------------------------------------------------------

# Day-to-day commands run as the operator, from a prompt that is not elevated.
function Assert-Operator([string]$What) {
    if (Test-Elevated) { Fail ($What + ' runs as the operator, from a prompt that is not elevated: open one without "Run as administrator", then run it again.') }
    if (-not $script:Data) { return }
    $f = Join-Path $script:Data 'config\install.conf'
    if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { return }
    $name = ''
    foreach ($l in [IO.File]::ReadAllLines($f)) { if ($l -cmatch '^operator=(.*)$') { $name = $Matches[1].Trim() } }
    if (-not $name) { return }
    $sid = ''
    try { $sid = (New-Object Security.Principal.NTAccount($name)).Translate([Security.Principal.SecurityIdentifier]).Value } catch { $sid = '' }
    if ($sid -and $sid -ne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value) {
        Fail ('Kilnmark runs as ' + $name + ': sign in to Windows as them to run ' + $What + '.')
    }
}

# Whether $Sid is a user's, as Windows names it (LookupAccountSid says a user, never a group, an
# alias or a well-known SID): the operator the elevated steps trust besides the Administrators and
# SYSTEM, so a group's SID would trust everyone in it.
function Test-UserSid([string]$Sid) {
    if ($Sid -cnotmatch '^S-1-(5-21|12-1)-[0-9-]+$') { return $false }
    if (-not ('Kilnmark.Sids' -as [type])) {
        Add-Type -Namespace Kilnmark -Name Sids -MemberDefinition @'
[DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
public static extern bool LookupAccountSid(string system, byte[] sid, System.Text.StringBuilder name, ref uint nameLength, System.Text.StringBuilder domain, ref uint domainLength, out int use);
'@
    }
    try {
        $id = New-Object Security.Principal.SecurityIdentifier($Sid)
        $bytes = New-Object byte[] ($id.BinaryLength)
        $id.GetBinaryForm($bytes, 0)
        $name = New-Object Text.StringBuilder 256; $domain = New-Object Text.StringBuilder 256
        [uint32]$nl = 256; [uint32]$dl = 256; $use = 0
        if (-not [Kilnmark.Sids]::LookupAccountSid($null, $bytes, $name, [ref]$nl, $domain, [ref]$dl, [ref]$use)) { return $false }
        return ($use -eq 1)
    } catch { return $false }
}

# Runs a part of install, upgrade or uninstall as an administrator: Windows asks (UAC), and it runs
# in a window of its own; its log is in the data folder's logs. The tests run it as they are.
function Invoke-Elevated([string]$Script, [string[]]$Arguments) {
    $shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $line = '-NoProfile -ExecutionPolicy Bypass -File "' + $Script + '"'
    foreach ($a in $Arguments) { if ($a.StartsWith('-')) { $line += ' ' + $a } else { $line += ' "' + $a.TrimEnd('\') + '"' } }
    try {
        if (Test-TestHooks) { $p = Start-Process -FilePath $shell -ArgumentList $line -PassThru -NoNewWindow -ErrorAction Stop }
        else { $p = Start-Process -FilePath $shell -ArgumentList $line -Verb RunAs -PassThru -ErrorAction Stop }
    } catch { Fail 'Windows did not run it as an administrator (UAC was declined, or no administrator could allow it), so nothing was changed.' }
    # the handle first, or Windows PowerShell may not keep the exit code
    $null = $p.Handle
    $p.WaitForExit()
    return $p.ExitCode
}
# A new folder for a moment's files (a step's answers and result), deleted after: yours, and the
# Administrators' and SYSTEM's, since the step UAC elevates may run as another administrator.
function New-WorkFolder {
    $work = Join-Path ([IO.Path]::GetTempPath()) ('kilnmark-' + [Guid]::NewGuid().ToString('N'))
    $sec = New-Object Security.AccessControl.DirectorySecurity
    $sec.SetAccessRuleProtection($true, $false)
    $inherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    foreach ($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-32-544', 'S-1-5-18')) {
        $sec.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule((New-Object Security.Principal.SecurityIdentifier($sid)), [Security.AccessControl.FileSystemRights]::FullControl, $inherit, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)))
    }
    try {
        $di = New-Object IO.DirectoryInfo($work)
        if ($PSVersionTable.PSVersion.Major -ge 6) { [IO.FileSystemAclExtensions]::Create($di, $sec) } else { $di.Create($sec) }
    } catch { Fail ('Could not make a folder for this step in ' + [IO.Path]::GetTempPath() + '.') }
    return $work
}
# What an elevated part said: OK, or FAILED with one line saying why; it failed if nothing was said.
function Read-ElevatedResult([string]$File, [int]$Code) {
    $text = ''
    if (Test-Path -LiteralPath $File) { $text = ([IO.File]::ReadAllText($File)).Trim() }
    if ($Code -eq 0 -and $text.StartsWith('OK')) { return }
    if ($text.StartsWith('FAILED ')) { Fail $text.Substring(7) }
    Fail ('it stopped as an administrator (code ' + $Code + '): its log is in the data folder''s logs')
}

# A folder made with these permissions from its first moment, nothing inherited: $Entries holds
# pairs of a SID and its rights. Owned by the Administrators. The tests, which run as they are and
# play the administrator, keep their own ownership and full control, as the Administrators have.
function New-ProtectedFolder([string]$Dir, [object[]]$Entries) {
    if (Test-Path -LiteralPath $Dir) { Fail ($Dir + ' is there already: install makes it afresh. Remove it (uninstall -Purge), or choose another folder. Nothing was changed.') }
    if (Test-TestHooks) { $Entries += , @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'FullControl') }
    $sec = New-Object Security.AccessControl.DirectorySecurity
    $sec.SetAccessRuleProtection($true, $false)
    $inherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    foreach ($e in $Entries) {
        $sid = New-Object Security.Principal.SecurityIdentifier($e[0])
        $sec.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid, [Security.AccessControl.FileSystemRights]$e[1], $inherit, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)))
    }
    if (-not (Test-TestHooks)) { $sec.SetOwner((New-Object Security.Principal.SecurityIdentifier('S-1-5-32-544'))) }
    try {
        $parent = Split-Path $Dir -Parent
        if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory $parent -ErrorAction Stop | Out-Null }
        $di = New-Object IO.DirectoryInfo($Dir)
        if ($PSVersionTable.PSVersion.Major -ge 6) { [IO.FileSystemAclExtensions]::Create($di, $sec) } else { $di.Create($sec) }
    } catch { Fail ($Dir + ' could not be made with its permissions.') }
}

function Copy-Launchers([string]$V) {
    $dir = Join-Path (Join-Path $script:Root 'versions') $V
    foreach ($f in $Launchers) {
        $src = Join-Path $dir $f
        if (Test-Path -LiteralPath $src) { Copy-Item -LiteralPath $src -Destination (Join-Path $script:Root $f) -Force }
    }
    Write-UnixText (Join-Path $script:Root 'current.txt') @($V)
}

function Invoke-Install([string[]]$Options) {
    $start = $true; $dry = $false; $answers = $null; $cli = @{}
    for ($i = 0; $i -lt $Options.Count; $i++) {
        switch ($Options[$i]) {
            '-Answers' { $i++; $answers = $Options[$i] }
            '-ProgramDir' { $i++; $cli['program_dir'] = [IO.Path]::GetFullPath($Options[$i]).TrimEnd('\') }
            '-DataDir' { $i++; $cli['data_dir'] = [IO.Path]::GetFullPath($Options[$i]).TrimEnd('\') }
            '-Operator' { $i++; $cli['operator'] = $Options[$i] }
            '-Offline' { $i++; $script:Offline = (Resolve-Path $Options[$i]).Path }
            '-Starter' { $script:WithStarter = $true }
            '-Build' { $script:Build = $true }
            '-SkipVerify' { $script:SkipVerify = $true }
            '-NoStart' { $start = $false }
            '-DryRun' { $dry = $true }
            default { Fail ('Unknown option ' + $Options[$i] + '. See: kilnmark.cmd help') }
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $Here 'compose.yaml'))) { Fail 'Run install from the unpacked release folder, kilnmark-<version>.' }
    $v = Get-VersionOf $Here
    if (-not $v) { Fail "Cannot tell this release's version from its compose.yaml." }
    if ((Test-Elevated) -and -not $dry) { Fail 'Run install from a prompt that is not elevated: it asks Windows for an administrator (UAC) only for the step that needs one, and runs Podman as you, the operator.' }
    if ($answers) { Read-Answers ((Resolve-Path $answers).Path) }
    foreach ($k in $cli.Keys) { $script:Answers[$k] = $cli[$k] }
    if ($script:WithStarter) {
        if ($script:Answers.ContainsKey('mode') -and $script:Answers['mode'] -cne 'starter-pack') { Fail '-Starter makes a Starter Pack install, and the answers say another mode: choose one. Nothing was changed.' }
        $script:Answers['mode'] = 'starter-pack'
    }
    Write-Host ('Installing Kilnmark ' + $v)
    if (-not $answers) {
        if ([Console]::IsInputRedirected) { Fail 'There is no console to ask the questions at: give the answers in a file (-Answers install.conf; install.conf.example says each).' }
        Read-AllAnswers
        Write-Host 'Install with these answers:'
        Show-Answers
        $yes = Read-Host 'Go on? [Y/n]'
        if ($yes -and $yes -notmatch '^[Yy]') { Fail 'Nothing was installed.' }
    }
    # the steps: the answers, the prerequisites, the folders (as an administrator) and the images;
    # then the start, the first admin and the Starter Pack where they apply
    $total = 4
    if ($start) {
        $total++
        if ((Get-Answer 'identity') -ceq 'bundled' -and (Get-Answer 'admin_email')) { $total++ }
        if ((Get-Answer 'mode') -ceq 'starter-pack') { $total++ }
    }
    if ($dry) { $total = 2 }
    Set-Steps $total
    Start-Step 'Check the answers'
    if (-not (Test-AllAnswers -Ports)) { Fail 'Some answers cannot be taken (above), so nothing was changed.' }
    $operatorSid = Get-OperatorSid
    if ($operatorSid -and $operatorSid -ne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value) {
        Fail ('Install runs as the operator, who runs Podman and Kilnmark: sign in to Windows as ' + (Get-Answer 'operator') + ', then run install, not elevated. Nothing was changed.')
    }
    if (-not $dry) { Read-InstallSecrets }
    Complete-Step
    Start-Step 'Check the prerequisites'
    Test-Prerequisites $false ((Get-Answer 'identity') -ceq 'bundled')
    if (-not (Write-Checks)) { Fail 'Fix the problems above (each says how), then run install again.' }
    # the release runs as an administrator next: nobody else may have changed it (ADR 0051, decision 17)
    Assert-ReleaseItsOwn $Here 'install'
    Complete-Step
    $prog = Get-Answer 'program_dir'; $data = Get-Answer 'data_dir'
    if ($dry) {
        Write-Host ('Install would place Kilnmark ' + $v + ' in ' + $prog + " (the Administrators'), keep its data in " + $data + ' (' + (Get-Answer 'operator') + "'s, SYSTEM's and the Administrators'), and run it as " + (Get-Answer 'operator') + ', with these answers:')
        Show-Answers
        Write-Host 'Nothing was changed (-DryRun).'
        return
    }
    Start-Step ('Make ' + $prog + ' and ' + $data + ', as an administrator')
    Write-Host ('Windows now asks for an administrator (UAC): only administrators may write ' + $prog + ' and ' + $data + '. That part runs in a window of its own, and its log goes to ' + (Join-Path $data 'logs') + '.')
    $work = New-WorkFolder
    try {
        Write-Answers (Join-Path $work 'answers.conf')
        $result = Join-Path $work 'result.txt'
        $code = Invoke-Elevated (Join-Path $Here 'kilnmark.ps1') @('_install-elevated', '-Answers', (Join-Path $work 'answers.conf'), '-OperatorSid', $operatorSid, '-Result', $result)
        Read-ElevatedResult $result $code
    } finally { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
    $script:Root = $prog; $script:Data = $data; $script:Project = Get-Answer 'project'
    # your organisation's client secret, written by you (the operator may change config\.env)
    if ((Get-Answer 'identity') -ceq 'own' -and $script:SecretValue) { Set-Line (Join-Path $data 'config\.env') 'KILNMARK_OIDC_CLIENT_SECRET' $script:SecretValue; $script:SecretValue = '' }
    # the certificate and its key, copied by you, with your own rights (never as an administrator)
    Copy-Certs
    Complete-Step
    Start-Log 'install'
    Start-Step 'Get the images'
    Get-ImagesFor $v
    Complete-Step
    if ($start) {
        Start-Step 'Start Kilnmark'
        Invoke-Up @('-NoBrowser')
        Complete-Step
        if ((Get-Answer 'identity') -ceq 'bundled' -and (Get-Answer 'admin_email')) {
            Start-Step 'Add the first admin'
            $person = @{ Email = (Get-Answer 'admin_email'); First = (Get-Answer 'admin_first_name'); Last = (Get-Answer 'admin_last_name'); Groups = 'kilnmark-admins'; Password = $script:AdminPassword }
            $script:AdminPassword = ''
            if (-not (New-Person $person)) { Fail 'The first admin was not added (above): add them with people add.' }
            Complete-Step
        }
        if ((Get-Answer 'mode') -ceq 'starter-pack') {
            Start-Step 'Turn the Starter Pack on'
            Invoke-StarterOn
            Complete-Step
        }
    }
    $script:AdminPassword = ''
    $kc = Get-SelfCall $prog
    Write-Host ''
    Write-Host ('Kilnmark ' + $v + ' is installed: the program in ' + $prog + ', its data in ' + $data + ', run by ' + (Get-Answer 'operator') + '.')
    Write-Host ('  Sign in with the admin token, KILNMARK_ADMIN_TOKEN in ' + (Join-Path $data 'config\.env') + '; then make a token of your own under API tokens, and keep it safe.')
    Write-Host ('  Every day, from a prompt that is not elevated: ' + $kc + ' up | down | status | check | backup | help')
    Write-Host ('  Change an answer later: ' + $kc + ' configure KEY=VALUE')
    Write-Host ('  Shortcuts on the desktop and in the Start menu: powershell -ExecutionPolicy Bypass -File "' + (Join-Path $prog 'create-shortcut.ps1') + '"')
    Write-Host '  Back it up every day, for example from Task Scheduler: kilnmark.cmd backup'
    if ((Get-Answer 'mode') -ceq 'starter-pack') { Write-Host ('  Add the example people, with a password you type: ' + $kc + ' starter people') }
    if ((Get-Answer 'identity') -ceq 'own') { Write-Host ('  Register Kilnmark in your identity provider with the redirect URI ' + (Get-Setting 'KILNMARK_URL') + '/api/v1/auth/login.') }
    Stop-Log
}

# install's part as an administrator: the program folder (the Administrators', read-only to
# everyone else), the data folder (the operator's, SYSTEM's and the Administrators', nobody else's),
# the release, data-folder.txt, config\.env with new secrets and config\install.conf.
function Invoke-InstallElevated([string[]]$Options) {
    $file = $null; $operatorSid = $null
    for ($i = 0; $i -lt $Options.Count; $i++) {
        switch ($Options[$i]) {
            '-Answers' { $i++; $file = $Options[$i] }
            '-OperatorSid' { $i++; $operatorSid = $Options[$i] }
            '-Result' { $i++; $script:ResultFile = $Options[$i] }
            default { Fail ('Unknown option ' + $Options[$i] + '.') }
        }
    }
    if (-not (Test-Elevated) -and -not (Test-TestHooks)) { Fail 'This part of install needs an administrator.' }
    if (-not (Test-UserSid $operatorSid)) { Fail 'The operator was not named (a user''s SID, which Windows knows as a user, never a group).' }
    # the release this runs from, checked again as an administrator before anything is copied
    $script:AlsoTrusted = @($operatorSid)
    $why = Get-ReleaseProblem $Here
    if ($why) { Fail ('The release folder was refused: ' + $why + '. Nothing was changed.') }
    Read-Answers $file
    # The certificate's files are never read here, as an administrator: the operator copies them
    # with their own rights once the folders are made (Invoke-Install), so a file only an
    # administrator may read never reaches the data folder.
    $script:SkipKeys = @('tls_cert', 'tls_key')
    if (-not (Test-AllAnswers)) { Fail 'Some answers cannot be taken (above), so nothing was changed.' }
    $v = Get-VersionOf $Here
    $prog = Get-Answer 'program_dir'; $data = Get-Answer 'data_dir'
    if ((Test-Path -LiteralPath (Join-Path $prog 'versions')) -or (Test-Path -LiteralPath (Join-Path $prog 'kilnmark.ps1'))) {
        Fail ('Kilnmark is installed in ' + $prog + ' already: upgrade it, or uninstall it first. Nothing was changed.')
    }
    if (Test-Path -LiteralPath $data) { Fail ($data + ' is there already: uninstall the earlier install with -Purge, or remove it, or choose another data folder (-DataDir). Nothing was changed.') }
    $ba = 'S-1-5-32-544'; $sy = 'S-1-5-18'
    New-ProtectedFolder $prog @(@($ba, 'FullControl'), @($sy, 'FullControl'), @('S-1-5-32-545', 'ReadAndExecute'))
    New-ProtectedFolder $data @(@($ba, 'FullControl'), @($sy, 'FullControl'), @($operatorSid, 'Modify'))
    $script:Root = $prog; $script:Data = $data
    foreach ($d in @('config', 'certs', 'certs\https', 'backups', 'logs')) { New-Item -ItemType Directory (Join-Path $data $d) | Out-Null }
    Start-Log 'install-elevated'
    New-Item -ItemType Directory (Join-Path $prog 'versions') | Out-Null
    $dest = Join-Path (Join-Path $prog 'versions') $v
    Copy-Item -LiteralPath $Here -Destination $dest -Recurse
    Copy-Launchers $v
    Write-UnixText (Join-Path $prog 'data-folder.txt') @($data)
    Write-Host ('Placed Kilnmark ' + $v + ' in ' + $dest + '.')
    Copy-Item -LiteralPath (Join-Path $dest '.env.example') -Destination (Join-Path $data 'config\.env.example')
    Write-Env
    Write-Answers (Join-Path $data 'config\install.conf')
    Write-Host ('Wrote ' + (Join-Path $data 'config\.env') + " with new secrets (the operator's, SYSTEM's and the Administrators' alone) and config\install.conf (the answers).")
    Stop-Log
    [IO.File]::WriteAllText($script:ResultFile, 'OK')
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
    if (-not ($archive -and (Test-Path -LiteralPath $archive))) { Fail 'Say which release to upgrade to: kilnmark.cmd upgrade kilnmark-<version>.zip' }
    $archive = (Resolve-Path $archive).Path
    if (Test-Elevated) { Fail 'Run upgrade from a prompt that is not elevated: it asks Windows for an administrator (UAC) only for the step that needs one.' }
    # the release runs as an administrator later: nobody else may have changed it (ADR 0051, decision 17)
    Assert-ReleaseItsOwn $archive 'upgrade'
    $old = Get-CurrentVersion
    $total = 5; if ($backup) { $total = 6 }
    Set-Steps $total
    Start-Step 'Check the new release'
    # An install from before deployment modes is never upgraded (ADR 0051): refused before anything
    # changes, by config\.env and by what the database recorded.
    Update-Settings
    $recorded = Get-RecordedMode
    if (-not $recorded) { Fail "Kilnmark's database has no deployment mode recorded (or does not answer: start it with kilnmark.cmd up first). An install from before deployment modes (release 0.1.1 or earlier, a preview) cannot be upgraded: install Kilnmark afresh. Nothing was changed." }
    if ($recorded -cne (Get-Setting 'KILNMARK_MODE')) { Fail 'KILNMARK_MODE in config\.env differs from the mode Kilnmark recorded at its first start: set it back. Nothing was changed.' }
    Start-Log 'upgrade'
    # The archive copied into a folder of yours alone first, with its signature: the copy is what is
    # checked and what is unpacked, so nothing can be put in its place between the two.
    $work = New-WorkFolder
    $name = Split-Path $archive -Leaf
    $copy = Join-Path $work $name
    try {
        Assert-Room $archive $work
        Copy-Item -LiteralPath $archive -Destination $copy -ErrorAction Stop
        if (Test-Path -LiteralPath ($archive + '.sigstore.json')) { Copy-Item -LiteralPath ($archive + '.sigstore.json') -Destination ($copy + '.sigstore.json') -ErrorAction Stop }
    } catch { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue; Assert-Room $archive $work; Fail ($name + ' could not be copied into a folder of yours to check it.') }
    $checked = $false
    try { Test-FileSignature $copy; $checked = $true } finally { if (-not $checked) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue } }
    $unpacked = Join-Path $work 'release'
    New-Item -ItemType Directory $unpacked | Out-Null
    & tar.exe -xf $copy -C $unpacked
    if ($LASTEXITCODE -ne 0) { Remove-Item -LiteralPath $work -Recurse -Force; Fail ($name + ' could not be unpacked.') }
    $src = (Get-ChildItem -LiteralPath $unpacked -Directory | Select-Object -First 1).FullName
    $new = Get-VersionOf $src
    if (-not $new) { Remove-Item -LiteralPath $work -Recurse -Force; Fail "Cannot tell the new release's version." }
    if ($new -eq $old) { Remove-Item -LiteralPath $work -Recurse -Force; Fail ('Kilnmark ' + $old + ' is already the running version.') }
    $vn = $null; $vo = $null
    if ([version]::TryParse($new, [ref]$vn) -and [version]::TryParse($old, [ref]$vo) -and $vn -lt $vo) {
        Remove-Item -LiteralPath $work -Recurse -Force; Fail ($new + ' is older than the running ' + $old + ': upgrade only goes forward (go back with restore).')
    }
    Complete-Step
    try {
        if ($backup) { Start-Step ('Back up Kilnmark ' + $old); Invoke-Backup @(); Complete-Step }
        Start-Step 'Get the images'
        if ($script:Offline) { Import-Images $script:Offline } elseif (-not (Test-ImagesPresent $new)) { Get-Images $new }
        if (-not (Test-ImagesPresent $new)) { Fail ('The images of Kilnmark ' + $new + ' are still missing after that.') }
        Complete-Step
        Start-Step ('Place Kilnmark ' + $new + ' and switch to it, as an administrator')
        Write-Host ('Windows now asks for an administrator (UAC): only administrators may write ' + $script:Root + '.')
        $result = Join-Path $work 'result.txt'
        $code = Invoke-Elevated (Join-Path $src 'kilnmark.ps1') @('_place-version', '-Source', $src, '-ProgramDir', $script:Root, '-Keep', $old, '-OperatorSid', [Security.Principal.WindowsIdentity]::GetCurrent().User.Value, '-Result', $result)
        Read-ElevatedResult $result $code
        Complete-Step
    } finally { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
    Copy-Item -LiteralPath (Join-Path (Join-Path (Join-Path $script:Root 'versions') $new) '.env.example') -Destination (Join-Path $script:Data 'config\.env.example') -Force
    $added = Get-NewSettings (Join-Path $script:Data 'config\.env.example')
    Start-Step ('Start Kilnmark ' + $new)
    $shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    & $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $script:Root 'kilnmark.ps1') up -NoBrowser
    if ($LASTEXITCODE -ne 0) {
        Write-Host ('Kilnmark ' + $new + ' did not start: stopping it and starting ' + $old + ' again.') -ForegroundColor Red
        [void](Invoke-Compose down)
        $code = Invoke-Elevated (Join-Path $script:Root 'kilnmark.ps1') @('_use-version', '-Version', $old, '-ProgramDir', $script:Root)
        & $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $script:Root 'kilnmark.ps1') up -NoBrowser
        if ($LASTEXITCODE -eq 0) { Fail ('Kilnmark ' + $new + ' did not start, so ' + $old + ' runs again. See why ' + $new + ' did not start in ' + (Join-Path $script:Data 'logs') + '.') }
        Fail ('Kilnmark ' + $old + ' does not start either: ' + $new + ' may have changed its database already. Put it back to the backup taken above: kilnmark.cmd restore <that backup> -Yes')
    }
    Complete-Step
    if ($added.Count -gt 0) { Write-Host ('Settings new in ' + $new + ' (see config\.env.example): ' + ($added -join ' ')) }
    Write-Host ('Upgraded Kilnmark from ' + $old + ' to ' + $new + '.')
    Stop-Log
}

# upgrade's part as an administrator: the new release copied into the program folder, the launchers
# and current.txt switched to it, and the versions but the two newest removed.
function Invoke-PlaceVersion([string[]]$Options) {
    $src = $null; $keep = $null; $operatorSid = $null
    for ($i = 0; $i -lt $Options.Count; $i++) {
        switch ($Options[$i]) {
            '-Source' { $i++; $src = $Options[$i] }
            '-ProgramDir' { $i++; $script:Root = $Options[$i] }
            '-Keep' { $i++; $keep = $Options[$i] }
            '-OperatorSid' { $i++; $operatorSid = $Options[$i] }
            '-Result' { $i++; $script:ResultFile = $Options[$i] }
            default { Fail ('Unknown option ' + $Options[$i] + '.') }
        }
    }
    if (-not (Test-Elevated) -and -not (Test-TestHooks)) { Fail 'This part of upgrade needs an administrator.' }
    if (-not (Test-UserSid $operatorSid)) { Fail 'The operator was not named (a user''s SID, which Windows knows as a user, never a group).' }
    # the new release, checked again as an administrator before anything is copied
    $script:AlsoTrusted = @($operatorSid)
    $why = Get-ReleaseProblem $src
    if ($why) { Fail ('The new release was refused: ' + $why + '. Nothing was changed.') }
    $new = Get-VersionOf $src
    if (-not $new -or $new -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._+-]*$') { Fail "Cannot tell the new release's version." }
    $versions = Join-Path $script:Root 'versions'
    $dest = Join-Path $versions $new
    if (-not (Test-Path -LiteralPath $dest)) { Copy-Item -LiteralPath $src -Destination $dest -Recurse }
    Copy-Launchers $new
    $all = Get-ChildItem -LiteralPath $versions -Directory | Where-Object { -not $_.Name.StartsWith('.') } | Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } }
    foreach ($d in @($all | Select-Object -SkipLast 2)) { if ($d.Name -ne $new -and $d.Name -ne $keep) { Remove-Item -LiteralPath $d.FullName -Recurse -Force } }
    [IO.File]::WriteAllText($script:ResultFile, 'OK')
}

function Invoke-UseVersion([string[]]$Options) {
    $v = $null
    for ($i = 0; $i -lt $Options.Count; $i++) {
        switch ($Options[$i]) { '-Version' { $i++; $v = $Options[$i] } '-ProgramDir' { $i++; $script:Root = $Options[$i] } default { Fail ('Unknown option ' + $Options[$i] + '.') } }
    }
    if (-not (Test-Elevated) -and -not (Test-TestHooks)) { Fail 'This part of upgrade needs an administrator.' }
    if ($v -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._+-]*$' -or -not (Test-Path -LiteralPath (Join-Path (Join-Path $script:Root 'versions') $v))) { Fail ('Kilnmark ' + $v + ' is not in ' + $script:Root + '.') }
    Copy-Launchers $v
}

function Invoke-Uninstall([string[]]$Options) {
    $purge = $false
    foreach ($o in $Options) { if ($o -eq '-Purge') { $purge = $true } else { Fail ('Unknown option ' + $o + '.') } }
    [void](Get-CurrentVersion)
    Assert-Operator 'uninstall'
    if ($purge) {
        Write-Host ("This deletes all of Kilnmark's data: its database, the key to stored passwords, Keycloak's, and " + $script:Data + ' with every backup in it.')
        Write-Host 'Back up first, somewhere else, if you might want any of it. Type DELETE to go on:'
        if ([Console]::IsInputRedirected) { $answer = [Console]::In.ReadLine(); if ($null -eq $answer) { $answer = '' }; $answer = $answer.Trim() } else { $answer = Read-Host }
        if ($answer -cne 'DELETE') { Fail 'Nothing was deleted.' }
    }
    $total = 2
    Set-Steps $total
    Start-Step 'Stop Kilnmark and remove its hourly task'
    Invoke-StopForUninstall $purge
    Complete-Step
    $what = $script:Root; if ($purge) { $what = $script:Root + ' and ' + $script:Data }
    Start-Step ('Remove ' + $what + ', as an administrator')
    Write-Host ('Windows now asks for an administrator (UAC): only administrators may remove ' + $what + '.')
    # run from a copy, since the program folder it removes holds this script
    $work = New-WorkFolder
    try {
        $copy = Join-Path $work 'kilnmark.ps1'
        Copy-Item -LiteralPath (Join-Path $script:Root 'kilnmark.ps1') -Destination $copy
        $result = Join-Path $work 'result.txt'
        $arguments = @('_uninstall-elevated', '-ProgramDir', $script:Root, '-DataDir', $script:Data, '-Result', $result)
        if ($purge) { $arguments += '-Purge' }
        Set-Location ([IO.Path]::GetTempPath())
        $code = Invoke-Elevated $copy $arguments
        Read-ElevatedResult $result $code
    } finally { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
    Complete-Step
    if ($purge) { Write-Host 'Kilnmark is uninstalled, and its data deleted.' }
    else { Write-Host ('Kilnmark is uninstalled. Its data (volumes) and ' + $script:Data + ' (settings, backups, logs) are kept: uninstall -Purge deletes them, or install afresh with another data folder.') }
}

# The operator's part of uninstall: no task, nothing running; with -Purge, no volumes nor images.
function Invoke-StopForUninstall([bool]$Purge) {
    $v = Get-CurrentVersion
    Assert-Project
    Remove-ExpiryTask
    if (-not $Purge) {
        [void](Invoke-ComposeQuietly down)
        Write-Host 'Kilnmark is stopped and will not start again by itself. Its data is kept.'
        return
    }
    [void](Invoke-Compose --profile auth --profile ai down -v)
    # An image another install on this machine still runs is kept: removing it, or its name, would
    # stop that install starting again.
    $kept = @()
    foreach ($i in (Get-ImageNames $v)) {
        $users = (& $script:Engine ps -a --filter ('ancestor=' + $i) --format '{{.Names}}' 2> $null | Out-String).Trim()
        if ($users) { $kept += $i } else { & $script:Engine rmi $i *> $null }
    }
    if ($kept.Count -gt 0) { Write-Host ('Kept the images ' + ($kept -join ' ') + ': other containers on this machine use them (another install, say).') }
    Write-Host "Kilnmark's containers, volumes and images are deleted."
}

# uninstall's part as an administrator: the program folder goes, and with -Purge the data folder.
function Invoke-UninstallElevated([string[]]$Options) {
    $prog = $null; $data = $null; $purge = $false
    for ($i = 0; $i -lt $Options.Count; $i++) {
        switch ($Options[$i]) {
            '-ProgramDir' { $i++; $prog = $Options[$i] }
            '-DataDir' { $i++; $data = $Options[$i] }
            '-Purge' { $purge = $true }
            '-Result' { $i++; $script:ResultFile = $Options[$i] }
            default { Fail ('Unknown option ' + $Options[$i] + '.') }
        }
    }
    if (-not (Test-Elevated) -and -not (Test-TestHooks)) { Fail 'This part of uninstall needs an administrator.' }
    # only a Kilnmark program folder: it names this data folder, and holds versions\
    $named = ''
    try { $named = ([IO.File]::ReadAllText((Join-Path $prog 'data-folder.txt'))).Trim() } catch { $named = '' }
    if (-not (Test-Path -LiteralPath (Join-Path $prog 'versions')) -or $named -ne $data) { Fail ($prog + ' is not the Kilnmark install that names ' + $data + ': nothing was removed.') }
    try { Remove-Item -LiteralPath $prog -Recurse -Force -ErrorAction Stop } catch { Fail ($prog + ' could not be removed (is a file in it open?).') }
    if ($purge) { try { Remove-Item -LiteralPath $data -Recurse -Force -ErrorAction Stop } catch { Fail ($data + ' could not be removed (is a file in it open?).') } }
    [IO.File]::WriteAllText($script:ResultFile, 'OK')
}

# --- configure: change an answer later (ADR 0051, decision 17) --------------------------------

function Invoke-Configure([string[]]$Options) {
    [void](Get-CurrentVersion)
    if (-not (Read-InstalledAnswers)) { Fail ((Join-Path $script:Data 'config\install.conf') + ", the install's answers, is missing, so configure cannot tell what install chose. Nothing was changed.") }
    $kc = Get-SelfCall $script:Root
    if ($Options.Count -eq 0) {
        Write-Host ("This install's answers (" + (Join-Path $script:Data 'config\install.conf') + '):')
        Show-Answers
        Write-Host ('Change one with: ' + $kc + ' configure KEY=VALUE (install.conf.example says each).')
        return
    }
    $identityWas = Get-Answer 'identity'
    $changed = @()
    # running is judged by the settings it runs with now, before they change
    $running = Test-Answers ((Get-LocalUrl) + '/q/health/ready')
    Set-Steps 3
    Start-Step 'Check the new answers'
    foreach ($a in $Options) {
        if ($a -cnotmatch '^([a-z_]+)=(.*)$') { Fail 'Say each change as KEY=VALUE, such as port=9780.' }
        $k = $Matches[1]; $v = $Matches[2].Trim()
        if ($k -match '(password|secret|token)') { Fail ($k + ' is a secret: configure asks for one when an answer needs it, and none is given on a command line.') }
        if (-not (Test-KnownKey $k)) { Fail ($k + ' is not an answer install knows (install.conf.example lists them).') }
        if ($v -match '[$`]') { Fail ($k + ' may not hold $ or `, which compose would read as the start of another setting''s name. Nothing was changed.') }
        if (($FixedKeys -ccontains $k) -and $v -cne (Get-Answer $k)) {
            if ($k -ceq 'mode') { Fail "An install's mode never changes (ADR 0051): for the other mode, install Kilnmark afresh. Nothing was changed." }
            Fail ($k + ' is fixed at install: changing it needs a new install. Nothing was changed.')
        }
        if ($v -ceq (Get-Answer $k)) { continue }
        $script:Answers[$k] = $v
        $changed += $k
    }
    if ($changed.Count -eq 0) { Complete-Step; Write-Host 'Nothing changed: those are the answers already.'; return }
    foreach ($k in $changed) {
        if (-not (Test-Relevant $k)) { Fail ($k + ' means nothing with this install''s other answers (with identity=' + (Get-Answer 'identity') + ' and tls=' + (Get-Answer 'tls') + '). Nothing was changed.') }
        $n = 0
        if ($k.EndsWith('port') -and [int]::TryParse((Get-Answer $k), [ref]$n) -and (Test-PortBusy $n)) { Fail ($k + ': port ' + $n + ' is in use (port ' + (Get-FreePort $n) + ' is free). Nothing was changed.') }
    }
    # the certificate's files are checked, and copied, only when the change is to them
    $certs = ($changed -ccontains 'tls' -or $changed -ccontains 'tls_cert' -or $changed -ccontains 'tls_key')
    if (-not $certs) { $script:SkipKeys = @('tls_cert', 'tls_key') }
    # the first admin, made at install, is checked again only when the change is to them
    if (@($changed | Where-Object { $_.StartsWith('admin_') }).Count -eq 0) { $script:SkipKeys += @('admin_email', 'admin_first_name', 'admin_last_name') }
    if (-not (Test-AllAnswers)) { Fail 'Some answers cannot be taken (above), so nothing was changed.' }
    if ((Get-Answer 'identity') -ceq 'own' -and ($identityWas -cne 'own' -or $changed -ccontains 'oidc_client_id')) { Read-ClientSecret }
    $restart = $false; $person = $false
    foreach ($k in $changed) {
        if ($k -ceq 'email_domain') { continue }
        if ($k -cin @('admin_email', 'admin_first_name', 'admin_last_name')) { $person = $true } else { $restart = $true }
    }
    if ($person) {
        if (-not (Get-Answer 'admin_email')) { Fail 'admin_email is needed to add a first admin. Nothing was changed.' }
        Read-InstallSecrets
    }
    Complete-Step
    Start-Step 'Write the settings'
    if ($certs) { Copy-Certs }
    if ($identityWas -cne (Get-Answer 'identity') -and (Get-Answer 'identity') -ceq 'bundled') { Write-Env -NewClient } else { Write-Env }
    Write-Answers (Join-Path $script:Data 'config\install.conf')
    Write-Host ('Wrote config\.env and config\install.conf: ' + ($changed -join ' ') + '.')
    Complete-Step
    Start-Step 'Run again what the change affects'
    if ($restart) {
        if ($running) { Write-Host 'Restarting Kilnmark, so every container takes the new settings ...'; Invoke-Down; Invoke-Up @('-NoBrowser') }
        else { Write-Host ('Kilnmark is not running: the change takes effect at its next start (' + $kc + ' up).') }
    }
    if ($person) {
        if (-not $running) { Fail ('Kilnmark is not running, so the first admin was not added: start it (' + $kc + ' up), then run configure again.') }
        $p = @{ Email = (Get-Answer 'admin_email'); First = (Get-Answer 'admin_first_name'); Last = (Get-Answer 'admin_last_name'); Groups = 'kilnmark-admins'; Password = $script:AdminPassword }
        $script:AdminPassword = ''
        if (-not (New-Person $p)) { Fail 'The first admin was not added (above).' }
    }
    if (-not $restart -and -not $person) { Write-Host ('Nothing to run again: ' + ($changed -join ' ') + ' takes effect as it is.') }
    Complete-Step
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
# does: Kilnmark does the work. Through Invoke-KilnmarkApi, as every request with the admin token:
# only to a listener of yours, at 127.0.0.1, the token in a file, never on a command line.
function Invoke-StarterOn {
    if (-not (Wait-OneOff 'samples-db')) { Write-Host 'Warning: samples-db did not finish well, so the Starter Pack may not find its database. See: kilnmark.cmd logs samples-db' -ForegroundColor Yellow }
    Write-Host 'Turning the Starter Pack on ...'
    $work = New-WorkFolder
    $answerFile = Join-Path $work 'answer.json'
    $text = ''
    try {
        $code = Invoke-KilnmarkApi 'POST' '/api/v1/starter-pack' '' $answerFile
        if (Test-Path -LiteralPath $answerFile) { $text = [IO.File]::ReadAllText($answerFile) }
    } finally {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
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
    $lines = @(& $script:Engine logs $id 2>&1 | ForEach-Object { ConvertTo-Said $_ } | Where-Object { -not (Test-ComposeCommand $_) })
    # Only its latest run: it says "started" first.
    $start = 0
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i].StartsWith('keycloak-setup: started')) { $start = $i } }
    $latest = @($lines[$start..($lines.Count - 1)])
    $refusal = @($latest | Where-Object { $_ -cmatch "^keycloak-setup: this Keycloak's realm .* is not this install's" })
    if ($refusal.Count -gt 0) {
        Write-Host $refusal[0].Substring(16) -ForegroundColor Red
        [void](Invoke-ComposeQuietly down)
        Fail "keycloak-setup refused this Keycloak's realm (above), so Kilnmark was stopped again: no sign-in goes to a realm that is not this install's."
    }
    Send-OwnPeopleDone $latest 'expire'
    # anyone the pack's removal only took out of its group, not one of the example people
    foreach ($line in $latest) { if ($line.StartsWith('keycloak-setup: took ')) { Write-Host $line.Substring(16) } }
    if (Test-StarterMode) { Send-PeopleDone $latest }
}

# A request to Kilnmark's own API as the admin: the token reaches curl in a settings file in a new
# folder only this account can open, deleted at once, never on a command line. The HTTP code.
# Why something other than yours may be answering on Kilnmark's port, or nothing (ADR 0051): every
# listener Windows shows on it (loopback or all addresses) must be a program of yours. One Windows
# will not name the owner of (another account's, or a service's) counts against it. A container
# engine whose ports Windows does not show (WSL's mirrored networking) leaves nothing to check.
function Get-ListenerProblem([int]$Port) {
    $me = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $found = @()
    try { $found = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop) }
    catch {
        # none listening is ObjectNotFound; any other error leaves who listens untold
        if ($_.CategoryInfo.Category -ne [Management.Automation.ErrorCategory]::ObjectNotFound) {
            return ('who listens on port ' + $Port + ' cannot be told here (Windows did not answer)')
        }
    }
    foreach ($c in $found) {
        if (@('127.0.0.1', '0.0.0.0', '::', '::1') -notcontains ('' + $c.LocalAddress)) { continue }
        $o = $null
        try {
            $proc = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $c.OwningProcess)
            if ($proc) { $o = Invoke-CimMethod -InputObject $proc -MethodName GetOwner }
        } catch { $o = $null }
        if (-not $o -or $o.ReturnValue -ne 0 -or (($o.Domain + '\' + $o.User) -ne $me)) {
            return ('a program that is not yours (or whose owner Windows will not name) listens on port ' + $Port + ', where Kilnmark should')
        }
    }
    return ''
}

# A request to Kilnmark's own API as the admin. The admin token goes only to a listener of yours
# (Get-ListenerProblem), at 127.0.0.1; else nothing is sent and the answer is 000.
function Invoke-KilnmarkApi([string]$Method, [string]$Path, [string]$Body, [string]$AnswerFile = 'NUL') {
    $url = [uri](Get-LocalUrl)
    $why = Get-ListenerProblem $url.Port
    if ($why) {
        Write-Host ('Warning: ' + $why + ': the admin token was not sent.') -ForegroundColor Yellow
        return '000'
    }
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
        # -k for Kilnmark's own certificate, whose name need not be localhost: the listener was checked above
        $at = $url.Scheme + '://127.0.0.1:' + $url.Port + $Path
        return (& curl.exe -k -s -K $configFile -X $Method --max-time 600 -o $AnswerFile --data-binary ('@' + $bodyFile) -w '%{http_code}' $at | Out-String).Trim()
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
    if (-not $profiles.Contains(',auth,')) { Fail 'The example people need the bundled Keycloak: the auth profile (docs/guide/install/production-bundled-keycloak.md).' }
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
        $out = @($password | & $script:Engine @base run --rm --no-deps -T keycloak-setup 2>&1 | ForEach-Object { ConvertTo-Said $_ } |
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
        Write-Host ($Message + ' Add them later with: ' + (Get-SelfCall $script:Root) + ' people add') -ForegroundColor Yellow
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
            Write-Host ('No first admin was made. Add people later with ' + (Get-SelfCall $script:Root) + ' people add (or people import, or Keycloak''s console: docs/guide/install/production-bundled-keycloak.md).')
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
        $out = @($Lines | & $script:Engine @base run --rm --no-deps -T keycloak-setup 2>&1 | ForEach-Object { ConvertTo-Said $_ } |
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
    $unrecorded = $false
    foreach ($b in $bodies) {
        $code = Invoke-KilnmarkApi 'POST' '/api/v1/auth/people' $b
        if ($code -ne '204') {
            Write-Host ("Warning: Kilnmark did not record this in its audit log (HTTP " + $code + "); Keycloak's admin events have it.") -ForegroundColor Yellow
            if ($b.StartsWith('{"via":"add"') -or $b.StartsWith('{"via":"import"') -or $b.StartsWith('{"via":"activate"')) { $unrecorded = $true }
        }
    }
    # Those given a temporary password just now wait, disabled, for that record: enabled only once
    # Kilnmark has it (fail closed), so a run cut short leaves nobody able to sign in unrecorded.
    $todo = @()
    foreach ($line in $Lines) {
        if ($line -cmatch '^keycloak-setup: made ([a-z0-9._%+@-]+) \([a-z;-]+\): they choose their own password') { $todo += $Matches[1] }
        elseif ($line -cmatch '^keycloak-setup: activated ([a-z0-9._%+@-]+): they choose their own password') { $todo += $Matches[1] }
    }
    if ($todo.Count -eq 0) { return }
    if ($unrecorded) {
        Write-Host 'So they stay disabled, until Kilnmark answers: then give each a temporary password with people activate EMAIL.' -ForegroundColor Yellow
        return
    }
    $out = Invoke-PeopleSetup 'enable' $null $todo
    $left = @($todo | Where-Object { $out -cnotcontains ('keycloak-setup: enabled ' + $_) })
    if ($left.Count -gt 0) { Write-Host ('Warning: recorded, but not enabled: ' + ($left -join ' ') + '. Give each a temporary password with people activate EMAIL.') -ForegroundColor Yellow }
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
                Write-Host ('Each person made waits, disabled, until you give them a temporary password of their own: ' + (Get-SelfCall $script:Root) + ' people activate EMAIL')
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
            # The tree it runs from was checked first (Assert-ExpireTree).
            # Nothing to do while Keycloak is stopped: nobody signs in then, and each start expires them first.
            $kc = Get-Setting 'KILNMARK_KEYCLOAK_PORT'; if (-not $kc) { $kc = '8180' }
            # only a refused connection is "not running": anything else answering there goes on to
            # setup, which checks the realm itself
            & curl.exe -s -o NUL --max-time 10 ('http://127.0.0.1:' + $kc + '/realms/kilnmark') *> $null
            if ($LASTEXITCODE -eq 7) {
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
    $dest = Join-Path $script:Data 'backups'
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
        Fail 'Say which backup to restore, a folder made by backup: kilnmark.cmd restore C:\ProgramData\Kilnmark\backups\kilnmark-<time> -Yes'
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
    $stage = Join-Path $script:Data ('backups\.restore-' + $stamp)
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
    Write-Host ('  - once all is well, drop the previous database: ' + (Get-SelfCall $script:Root) + ' compose exec postgres dropdb -U kilnmark ' + $before)
    if ($restoreKeycloak) {
        Write-Host '  - Keycloak is back to the backup too: people added or changed since then are gone, and anyone'
        Write-Host '    removed since is back (remove them again). Then drop its previous database:'
        Write-Host ('    ' + (Get-SelfCall $script:Root) + ' compose exec keycloak-db dropdb -U keycloak ' + $kcBefore)
    }
}

# --- main --------------------------------------------------------------------------------------

# Day-to-day commands run as the operator, from a prompt that is not elevated.
if ($Command -notin @('install', 'upgrade', 'uninstall', 'check', 'version', 'help', '-h', '--help', '/?', '_install-elevated', '_place-version', '_use-version', '_uninstall-elevated')) {
    Assert-Operator $Command
}
switch ($Command) {
    'install' { Invoke-Install $Rest }
    'upgrade' { Invoke-Upgrade $Rest }
    'uninstall' { Invoke-Uninstall $Rest }
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
    'check' { Invoke-Check $Rest }
    # compose as this install runs it (its project, settings, secrets and profiles), for a command
    # by hand: kilnmark.cmd compose ps, logs, exec, ...
    'compose' {
        [void](Get-CurrentVersion)
        $base = Get-ComposeArgs
        & $script:Engine @base @Rest
        exit $LASTEXITCODE
    }
    'configure' { Start-Log 'configure'; Invoke-Configure $Rest; Stop-Log }
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
    'people' { if ($Rest.Count -gt 0 -and $Rest[0] -eq 'expire') { Assert-ExpireTree; Start-Log 'people-expire' -Daily } else { Start-Log 'people' }; Invoke-People $Rest; Stop-Log }
    'restore' { Start-Log 'restore'; Invoke-Restore $Rest; Stop-Log }
    # the parts of install, upgrade and uninstall that run as an administrator, and the operator's
    # part of uninstall
    '_install-elevated' { Invoke-InstallElevated $Rest }
    '_place-version' { Invoke-PlaceVersion $Rest }
    '_use-version' { Invoke-UseVersion $Rest }
    '_uninstall-elevated' { Invoke-UninstallElevated $Rest }
    '_stop-for-uninstall' { Start-Log 'uninstall'; Invoke-StopForUninstall ($Rest -contains '-Purge'); Stop-Log }
    'version' { if ($script:Root -and $script:Data) { Write-Host ('Kilnmark ' + (Get-CurrentVersion)) } else { Write-Host ('Kilnmark ' + (Get-VersionOf $Here) + ' (not installed)') } }
    { $_ -in @('help', '-h', '--help', '/?') } { Show-Usage }
    default { Show-Usage; exit 2 }
}
exit 0
