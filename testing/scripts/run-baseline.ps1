param(
    [switch]$RunDockerIntegration
)

. (Join-Path $PSScriptRoot 'common.ps1')

try {
    Push-Location (Join-Path $RepoRoot 'pulseflow')
    try {
        if ($RunDockerIntegration) {
            $env:PULSEFLOW_TEST_ENV = 'test'
            $env:PULSEFLOW_TEST_DOCKER = 'true'
            & mvn '-q' 'clean' 'verify'
        } else {
            Write-Host 'Running existing unit tests only. Docker-gated *IT tests are NOT_RUN.'
            & mvn '-q' 'test'
        }
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally {
        Pop-Location
    }
} catch {
    Write-Error $_
    exit 1
}
