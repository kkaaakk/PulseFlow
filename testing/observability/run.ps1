param(
    [ValidateSet('start', 'verify', 'stop', 'down')]
    [string]$Action = 'start',
    [switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$tokenPath = Join-Path $repoRoot '.env.jaeger.local'
if (-not (Test-Path -LiteralPath $tokenPath)) {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [IO.File]::WriteAllText($tokenPath,
        ('PULSEFLOW_AGENT_INTERNAL_TOKEN=' + [Convert]::ToBase64String($bytes)),
        (New-Object Text.UTF8Encoding($false)))
}
$composeArgs = @('compose', '--env-file', $tokenPath,
    '-f', (Join-Path $repoRoot 'testing/docker-compose.test.yml'),
    '-f', (Join-Path $repoRoot 'testing/docker-compose.observability.yml'),
    '--profile', 'agent', '--profile', 'observability')
switch ($Action) {
    'start' {
        if ($NoBuild) { & docker @composeArgs up -d --no-build java agent jaeger }
        else { & docker @composeArgs up -d --build java agent jaeger }
    }
    'verify' { & docker @composeArgs run --rm --no-deps trace-check }
    'stop' { & docker @composeArgs stop jaeger }
    # No -v: existing MySQL, Agent MySQL, Redis and Kafka volumes are retained.
    'down' { & docker @composeArgs down }
}
if ($LASTEXITCODE -ne 0) { throw "Docker Compose $Action failed ($LASTEXITCODE)" }
if ($Action -eq 'start') { Write-Host 'Jaeger: http://localhost:16686' }
