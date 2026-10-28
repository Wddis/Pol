# Runs once inside the Windows VM at the end of the unattended setup (dockur copies
# the OEM folder to C:\OEM and calls install.bat). start-vm.sh fills C:\OEM with the
# sandbox CA certificates, the Node.js MSI, Win32-OpenSSH and the host's SSH public key.
$ErrorActionPreference = 'Continue'
Start-Transcript -Path C:\OEM\install.log -Append

# The sandbox intercepts TLS; trust its CAs (one file per certificate: certutil only
# imports the first certificate of a multi-certificate file).
Get-ChildItem C:\OEM\ca-*.crt | ForEach-Object { certutil -addstore -f Root $_.FullName }
# Node reads the Windows store with --use-system-ca; npm also gets the full bundle.
[Environment]::SetEnvironmentVariable('NODE_OPTIONS', '--use-system-ca', 'Machine')
$env:NODE_OPTIONS = '--use-system-ca'

Start-Process msiexec.exe -Wait -ArgumentList '/i C:\OEM\node-x64.msi /qn /norestart'
$npm = 'C:\Program Files\nodejs\npm.cmd'
& 'C:\Program Files\nodejs\node.exe' -v
& $npm config set cafile C:\OEM\ca-bundle.crt --location=global
& $npm install -g --cafile C:\OEM\ca-bundle.crt --prefix C:\npm-global @higgsfield_org/premiere-mcp fnf-after-effects-mcp
[Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';C:\npm-global', 'Machine')

# OpenSSH server so the Linux host can run the stdio MCP servers over ssh.
Expand-Archive C:\OEM\OpenSSH-Win64.zip -DestinationPath 'C:\Program Files' -Force
& powershell -ExecutionPolicy Bypass -File 'C:\Program Files\OpenSSH-Win64\install-sshd.ps1'
New-Item -ItemType Directory -Force C:\ProgramData\ssh | Out-Null
Copy-Item C:\OEM\administrators_authorized_keys C:\ProgramData\ssh\administrators_authorized_keys -Force
icacls C:\ProgramData\ssh\administrators_authorized_keys /inheritance:r /grant 'Administrators:F' /grant 'SYSTEM:F'
New-NetFirewallRule -Name sshd -DisplayName 'OpenSSH Server' -Protocol TCP -LocalPort 22 -Action Allow -Direction Inbound -ErrorAction SilentlyContinue
Set-Service sshd -StartupType Automatic
Start-Service sshd
Get-Service sshd
Stop-Transcript
