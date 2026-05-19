param(
    [string]$SshHost = $env:HUASHUO_REMOTE_SSH_HOST,
    [string]$SshUser = $env:HUASHUO_REMOTE_SSH_USER,
    [string]$SshPort = $env:HUASHUO_REMOTE_SSH_PORT,
    [string]$IdentityFile = $env:HUASHUO_REMOTE_SSH_KEY,
    [string]$BindAddress = $env:HUASHUO_TUNNEL_BIND_ADDRESS,
    [string]$MysqlLocalPort = $env:HUASHUO_TUNNEL_MYSQL_PORT,
    [string]$RedisLocalPort = $env:HUASHUO_TUNNEL_REDIS_PORT,
    [string]$RabbitLocalPort = $env:HUASHUO_TUNNEL_RABBITMQ_PORT,
    [string]$RabbitManagementLocalPort = $env:HUASHUO_TUNNEL_RABBITMQ_MANAGEMENT_PORT
)

$ErrorActionPreference = "Stop"

function Test-TcpPort {
    param(
        [string]$HostName,
        [int]$Port,
        [int]$TimeoutMs = 700
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $asyncResult = $client.BeginConnect($HostName, $Port, $null, $null)
        $connected = $asyncResult.AsyncWaitHandle.WaitOne($TimeoutMs, $false)
        if (-not $connected -or -not $client.Connected) {
            return $false
        }
        $client.EndConnect($asyncResult)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $client.Close()
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    $SshHost = "101.47.67.115"
}
if ([string]::IsNullOrWhiteSpace($SshUser)) {
    $SshUser = "root"
}
if ([string]::IsNullOrWhiteSpace($SshPort)) {
    $SshPort = "22"
}
if ([string]::IsNullOrWhiteSpace($BindAddress)) {
    $BindAddress = "127.0.0.1"
}
if ([string]::IsNullOrWhiteSpace($MysqlLocalPort)) {
    $MysqlLocalPort = "13306"
}
if ([string]::IsNullOrWhiteSpace($RedisLocalPort)) {
    $RedisLocalPort = "16379"
}
if ([string]::IsNullOrWhiteSpace($RabbitLocalPort)) {
    $RabbitLocalPort = "5673"
}
if ([string]::IsNullOrWhiteSpace($RabbitManagementLocalPort)) {
    $RabbitManagementLocalPort = "15673"
}

$ssh = Get-Command ssh -ErrorAction SilentlyContinue
if ($null -eq $ssh) {
    throw "OpenSSH ssh was not found in PATH. Install OpenSSH Client or run the tunnel from a shell that has ssh available."
}

$ports = @(
    @{ Name = "MySQL"; Port = [int]$MysqlLocalPort },
    @{ Name = "Redis"; Port = [int]$RedisLocalPort },
    @{ Name = "RabbitMQ"; Port = [int]$RabbitLocalPort },
    @{ Name = "RabbitMQ UI"; Port = [int]$RabbitManagementLocalPort }
)

$openPorts = @($ports | Where-Object { Test-TcpPort -HostName $BindAddress -Port $_.Port })
if ($openPorts.Count -eq $ports.Count) {
    Write-Host "All tunnel ports are already reachable on ${BindAddress}. The tunnel may already be running."
    Write-Host "Use tools\check-remote-services.ps1 -Mode tunnel to verify latency."
    exit 0
}
elseif ($openPorts.Count -gt 0) {
    $names = ($openPorts | ForEach-Object { "$($_.Name):$($_.Port)" }) -join ", "
    Write-Host "Some local tunnel ports are already in use: $names"
    Write-Host "SSH may fail if another process owns those ports."
}

$sshArgs = @(
    "-N",
    "-T",
    "-o", "ExitOnForwardFailure=yes",
    "-o", "ConnectTimeout=5",
    "-o", "ServerAliveInterval=30",
    "-o", "ServerAliveCountMax=3",
    "-o", "TCPKeepAlive=yes",
    "-o", "Compression=no",
    "-p", $SshPort,
    "-L", "${BindAddress}:${MysqlLocalPort}:127.0.0.1:3306",
    "-L", "${BindAddress}:${RedisLocalPort}:127.0.0.1:6379",
    "-L", "${BindAddress}:${RabbitLocalPort}:127.0.0.1:5672",
    "-L", "${BindAddress}:${RabbitManagementLocalPort}:127.0.0.1:15672"
)

if (-not [string]::IsNullOrWhiteSpace($IdentityFile)) {
    $sshArgs += @("-i", $IdentityFile)
}

$sshArgs += "${SshUser}@${SshHost}"

Write-Host "Opening SSH tunnel:"
Write-Host "  MySQL    ${BindAddress}:${MysqlLocalPort} -> server 127.0.0.1:3306"
Write-Host "  Redis    ${BindAddress}:${RedisLocalPort} -> server 127.0.0.1:6379"
Write-Host "  RabbitMQ ${BindAddress}:${RabbitLocalPort} -> server 127.0.0.1:5672"
Write-Host "  RabbitMQ UI http://${BindAddress}:${RabbitManagementLocalPort}"
Write-Host "Keep this window open while running the backend locally."

& ssh @sshArgs
