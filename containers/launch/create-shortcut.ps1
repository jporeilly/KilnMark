# Puts Kilnmark on the desktop and in the Start menu, once, from the Kilnmark folder:
#   powershell -ExecutionPolicy Bypass -File .\create-shortcut.ps1
# Kilnmark starts it (and Keycloak, when .env says COMPOSE_PROFILES=auth) and opens it in the
# browser; Stop Kilnmark stops it, keeping its data. Run it again after moving the folder.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$shell = New-Object -ComObject WScript.Shell
$desktop = [Environment]::GetFolderPath('Desktop')
$menu = Join-Path ([Environment]::GetFolderPath('Programs')) 'Kilnmark'
New-Item -ItemType Directory -Force -Path $menu | Out-Null

$shortcuts = @(
    @{ Name = 'Kilnmark'; Target = 'start-kilnmark.cmd'; Text = 'Start Kilnmark and open it in the browser' },
    @{ Name = 'Stop Kilnmark'; Target = 'stop-kilnmark.cmd'; Text = 'Stop Kilnmark; its data is kept' }
)
foreach ($dir in @($desktop, $menu)) {
    foreach ($s in $shortcuts) {
        $link = $shell.CreateShortcut((Join-Path $dir ($s.Name + '.lnk')))
        $link.TargetPath = Join-Path $here $s.Target
        $link.WorkingDirectory = $here
        $link.IconLocation = (Join-Path $here 'kilnmark.ico') + ',0'
        $link.Description = $s.Text
        $link.Save()
    }
}
Write-Host "Kilnmark and Stop Kilnmark are on the desktop and in the Start menu, starting $here."
