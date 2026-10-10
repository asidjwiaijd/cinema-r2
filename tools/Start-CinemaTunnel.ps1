$ErrorActionPreference = 'Stop'
$taskRoot = 'E:\mc\cinema-r2'
$mutex = [System.Threading.Mutex]::new($false, 'Local\CinemaRemoteTunnel')
if (-not $mutex.WaitOne(0)) { exit 0 }
try {
    $log = Join-Path $taskRoot '.tools\cinema-tunnel.log'
    while ($true) {
        Add-Content -LiteralPath $log -Value "$(Get-Date -Format o) Connecting cinema tunnel"
        try {
            & "$env:WINDIR\System32\OpenSSH\ssh.exe" -NT -o BatchMode=yes -o ExitOnForwardFailure=yes -o ConnectTimeout=8 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 -R 127.0.0.1:13210:127.0.0.1:3210 192.168.8.244 2>> $log
        } catch { Add-Content -LiteralPath $log -Value "$(Get-Date -Format o) $($_.Exception.Message)" }
        Add-Content -LiteralPath $log -Value "$(Get-Date -Format o) Tunnel disconnected; retry in 3 seconds"
        Start-Sleep -Seconds 3
    }
} finally { $mutex.ReleaseMutex(); $mutex.Dispose() }
