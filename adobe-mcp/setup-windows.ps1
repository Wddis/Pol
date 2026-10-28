# Installs and registers the Higgsfield Premiere Pro and After Effects MCP servers
# on a Windows machine that has Adobe Premiere Pro / After Effects installed.
# Run in PowerShell (as the same user who runs Premiere/AE and Claude Desktop):
#   powershell -ExecutionPolicy Bypass -File .\setup-windows.ps1
# Existing MCP entries in the Claude Desktop config are preserved; a backup is written first.

$ErrorActionPreference = 'Stop'

function Step($msg) { Write-Host "`n=== $msg" -ForegroundColor Cyan }

function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
                [Environment]::GetEnvironmentVariable('Path', 'User')
}

Step 'Node.js (24+ required)'
$nodeMajor = 0
if (Get-Command node -ErrorAction SilentlyContinue) {
    $nodeMajor = [int]((node -v).TrimStart('v').Split('.')[0])
}
if ($nodeMajor -lt 24) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw 'Node.js 24+ is missing and winget is unavailable. Install Node.js 24 LTS from https://nodejs.org and re-run.'
    }
    winget install --id OpenJS.NodeJS.LTS -e --accept-source-agreements --accept-package-agreements
    Refresh-Path
    $nodeMajor = [int]((node -v).TrimStart('v').Split('.')[0])
    if ($nodeMajor -lt 24) { throw "Installed Node.js is $(node -v); version 24+ is required." }
}
Write-Host "node $(node -v), npm $(npm -v)"

Step 'Adobe applications'
$aeExe = Get-ChildItem "$env:ProgramFiles\Adobe\Adobe After Effects*\Support Files\AfterFX.exe" -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $aeExe) {
    # Nonstandard install location: probe the usual layouts on every drive
    # (a full recursive scan takes far too long on large disks).
    $aeExe = Get-PSDrive -PSProvider FileSystem | ForEach-Object {
        foreach ($pattern in 'Program Files\Adobe', 'Adobe', '*\Adobe') {
            Get-ChildItem (Join-Path $_.Root "$pattern\Adobe After Effects*\Support Files\AfterFX.exe") -ErrorAction SilentlyContinue
        }
    } | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
}
$prExe = Get-ChildItem "$env:ProgramFiles\Adobe\Adobe Premiere Pro*\Adobe Premiere Pro.exe" -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
Write-Host "Premiere Pro:   $(if ($prExe) { $prExe } else { 'NOT FOUND' })"
Write-Host "After Effects:  $(if ($aeExe) { $aeExe } else { 'NOT FOUND' })"

Step 'Installing MCP packages'
npm install -g @higgsfield_org/premiere-mcp fnf-after-effects-mcp
Refresh-Path

Step 'Premiere: doctor / install-bridge / config'
fnf-premiere doctor
if ($prExe) {
    fnf-premiere install-bridge
    Write-Host 'Save open projects and restart Premiere Pro once so the bridge loads.' -ForegroundColor Yellow
}
$prConfig = (fnf-premiere config | Out-String | ConvertFrom-Json).mcpServers

Step 'After Effects: doctor / config'
if ($aeExe) { $env:AE_MCP_EXE = $aeExe }
fnf-after-effects doctor
$aeConfig = (fnf-after-effects config | Out-String | ConvertFrom-Json).mcpServers
if ($aeExe) {
    foreach ($p in $aeConfig.PSObject.Properties) {
        $p.Value | Add-Member -NotePropertyName env -NotePropertyValue ([pscustomobject]@{ AE_MCP_EXE = $aeExe }) -Force
    }
}

Step 'Registering servers in Claude Desktop (existing entries kept)'
$cfgPath = Join-Path $env:APPDATA 'Claude\claude_desktop_config.json'
New-Item -ItemType Directory -Force -Path (Split-Path $cfgPath) | Out-Null
if (Test-Path $cfgPath) {
    Copy-Item $cfgPath "$cfgPath.bak-$(Get-Date -Format yyyyMMddHHmmss)"
    $cfg = Get-Content $cfgPath -Raw | ConvertFrom-Json
} else {
    $cfg = [pscustomobject]@{}
}
if (-not $cfg.PSObject.Properties['mcpServers']) {
    $cfg | Add-Member -NotePropertyName mcpServers -NotePropertyValue ([pscustomobject]@{})
}
foreach ($src in @($prConfig, $aeConfig)) {
    foreach ($p in $src.PSObject.Properties) {
        $cfg.mcpServers | Add-Member -NotePropertyName $p.Name -NotePropertyValue $p.Value -Force
        Write-Host "  + $($p.Name)"
    }
}
# UTF-8 without BOM: Claude Desktop rejects a BOM-prefixed JSON file.
[IO.File]::WriteAllText($cfgPath, ($cfg | ConvertTo-Json -Depth 20), (New-Object Text.UTF8Encoding $false))
Write-Host "Written: $cfgPath"

if (Get-Command claude -ErrorAction SilentlyContinue) {
    Step 'Registering servers in Claude Code CLI (user scope)'
    foreach ($src in @($prConfig, $aeConfig)) {
        foreach ($p in $src.PSObject.Properties) {
            $envArgs = @()
            if ($p.Value.env) { foreach ($e in $p.Value.env.PSObject.Properties) { $envArgs += @('-e', "$($e.Name)=$($e.Value)") } }
            claude mcp add --scope user $p.Name @envArgs -- $p.Value.command @($p.Value.args)
        }
    }
}

Step 'Next steps'
Write-Host '1. Restart Premiere Pro (bridge), open a project, then run:  fnf-premiere probe'
Write-Host '2. Open a project in After Effects. If scripting reports file access denied, enable'
Write-Host '   Edit > Preferences > Scripting & Expressions > Allow Scripts to Write Files and Access Network.'
Write-Host '3. Fully quit and restart Claude Desktop, then ask it to call pr_document_info and ae_project_info.'
