[CmdletBinding()]
param(
    [switch]$ConfigureOnly,
    [switch]$Build
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$envPath = Join-Path $PSScriptRoot '.env'

function Read-EnvironmentFile {
    param([string]$Path)
    $values = [ordered]@{}
    if (Test-Path -LiteralPath $Path) {
        foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
                $value = $Matches[2].Trim()
                if ($value.Length -ge 2 -and $value[0] -eq "'" -and $value[-1] -eq "'") {
                    $value = $value.Substring(1, $value.Length - 2).Replace("\'", "'")
                } elseif ($value.Length -ge 2 -and $value[0] -eq '"' -and $value[-1] -eq '"') {
                    $value = $value.Substring(1, $value.Length - 2)
                }
                $values[$Matches[1]] = $value
            }
        }
    }
    return $values
}

$isNew = -not (Test-Path -LiteralPath $envPath)
$values = Read-EnvironmentFile $(if ($isNew) { Join-Path $PSScriptRoot '.env.example' } else { $envPath })
$defaults = Read-EnvironmentFile (Join-Path $PSScriptRoot '.env.example')
foreach ($key in $defaults.Keys) {
    if (-not $values.Contains($key)) { $values[$key] = $defaults[$key] }
}
$agentValues = Read-EnvironmentFile (Join-Path $PSScriptRoot 'pulseflow-agent\.env')
$providerKeys = @('PULSEFLOW_AGENT_MODEL', 'PULSEFLOW_AGENT_BASE_URL', 'PULSEFLOW_AGENT_API_KEY',
    'AZURE_LANGUAGE_ENDPOINT', 'AZURE_LANGUAGE_KEY', 'AZURE_LANGUAGE_PII_LANGUAGE')
foreach ($key in $providerKeys) {
    $processValue = [Environment]::GetEnvironmentVariable($key)
    if (-not [string]::IsNullOrWhiteSpace($processValue)) {
        $values[$key] = $processValue
    } elseif (($isNew -or -not $values.Contains($key) -or [string]::IsNullOrWhiteSpace($values[$key])) -and
        $agentValues.Contains($key) -and -not [string]::IsNullOrWhiteSpace($agentValues[$key])) {
        $values[$key] = $agentValues[$key]
    }
}
if ($values['PULSEFLOW_AGENT_MODEL'].StartsWith('openai:deepseek-')) {
    $values['PULSEFLOW_AGENT_MODEL'] = $values['PULSEFLOW_AGENT_MODEL'].Replace('openai:', 'deepseek:')
}

$secretKeys = @('PULSEFLOW_AGENT_INTERNAL_TOKEN', 'PULSEFLOW_MYSQL_PASSWORD',
    'PULSEFLOW_MYSQL_ROOT_PASSWORD', 'PULSEFLOW_AGENT_DB_PASSWORD', 'PULSEFLOW_AGENT_DB_ROOT_PASSWORD',
    'XXL_JOB_DB_PASSWORD', 'XXL_JOB_DB_ROOT_PASSWORD', 'XXL_JOB_ACCESS_TOKEN', 'XXL_JOB_ADMIN_PASSWORD')
foreach ($key in $secretKeys) {
    if (-not $values.Contains($key) -or [string]::IsNullOrWhiteSpace($values[$key])) {
        $bytes = New-Object byte[] 32
        $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
        $values[$key] = ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
    }
}

$lines = @('# Local PulseFlow configuration. Do not commit or share this file.')
foreach ($key in $values.Keys) {
    $escaped = ([string]$values[$key]).Replace("'", "\'")
    $lines += "$key='$escaped'"
}
[IO.File]::WriteAllLines($envPath, $lines, (New-Object Text.UTF8Encoding($false)))
Write-Host 'Local configuration prepared; existing credentials were retained.'
if ($ConfigureOnly) { return }

$required = @('PULSEFLOW_AGENT_MODEL', 'PULSEFLOW_AGENT_API_KEY', 'AZURE_LANGUAGE_ENDPOINT', 'AZURE_LANGUAGE_KEY')
$missing = @($required | Where-Object { -not $values.Contains($_) -or [string]::IsNullOrWhiteSpace($values[$_]) })
if ($missing.Count -gt 0) { throw ('Configure these values in the root .env: ' + ($missing -join ', ')) }
if ($values['PULSEFLOW_AGENT_MODEL'] -eq 'test') {
    throw 'The full stack requires a real model. Use testing/docker-compose.test.yml for offline tests.'
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker Desktop is required.' }
docker info --format '{{.ServerVersion}}' *> $null
if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop, then run start.ps1 again.' }

$composeArguments = @('compose', '--project-directory', $PSScriptRoot, '--env-file', $envPath,
    '-f', (Join-Path $PSScriptRoot 'compose.yml'), 'up', '-d', '--wait', '--wait-timeout', '300')
if ($Build) { $composeArguments += '--build' }
& docker @composeArguments
if ($LASTEXITCODE -ne 0) { throw 'The stack did not become healthy. Inspect docker compose ps and docker compose logs.' }

Write-Host ('Web:       http://127.0.0.1:' + $values['PULSEFLOW_WEB_PORT'])
Write-Host ('XXL-JOB:   http://127.0.0.1:' + $values['XXL_JOB_ADMIN_PORT'] + '/xxl-job-admin')
Write-Host ('Traces:    http://127.0.0.1:' + $values['JAEGER_UI_PORT'])
Write-Host 'XXL-JOB username: admin; password: XXL_JOB_ADMIN_PASSWORD in the root .env.'
