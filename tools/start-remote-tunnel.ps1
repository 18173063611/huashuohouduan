param(
    [string]$SshHost = $env:HUASHUO_REMOTE_SSH_HOST,
    [string]$SshUser = $env:HUASHUO_REMOTE_SSH_USER,
    [string]$SshPort = $env:HUASHUO_REMOTE_SSH_PORT,
    [string]$IdentityFile = $env:HUASHUO_REMOTE_SSH_KEY,
    [string]$MysqlLocalPort = $env:HUASHUO_TUNNEL_MYSQL_PORT,
    [string]$RedisLocalPort = $env:HUASHUO_TUNNEL_REDIS_PORT,
    [string]$RabbitLocalPort = $env:HUASHUO_TUNNEL_RABBITMQ_PORT,
    [string]$RabbitManagementLocalPort = $env:HUASHUO_TUNNEL_RABBITMQ_MANAGEMENT_PORT
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    $SshHost = "101.47.67.115"
}
if ([string]::IsNullOrWhiteSpace($SshUser)) {
    $SshUser = "root"
}
if ([string]::IsNullOrWhiteSpace($SshPort)) {
    $SshPort = "22"
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

$sshArgs = @(
    "-N",
    "-o", "ExitOnForwardFailure=yes",
    "-o", "ServerAliveInterval=30",
    "-o", "ServerAliveCountMax=3",
    "-p", $SshPort,
    "-L", "127.0.0.1:${MysqlLocalPort}:127.0.0.1:3306",
    "-L", "127.0.0.1:${RedisLocalPort}:127.0.0.1:6379",
    "-L", "127.0.0.1:${RabbitLocalPort}:127.0.0.1:5672",
    "-L", "127.0.0.1:${RabbitManagementLocalPort}:127.0.0.1:15672"
)

if (-not [string]::IsNullOrWhiteSpace($IdentityFile)) {
    $sshArgs += @("-i", $IdentityFile)
}

$sshArgs += "${SshUser}@${SshHost}"

Write-Host "Opening SSH tunnel:"
Write-Host "  MySQL    127.0.0.1:${MysqlLocalPort} -> server 127.0.0.1:3306"
Write-Host "  Redis    127.0.0.1:${RedisLocalPort} -> server 127.0.0.1:6379"
Write-Host "  RabbitMQ 127.0.0.1:${RabbitLocalPort} -> server 127.0.0.1:5672"
Write-Host "  RabbitMQ UI http://127.0.0.1:${RabbitManagementLocalPort}"
Write-Host "Keep this window open while running the backend locally."

& ssh @sshArgs
