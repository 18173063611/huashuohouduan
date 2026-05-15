param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [string]$OutputPath
)

if (-not (Test-Path -LiteralPath $InputPath)) {
    throw "Input file not found: $InputPath"
}

if (-not $OutputPath) {
    $directory = Split-Path -Parent $InputPath
    $name = [System.IO.Path]::GetFileNameWithoutExtension($InputPath)
    $extension = [System.IO.Path]::GetExtension($InputPath)
    $OutputPath = Join-Path $directory "$name.sanitized$extension"
}

$text = Get-Content -LiteralPath $InputPath -Raw

$rules = [ordered]@{
    '(?i)(password\s*[:=]\s*)[^\s,;]+' = '${1}<redacted>'
    '(?i)(passwd\s*[:=]\s*)[^\s,;]+' = '${1}<redacted>'
    '(?i)(pwd\s*[:=]\s*)[^\s,;]+' = '${1}<redacted>'
    '(?i)(secret[-_a-z]*\s*[:=]\s*)[^\s,;]+' = '${1}<redacted>'
    '(?i)(access[-_]?key[-_a-z]*\s*[:=]\s*)[^\s,;]+' = '${1}<redacted>'
    '(?i)(api[-_]?key\s*[:=]\s*)[^\s,;]+' = '${1}<redacted>'
    '(?i)(token\s*[:=]\s*)[^\s,;]+' = '${1}<redacted>'
    '(?i)(authorization:\s*bearer\s+)[^\s]+' = '${1}<redacted>'
    '(ark-[0-9a-f-]{20,})' = '<redacted-ark-key>'
    '(AKLT[A-Za-z0-9+/=_-]{20,})' = '<redacted-access-key-id>'
    '(?<![\d.])((?:\d{1,3}\.){3}\d{1,3})(?![\d.])' = '<redacted-ip>'
    '(jdbc:mysql://)[^:/\s]+(:\d+)?' = '${1}<redacted-host>${2}'
    '(amqp://)[^@/\s]+@[^:/\s]+(:\d+)?' = '${1}<redacted-user>@<redacted-host>${2}'
}

foreach ($pattern in $rules.Keys) {
    $text = $text -replace $pattern, $rules[$pattern]
}

Set-Content -LiteralPath $OutputPath -Value $text -Encoding UTF8
Write-Output "Sanitized log written to: $OutputPath"
