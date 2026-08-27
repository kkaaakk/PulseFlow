param(
    [ValidateSet('small', 'medium')][string]$Scale = 'small',
    [int]$Seed = 20260827,
    [string]$BaseUrl = 'http://localhost:8080',
    [switch]$PrepareDependencies,
    [switch]$SkipBuild,
    [switch]$SkipK6,
    [int]$WaitSeconds = 120
)

. (Join-Path $PSScriptRoot 'common.ps1')

$env:PULSEFLOW_TEST_ENV = 'test'
$env:PULSEFLOW_TEST_MYSQL_DATABASE = if ($env:PULSEFLOW_TEST_MYSQL_DATABASE) { $env:PULSEFLOW_TEST_MYSQL_DATABASE } else { 'pulseflow_test' }

try {
    Assert-LoopbackUrl -Url $BaseUrl
    Assert-TcpEndpoint -Url $BaseUrl
    Assert-TestDatabase -Database $env:PULSEFLOW_TEST_MYSQL_DATABASE

    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker CLI was not found; full validation requires Docker or use the individual no-Docker checks.'
    }
    & docker info | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker daemon is unavailable; full validation was not run.'
    }

    if ($PrepareDependencies) {
        $compose = Join-Path $TestingRoot 'docker-compose.test.yml'
        & docker compose '-f' $compose '-p' 'pulseflow-test' 'up' '-d'
        if ($LASTEXITCODE -ne 0) { throw 'Test dependency compose startup failed.' }
    }

    & (Resolve-PythonCommand) (Join-Path $TestingRoot 'scripts\verify_contract.py')
    if ($LASTEXITCODE -ne 0) { throw 'Source contract check failed.' }

    if (-not $SkipBuild) {
        # Existing *IT tests are environment-gated. Enabling the flag here is
        # intentional: a Docker-backed full run must execute them, not skip.
        $env:PULSEFLOW_TEST_DOCKER = 'true'
        Push-Location (Join-Path $RepoRoot 'pulseflow')
        try {
            & mvn '-q' 'clean' 'verify'
            if ($LASTEXITCODE -ne 0) { throw 'Maven baseline/integration tests failed.' }
        } finally {
            Pop-Location
        }
    }

    $generator = Join-Path $TestingRoot 'generator\generate_dataset.py'
    & (Resolve-PythonCommand) $generator '--scale' $Scale.ToUpperInvariant() '--scenario' 'all' '--seed' $Seed.ToString()
    if ($LASTEXITCODE -ne 0) { throw 'Dataset generation failed.' }

    $runId = New-TestRunId
    $reportDir = Get-TestReportDirectory -RunId $runId
    $dataset = Join-Path $TestingRoot ("datasets\generated\normal-events-{0}-v1.jsonl" -f $Scale)
    $manifest = Join-Path $TestingRoot ("datasets\generated\normal-events-{0}-v1.manifest.json" -f $Scale)
    $replay = Join-Path $TestingRoot 'scripts\replay_dataset.py'
    & (Resolve-PythonCommand) $replay '--dataset' $dataset '--base-url' $BaseUrl '--report-dir' $reportDir '--run-id' $runId '--pacing-ms' '1'
    $replayExit = $LASTEXITCODE

    if (-not $SkipK6) {
        if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
            throw 'k6 was not found; pass -SkipK6 to make the omission explicit.'
        }
        $k6Script = Join-Path $TestingRoot 'k6\smoke.js'
        & k6 run '-e' "BASE_URL=$BaseUrl" '--summary-export' (Join-Path $reportDir 'k6-summary.json') $k6Script
        $k6Exit = $LASTEXITCODE
    } else {
        $k6Exit = 2
        Write-Warning 'k6 explicitly skipped; report will contain NOT_RUN.'
    }

    $validator = Join-Path $TestingRoot 'validators\validate_run.py'
    & (Resolve-PythonCommand) $validator '--manifest' $manifest '--run-dir' $reportDir '--report-dir' $reportDir '--wait-seconds' $WaitSeconds.ToString()
    $validateExit = $LASTEXITCODE

    if ($replayExit -ne 0 -or $k6Exit -ne 0 -or $validateExit -ne 0) {
        exit 1
    }
} catch {
    Write-Error $_
    exit 1
}
