param(
    [ValidateSet("direct", "tunnel", "both")]
    [string]$Mode = "direct",
    [string]$RemoteHost = $env:HUASHUO_REMOTE_HOST,
    [string]$TunnelHost = $env:HUASHUO_TUNNEL_BIND_ADDRESS,
    [int]$TimeoutMs = 1200
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RemoteHost)) {
    $RemoteHost = "101.47.67.115"
}
if ([string]::IsNullOrWhiteSpace($TunnelHost)) {
    $TunnelHost = "127.0.0.1"
}

function Get-EnvOrDefault {
    param(
        [string]$Name,
        [int]$DefaultValue
    )

    $raw = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return $DefaultValue
    }
    return [int]$raw
}

function Test-TcpTarget {
    param(
        [string]$Name,
        [string]$HostName,
        [int]$Port,
        [int]$TimeoutMs
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $asyncResult = $client.BeginConnect($HostName, $Port, $null, $null)
        $connected = $asyncResult.AsyncWaitHandle.WaitOne($TimeoutMs, $false)
        if ($connected -and $client.Connected) {
            $client.EndConnect($asyncResult)
            $watch.Stop()
            [PSCustomObject]@{
                Service = $Name
                Target = "${HostName}:${Port}"
                Status = "OK"
                LatencyMs = $watch.ElapsedMilliseconds
            }
        }
        else {
            $watch.Stop()
            [PSCustomObject]@{
                Service = $Name
                Target = "${HostName}:${Port}"
                Status = "TIMEOUT"
                LatencyMs = $watch.ElapsedMilliseconds
            }
        }
    }
    catch {
        $watch.Stop()
        [PSCustomObject]@{
            Service = $Name
            Target = "${HostName}:${Port}"
            Status = "FAILED"
            LatencyMs = $watch.ElapsedMilliseconds
        }
    }
    finally {
        $client.Close()
    }
}

$targets = @()

if ($Mode -eq "direct" -or $Mode -eq "both") {
    $targets += @(
        @{ Name = "MySQL direct"; Host = $RemoteHost; Port = Get-EnvOrDefault -Name "HUASHUO_REMOTE_DB_PORT" -DefaultValue 3306 },
        @{ Name = "Redis direct"; Host = $RemoteHost; Port = Get-EnvOrDefault -Name "HUASHUO_REMOTE_REDIS_PORT" -DefaultValue 6379 },
        @{ Name = "RabbitMQ direct"; Host = $RemoteHost; Port = Get-EnvOrDefault -Name "HUASHUO_REMOTE_RABBITMQ_PORT" -DefaultValue 5672 },
        @{ Name = "RabbitMQ UI direct"; Host = $RemoteHost; Port = 15672 }
    )
}

if ($Mode -eq "tunnel" -or $Mode -eq "both") {
    $targets += @(
        @{ Name = "MySQL tunnel"; Host = $TunnelHost; Port = Get-EnvOrDefault -Name "HUASHUO_TUNNEL_MYSQL_PORT" -DefaultValue 13306 },
        @{ Name = "Redis tunnel"; Host = $TunnelHost; Port = Get-EnvOrDefault -Name "HUASHUO_TUNNEL_REDIS_PORT" -DefaultValue 16379 },
        @{ Name = "RabbitMQ tunnel"; Host = $TunnelHost; Port = Get-EnvOrDefault -Name "HUASHUO_TUNNEL_RABBITMQ_PORT" -DefaultValue 5673 },
        @{ Name = "RabbitMQ UI tunnel"; Host = $TunnelHost; Port = Get-EnvOrDefault -Name "HUASHUO_TUNNEL_RABBITMQ_MANAGEMENT_PORT" -DefaultValue 15673 }
    )
}

$results = foreach ($target in $targets) {
    Test-TcpTarget -Name $target.Name -HostName $target.Host -Port $target.Port -TimeoutMs $TimeoutMs
}

$results | Format-Table -AutoSize

if ($results | Where-Object { $_.Status -ne "OK" }) {
    exit 1
}
