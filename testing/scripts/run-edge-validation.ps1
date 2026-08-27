param(
    [ValidateSet('small', 'medium')][string]$Scale = 'small',
    [int]$Seed = 20260827,
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$WaitSeconds = 120,
    [switch]$IncludeHotUser
)

. (Join-Path $PSScriptRoot 'common.ps1')

try {
    Assert-LoopbackUrl -Url $BaseUrl
    Assert-TcpEndpoint -Url $BaseUrl
    $env:PULSEFLOW_TEST_ENV = 'test'
    Assert-TestDatabase

    $generator = Join-Path $TestingRoot 'generator\generate_dataset.py'
    & (Resolve-PythonCommand) $generator '--scale' $Scale.ToUpperInvariant() '--scenario' 'all' '--seed' $Seed.ToString()
    if ($LASTEXITCODE -ne 0) { throw 'Dataset generation failed.' }

    $runRoot = Get-TestReportDirectory -RunId (New-TestRunId)
    $datasetNames = @('duplicate-events-v1', 'out-of-order-events-v1', 'late-events-v1', 'invalid-payload-v1')
    if ($IncludeHotUser) { $datasetNames += "hot-user-events-$Scale-v1" }
    $overall = 0
    $results = @()
    foreach ($datasetName in $datasetNames) {
        $dataset = Join-Path $TestingRoot "datasets\generated\$datasetName.jsonl"
        $manifest = Join-Path $TestingRoot "datasets\generated\$datasetName.manifest.json"
        $reportDir = Join-Path $runRoot $datasetName
        New-Item -ItemType Directory -Path $reportDir -Force | Out-Null

        & (Resolve-PythonCommand) (Join-Path $TestingRoot 'scripts\replay_dataset.py') '--dataset' $dataset '--base-url' $BaseUrl '--report-dir' $reportDir '--pacing-ms' '1' '--rebase-event-time'
        $replayExit = $LASTEXITCODE
        $validateExit = 0
        if ($datasetName -ne 'invalid-payload-v1') {
            & (Resolve-PythonCommand) (Join-Path $TestingRoot 'validators\validate_run.py') '--manifest' $manifest '--run-dir' $reportDir '--report-dir' $reportDir '--wait-seconds' $WaitSeconds.ToString()
            $validateExit = $LASTEXITCODE
        } else {
            Write-Warning 'Invalid payload is validated at the HTTP boundary only; no store validator is applicable.'
        }
        if ($replayExit -ne 0 -or $validateExit -ne 0) { $overall = 1 }
        $results += [pscustomobject]@{
            dataset = $datasetName
            replayExit = $replayExit
            validateExit = $validateExit
            reportDir = $reportDir
        }
    }
    $summary = [pscustomobject]@{
        status = if ($overall -eq 0) { 'PASS' } else { 'FAIL' }
        scale = $Scale
        seed = $Seed
        results = $results
    }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runRoot 'edge-summary.json') -Encoding UTF8
    if ($overall -ne 0) { exit 1 }
} catch {
    Write-Error $_
    exit 1
}
