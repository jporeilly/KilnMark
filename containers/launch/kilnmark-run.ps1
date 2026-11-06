# kilnmark-run: start a Kilnmark pipeline or flow from a scheduler (Control-M, AutoSys, IBM Workload
# Automation, Windows Task Scheduler) and wait for it to end, with an exit code the scheduler can act
# on (docs/guide/external-schedulers.md, ADR 0047). Windows PowerShell 5.1 or later:
#
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File kilnmark-run.ps1 pipeline|flow NAME
#       [--param NAME=VALUE]... [--version N] [--timeout 2h] [--key REQUEST_KEY] [--cancel-on-timeout]
#
# Settings, from the environment:
#   KILNMARK_URL         Kilnmark's address, for example https://kilnmark.example.com:8443
#   KILNMARK_TOKEN_FILE  a file holding a steward API token, readable only by the job's account.
#                        Never on the command line or in an environment value: process lists and
#                        job logs show those.
#   KILNMARK_CA_FILE     optional: the private CA that signed Kilnmark's certificate (PEM or DER).
#
# Exit codes: 0 succeeded, 1 failed, 2 could not start (refused), 3 timed out waiting, 4 cancelled,
# 5 could not reach the server, or bad usage. The token is never printed. This file is ASCII only:
# Windows PowerShell 5.1 misreads anything else.

$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 writes a list as {"value": [...], "Count": n} unless this type data goes.
if ($PSVersionTable.PSVersion.Major -le 5) { Remove-TypeData -TypeName System.Array -ErrorAction SilentlyContinue }
$Me = 'kilnmark-run'
$ExOk = 0; $ExFailed = 1; $ExRefused = 2; $ExTimeout = 3; $ExCancelled = 4; $ExUnreachable = 5
# The longest one request asks to wait, in seconds; the server may cut it shorter (kilnmark.api.max-wait).
$Chunk = 900
# Tries in a row before a server that cannot be reached counts as gone.
$Tries = 5

function Say([string]$Text) { [Console]::Out.WriteLine($Text) }
function Err([string]$Text) { [Console]::Error.WriteLine("${Me}: $Text") }

