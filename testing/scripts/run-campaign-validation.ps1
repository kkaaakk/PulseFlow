param(
    [int]$Seed = 20260827,
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$WaitSeconds = 360
)

. (Join-Path $PSScriptRoot 'common.ps1')

try {
    Assert-LoopbackUrl -Url $BaseUrl
    $env:PULSEFLOW_TEST_ENV = 'test'
    Assert-TestDatabase
    if ($Seed -ne 20260827) {
        throw 'The current SQL fixture is keyed to seed 20260827; use that seed or update the fixture target id first.'
    }

    $generator = Join-Path $TestingRoot 'generator\generate_dataset.py'
    & (Resolve-PythonCommand) $generator '--scale' 'SMALL' '--scenario' 'campaign' '--seed' $Seed.ToString()
    if ($LASTEXITCODE -ne 0) { throw 'Campaign dataset generation failed.' }

    & (Join-Path $PSScriptRoot 'prepare-campaign-fixture.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Campaign SQL fixture preparation failed.' }

    $runDir = Get-TestReportDirectory -RunId (New-TestRunId)
    $dataset = Join-Path $TestingRoot 'datasets\generated\campaign-frequency-attribution-v1.jsonl'
    $manifest = Join-Path $TestingRoot 'datasets\generated\campaign-frequency-attribution-v1.manifest.json'
    & (Resolve-PythonCommand) (Join-Path $TestingRoot 'scripts\replay_dataset.py') '--dataset' $dataset '--base-url' $BaseUrl '--report-dir' $runDir '--pacing-ms' '1' '--rebase-event-time'
    $replayExit = $LASTEXITCODE
    & (Resolve-PythonCommand) (Join-Path $TestingRoot 'validators\validate_run.py') '--manifest' $manifest '--run-dir' $runDir '--report-dir' $runDir '--wait-seconds' $WaitSeconds.ToString()
    $validateExit = $LASTEXITCODE
    if ($replayExit -ne 0 -or $validateExit -ne 0) { exit 1 }
} catch {
    Write-Error $_
    exit 1
}