function Show-Usage {
    [Console]::Error.WriteLine(@'
Usage: kilnmark-run.ps1 pipeline|flow NAME [--param NAME=VALUE]... [--version N] [--timeout 2h]
                        [--key REQUEST_KEY] [--cancel-on-timeout]

Starts a run of the pipeline or flow, waits until it ends, and exits with:
  0 succeeded, 1 failed, 2 could not start, 3 timed out waiting, 4 cancelled,
  5 could not reach the server or bad usage.

--param NAME=VALUE   A value for one of its parameters. A number, true or false is sent as
                     such; [..] as a JSON list; "..." as text; anything else as text.
--version N          Run version N (the latest by default).
--timeout DURATION   Stop waiting after this long: 90 (seconds), 30s, 10m, 2h or 1d. The run is
                     left going unless --cancel-on-timeout. No limit by default.
--key REQUEST_KEY    The request key (1 to 200 printable characters). A retry with the same key
                     within 24 hours gets the run the first try started, never a second one.
                     Made up and printed when left out.
--cancel-on-timeout  Cancel the run when the timeout is reached.

Reads KILNMARK_URL, KILNMARK_TOKEN_FILE (a file holding the API token) and, optionally,
KILNMARK_CA_FILE (the private CA that signed Kilnmark's certificate).
'@)
    exit $ExUnreachable
}

function Stop-Usage([string]$Text) { Err $Text; exit $ExUnreachable }

# A duration in seconds: 90, 30s, 10m, 2h or 1d; -1 when it is none of those.
function ConvertTo-Seconds([string]$Text) {
    if ($Text -notmatch '^([0-9]{1,9})([smhd]?)$') { return -1 }
    $n = [long]$Matches[1]
    switch ($Matches[2]) {
        'd' { return $n * 86400 }
        'h' { return $n * 3600 }
        'm' { return $n * 60 }
        default { return $n }
    }
}

# A parameter's value: a number, true or false as such; [..] and "..." read as JSON; else text.
function ConvertTo-Value([string]$Text) {
    if ($Text -ceq 'true') { return $true }
    if ($Text -ceq 'false') { return $false }
    if ($Text -match '^-?[0-9]+(\.[0-9]+)?$') { return [double]::Parse($Text, [Globalization.CultureInfo]::InvariantCulture) }
    if (($Text.StartsWith('[') -and $Text.EndsWith(']')) -or ($Text.Length -ge 2 -and $Text.StartsWith('"') -and $Text.EndsWith('"'))) {
        try { $v = ConvertFrom-Json $Text } catch { Stop-Usage "a --param value written as [..] or `"..`" is not valid JSON" }
        # a list is handed back whole: PowerShell would otherwise unroll it, and a list of one into its item
        if ($v -is [array]) { return , $v }
        return $v
    }
    if ($Text -match '[\x00-\x1f]') { Stop-Usage 'a value holds a line break or a tab; leave them out' }
    return $Text
}

# ---- arguments ----

if ($args.Count -lt 2) { Show-Usage }
$Kind = [string]$args[0]
$Name = [string]$args[1]
if ($Kind -cne 'pipeline' -and $Kind -cne 'flow') { Err 'the first argument is pipeline or flow'; Show-Usage }
if ($Name -cnotmatch '^[a-z0-9-]+$') { Stop-Usage 'a name is small letters, digits and dashes' }

$Params = [ordered]@{}
$Version = $null
$Timeout = $null
$Key = $null
$Cancel = $false
$i = 2
while ($i -lt $args.Count) {
    $a = [string]$args[$i]
    $hasValue = ($i + 1) -lt $args.Count
    switch -CaseSensitive ($a) {
        '--param' {
            if (-not $hasValue) { Show-Usage }
            $pair = [string]$args[$i + 1]
            $eq = $pair.IndexOf('=')
            if ($eq -lt 1) { Stop-Usage '--param takes NAME=VALUE' }
            $Params[$pair.Substring(0, $eq)] = ConvertTo-Value $pair.Substring($eq + 1)
            $i += 2
        }
        '--version' {
            if (-not $hasValue) { Show-Usage }
            if ([string]$args[$i + 1] -notmatch '^[0-9]{1,9}$') { Stop-Usage '--version takes a whole number' }
            $Version = [int]$args[$i + 1]
            $i += 2
        }
        '--timeout' {
            if (-not $hasValue) { Show-Usage }
            $Timeout = ConvertTo-Seconds ([string]$args[$i + 1])
            if ($Timeout -lt 0) { Stop-Usage '--timeout takes a duration such as 90, 30s, 10m, 2h or 1d' }
            $i += 2
        }
        '--key' {
            if (-not $hasValue) { Show-Usage }
            $Key = [string]$args[$i + 1]
            if ($Key.Length -lt 1 -or $Key.Length -gt 200 -or $Key -cmatch '[^\x20-\x7e]' -or $Key.Trim().Length -eq 0) {
                Stop-Usage '--key takes 1 to 200 printable characters'
            }
            $i += 2
        }
        '--cancel-on-timeout' { $Cancel = $true; $i += 1 }
        '--help' { Show-Usage }
        '-h' { Show-Usage }
        default { Err "unknown option: $a"; Show-Usage }
    }
}
if (-not $Key) {
    $Key = $Me + '-' + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 16)
}

# ---- settings ----

$Base = [string]$env:KILNMARK_URL
$Base = $Base.TrimEnd('/')
if ($Base -notmatch '^https://.' -and $Base -notmatch '^http://(127\.0\.0\.1|localhost|\[::1\])(:[0-9]+)?$') {
    if ($Base -match '^http://') {
        Stop-Usage 'KILNMARK_URL must start with https:// (plain http only to this machine): the token would cross the network unprotected'
    }
    Stop-Usage "set KILNMARK_URL to Kilnmark's address, for example https://kilnmark.example.com:8443"
}

$TokenFile = [string]$env:KILNMARK_TOKEN_FILE
if (-not $TokenFile -or -not (Test-Path -LiteralPath $TokenFile -PathType Leaf)) {
    Stop-Usage 'set KILNMARK_TOKEN_FILE to a file holding a steward API token, readable by this account'
}
try { $Token = ([IO.File]::ReadAllText($TokenFile)) -replace '\s', '' } catch { Stop-Usage 'could not read the file KILNMARK_TOKEN_FILE names' }
if ($Token -cnotmatch '^km_[A-Za-z0-9_-]+$') { Stop-Usage 'the file KILNMARK_TOKEN_FILE names does not hold a Kilnmark API token (km_...)' }
$Auth = @{ Authorization = "Bearer $Token" }
$Token = $null

# TLS 1.2 or later: Windows PowerShell 5.1 may offer older versions by default.
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch { }

# A private CA: Windows checks the certificate as always; a chain Windows cannot complete is accepted
# only when it ends at this CA and has no other fault. The address must still match the certificate.
if ($env:KILNMARK_CA_FILE) {
    if ($PSVersionTable.PSVersion.Major -gt 5) {
        Stop-Usage 'KILNMARK_CA_FILE works with Windows PowerShell 5.1 (powershell.exe); in PowerShell 7, import the CA into the Trusted Root store instead'
    }
    if (-not (Test-Path -LiteralPath $env:KILNMARK_CA_FILE -PathType Leaf)) { Stop-Usage 'KILNMARK_CA_FILE names a file this account cannot read' }
    Add-Type -TypeDefinition @'
using System;
using System.Net;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
public static class KilnmarkRunCa {
    static X509Certificate2 ca;
    public static void Trust(string path) {
        ca = new X509Certificate2(path);
        ServicePointManager.ServerCertificateValidationCallback = Check;
    }
    static bool Check(object sender, X509Certificate cert, X509Chain given, SslPolicyErrors errors) {
        if (errors == SslPolicyErrors.None) return true;
        if (cert == null || (errors & ~SslPolicyErrors.RemoteCertificateChainErrors) != 0) return false;
        X509Chain chain = new X509Chain();
        chain.ChainPolicy.ExtraStore.Add(ca);
        chain.ChainPolicy.RevocationMode = X509RevocationMode.Online;
        chain.ChainPolicy.VerificationFlags = X509VerificationFlags.AllowUnknownCertificateAuthority
            | X509VerificationFlags.IgnoreEndRevocationUnknown
            | X509VerificationFlags.IgnoreCertificateAuthorityRevocationUnknown;
        if (!chain.Build(new X509Certificate2(cert))) return false;
        X509Certificate2 root = chain.ChainElements[chain.ChainElements.Count - 1].Certificate;
        if (root.Thumbprint != ca.Thumbprint) return false;
        foreach (X509ChainStatus s in chain.ChainStatus) {
            if (s.Status != X509ChainStatusFlags.NoError && s.Status != X509ChainStatusFlags.UntrustedRoot
                && s.Status != X509ChainStatusFlags.RevocationStatusUnknown
                && s.Status != X509ChainStatusFlags.OfflineRevocation) return false;
        }
        return true;
    }
}
'@
    [KilnmarkRunCa]::Trust((Resolve-Path -LiteralPath $env:KILNMARK_CA_FILE).ProviderPath)
}

# ---- the API ----

# Calls Kilnmark: returns @{ Code = HTTP status (0 when the server could not be reached); Run = the
# run read from JSON, or $null; Error = the refusal's words; RetryAfter = seconds; Replayed = bool }.
function Invoke-Kilnmark([string]$Method, [string]$Path, [int]$MaxSeconds, $Body) {
    $r = @{ Code = 0; Run = $null; Error = ''; RetryAfter = 5; Replayed = $false }
    $headers = @{}
    foreach ($k in $Auth.Keys) { $headers[$k] = $Auth[$k] }
    $call = @{ Method = $Method; Uri = "$Base$Path"; Headers = $headers; TimeoutSec = $MaxSeconds; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $headers['Idempotency-Key'] = $Key
        $call['ContentType'] = 'application/json; charset=utf-8'
        $call['Body'] = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 6 -Compress))
    }
    try {
        $resp = Invoke-WebRequest @call
        $r.Code = [int]$resp.StatusCode
        $r.Run = ConvertFrom-Json ([string]$resp.Content)
        if ($resp.Headers['Idempotent-Replayed'] -eq 'true') { $r.Replayed = $true }
    } catch {
        $resp = $_.Exception.Response
        if ($null -eq $resp) { $r.Error = $_.Exception.Message; return $r }
        $r.Code = [int]$resp.StatusCode
        $text = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $text = $_.ErrorDetails.Message }
        try { if ($text) { $r.Error = [string](ConvertFrom-Json $text).error } } catch { }
        if (-not $r.Error) { $r.Error = "HTTP $($r.Code)" }
        try {
            $after = $resp.Headers['Retry-After']
            if ($after -match '^[0-9]+$') { $r.RetryAfter = [int]$after }
        } catch { }
    }
    return $r
}

$Start = Get-Date
# How long the next wait may be: the chunk, or what is left before the timeout (0 when it is up).
function Get-NextWait {
    if ($null -eq $Timeout) { return $Chunk }
    $left = [int][Math]::Floor($Timeout - ((Get-Date) - $Start).TotalSeconds)
    if ($left -le 0) { return 0 }
    return [Math]::Min($left, $Chunk)
}

function Complete-Run($Run) {
    Say "Status: $($Run.status)"
    if ($Run.error) { Say ("Message: " + ([string]$Run.error -replace '[\x00-\x1f]+', ' ')) }
    switch -CaseSensitive ([string]$Run.status) {
        'succeeded' { exit $ExOk }
        'failed' { exit $ExFailed }
        'cancelled' { exit $ExCancelled }
        default { Err "the run ended with a status this script does not know: $($Run.status)"; exit $ExFailed }
    }
}

function Stop-TimedOut($Run, [long]$Id) {
    Say "Status: $($Run.status) (still going after the timeout)"
    if ($Cancel) {
        $c = Invoke-Kilnmark 'POST' "/api/v1/$Kind-runs/$Id/cancel" 60 $null
        if ($c.Code -eq 200) { Say 'Cancel: asked; the run stops within a few seconds' }
        elseif ($c.Code -eq 409) { Say 'Cancel: the run had already ended' }
        else { Err "could not cancel the run: $($c.Error)" }
    } else {
        Say "The run is left going. Its page shows how it ends."
    }
    exit $ExTimeout
}

function Test-Ended($Run) {
    $s = [string]$Run.status
    return ($s -cne 'queued' -and $s -cne 'running')
}

# ---- start ----

$Plural = if ($Kind -ceq 'pipeline') { 'pipelines' } else { 'flows' }
$Request = [ordered]@{ parameters = $Params }
if ($null -ne $Version) { $Request['version'] = $Version }
Say "Request key: $Key"

$failures = 0
while ($true) {
    $w = Get-NextWait
    if ($w -le 0) { Err 'timed out before the run could be started'; exit $ExTimeout }
    $r = Invoke-Kilnmark 'POST' "/api/v1/$Plural/$Name/runs?wait=$w" ($w + 60) $Request
    if ($r.Code -eq 200 -or $r.Code -eq 202) { break }
    if ($r.Code -eq 503) {
        $failures = 0
        Say "Kilnmark is busy; asking again in $($r.RetryAfter) seconds"
        Start-Sleep -Seconds $r.RetryAfter
        continue
    }
    if (@(400, 401, 403, 404, 409, 422) -contains $r.Code) {
        Err "could not start the ${Kind}: $($r.Error)"
        exit $ExRefused
    }
    $failures++
    if ($failures -ge $Tries) {
        Err "could not reach Kilnmark at $Base ($($r.Error)); the request key above is safe to use again"
        exit $ExUnreachable
    }
    Err "no answer from Kilnmark ($($r.Error)); trying again in 10 seconds with the same key"
    Start-Sleep -Seconds 10
}

$Run = $r.Run
$Id = [long]$Run.id
if ($r.Replayed) { Say "Run: $Id (started earlier with this request key)" } else { Say "Run: $Id" }
Say "Page: $Base/#/$Kind-runs/$Id"

# ---- wait ----

$failures = 0
while ($true) {
    if (Test-Ended $Run) { Complete-Run $Run }
    $w = Get-NextWait
    if ($w -le 0) { Stop-TimedOut $Run $Id }
    $g = Invoke-Kilnmark 'GET' "/api/v1/$Kind-runs/$Id`?wait=$w" ($w + 60) $null
    if ($g.Code -eq 200) {
        $failures = 0
        # A pipeline run is read as { run, nodes, ... }; a flow run as { run, nodes }.
        $Run = $g.Run.run
        continue
    }
    if ($g.Code -eq 503) { Start-Sleep -Seconds $g.RetryAfter; continue }
    if (@(401, 403, 404) -contains $g.Code) { Err "could not read run ${Id}: $($g.Error)"; exit $ExUnreachable }
    $failures++
    if ($failures -ge $Tries) {
        Err "lost Kilnmark at $Base while waiting ($($g.Error)); run $Id may still be going"
        exit $ExUnreachable
    }
    Start-Sleep -Seconds 10
}
