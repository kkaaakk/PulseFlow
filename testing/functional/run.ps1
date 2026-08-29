param(
    [ValidateSet('small', 'medium', 'large')][string]$Scale = 'small',
    [int]$Seed = 20260827,
    [string]$BaseUrl = 'http://localhost:8080',
    [ValidateSet('all', 'fixture', 'normal', 'edge', 'hot-user', 'concurrency', 'campaign')][string]$Scenario = 'all',
    [int]$Concurrency = 8,
    [int]$WaitSeconds = 120,
    [int]$CampaignWaitSeconds = 360,
    [switch]$PrepareDependencies,
    [switch]$RunMaven,
    [switch]$SkipMySql,
    [switch]$SkipRedis,
    [switch]$RebaseEventTime,
    [switch]$DryRun,
    [string]$RunId = '',
    [string]$ReportDir = ''
)

. (Join-Path $PSScriptRoot '..\common.ps1')

function Get-ScenarioPlan {
    param([string]$SelectedScenario, [string]$SelectedScale)
    $scaleName = $SelectedScale.ToLowerInvariant()
    switch ($SelectedScenario) {
        'fixture' { return @([pscustomobject]@{ name = 'fixture'; dataset = 'smoke-events-v1' }) }
        'normal' { return @([pscustomobject]@{ name = 'normal'; dataset = "normal-events-$scaleName-v1" }) }
        'edge' {
            return @(
                [pscustomobject]@{ name = 'duplicate'; dataset = 'duplicate-events-v1' },
                [pscustomobject]@{ name = 'out-of-order'; dataset = 'out-of-order-events-v1' },
                [pscustomobject]@{ name = 'late'; dataset = 'late-events-v1' },
                [pscustomobject]@{ name = 'invalid'; dataset = 'invalid-payload-v1' }
            )
        }
        'hot-user' { return @([pscustomobject]@{ name = 'hot-user'; dataset = "hot-user-events-$scaleName-v1" }) }
        'concurrency' { return @([pscustomobject]@{ name = 'concurrency'; dataset = 'concurrency-events-v1' }) }
        'campaign' { return @([pscustomobject]@{ name = 'campaign'; dataset = 'campaign-frequency-attribution-v1' }) }
        default {
            return @(
                [pscustomobject]@{ name = 'fixture'; dataset = 'smoke-events-v1' },
                [pscustomobject]@{ name = 'normal'; dataset = "normal-events-$scaleName-v1" },
                [pscustomobject]@{ name = 'duplicate'; dataset = 'duplicate-events-v1' },
                [pscustomobject]@{ name = 'out-of-order'; dataset = 'out-of-order-events-v1' },
                [pscustomobject]@{ name = 'late'; dataset = 'late-events-v1' },
                [pscustomobject]@{ name = 'invalid'; dataset = 'invalid-payload-v1' },
                [pscustomobject]@{ name = 'hot-user'; dataset = "hot-user-events-$scaleName-v1" },
                [pscustomobject]@{ name = 'concurrency'; dataset = 'concurrency-events-v1' },
                [pscustomobject]@{ name = 'campaign'; dataset = 'campaign-frequency-attribution-v1' }
            )
        }
    }
}

function Get-GeneratorScenarios {
    param([string]$SelectedScenario)
    switch ($SelectedScenario) {
        'fixture' { return @() }
        'normal' { return @('normal') }
        'edge' { return @('duplicate', 'out-of-order', 'late', 'invalid') }
        'hot-user' { return @('hot-user') }
        'concurrency' { return @('concurrency') }
        'campaign' { return @('campaign') }
        default { return @('normal', 'duplicate', 'out-of-order', 'late', 'invalid', 'hot-user', 'concurrency', 'campaign') }
    }
}

function Get-StatusFromExitCode {
    param([int]$ExitCode)
    if ($ExitCode -eq 0) { return 'PASS' }
    if ($ExitCode -eq 1) { return 'FAIL' }
    return 'NOT_RUN'
}

function Prepare-CampaignFixture {
    param([string]$MysqlDatabase)
    $mysqlBin = if ($env:PULSEFLOW_TEST_MYSQL_BIN) { $env:PULSEFLOW_TEST_MYSQL_BIN } else { 'mysql' }
    $mysqlHost = if ($env:PULSEFLOW_TEST_MYSQL_HOST) { $env:PULSEFLOW_TEST_MYSQL_HOST } else { '127.0.0.1' }
    $mysqlPort = if ($env:PULSEFLOW_TEST_MYSQL_PORT) { [int]$env:PULSEFLOW_TEST_MYSQL_PORT } else { 13306 }
    $mysqlUser = if ($env:PULSEFLOW_TEST_MYSQL_USER) { $env:PULSEFLOW_TEST_MYSQL_USER } else { 'test' }
    $mysqlPassword = if ($env:PULSEFLOW_TEST_MYSQL_PASSWORD) { $env:PULSEFLOW_TEST_MYSQL_PASSWORD } else { 'test' }
    Assert-TestStoreHost -HostName $mysqlHost
    if (-not (Get-Command $mysqlBin -ErrorAction SilentlyContinue) -and -not (Test-Path -LiteralPath $mysqlBin)) {
        throw "MySQL client was not found: $mysqlBin"
    }
    $sqlPath = Join-Path $PSScriptRoot 'campaign-fixture.sql'
    $previousPassword = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = $mysqlPassword
        $sql = Get-Content -Raw -LiteralPath $sqlPath
        & $mysqlBin '--batch' '--raw' '--protocol=tcp' '-h' $mysqlHost '-P' $mysqlPort.ToString() '-u' $mysqlUser $MysqlDatabase '--execute' $sql
        if ($LASTEXITCODE -ne 0) { throw 'Campaign fixture preparation failed.' }
    } finally {
        $env:MYSQL_PWD = $previousPassword
    }
}

try {
    if ($Concurrency -lt 1) { throw 'Concurrency must be at least 1.' }
    Assert-LoopbackUrl -Url $BaseUrl
    $env:PULSEFLOW_TEST_ENV = 'test'
    if (-not $env:PULSEFLOW_TEST_MYSQL_DATABASE) { $env:PULSEFLOW_TEST_MYSQL_DATABASE = 'pulseflow_test' }
    Assert-TestDatabase -Database $env:PULSEFLOW_TEST_MYSQL_DATABASE

    if (-not $RunId) { $RunId = New-TestRunId }
    if ($ReportDir) {
        New-Item -ItemType Directory -Path $ReportDir -Force | Out-Null
        $runRoot = (Resolve-Path -LiteralPath $ReportDir).Path
    } else {
        $runRoot = Get-TestReportDirectory -RunId $RunId
    }
    try {
        if (-not $DryRun) { Assert-TcpEndpoint -Url $BaseUrl }
    } catch {
        $notRun = [ordered]@{
            status = 'NOT_RUN'
            runId = $RunId
            baseUrl = $BaseUrl
            reason = "application endpoint is not reachable: $($_.Exception.Message)"
        }
        $notRun | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runRoot 'functional-report.json') -Encoding UTF8
        @('# PulseFlow Functional Validation', '', 'Status: **NOT_RUN**', '', $notRun.reason) |
            Set-Content -LiteralPath (Join-Path $runRoot 'functional-report.md') -Encoding UTF8
        Write-Warning 'application endpoint is not reachable; functional validation is NOT_RUN.'
        exit 2
    }

    if ($PrepareDependencies) {
        if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
            throw 'Docker CLI was not found; use an existing isolated test stack or install Docker.'
        }
        & docker info | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Docker daemon is unavailable.' }
        $compose = Join-Path $TestingRoot 'docker-compose.test.yml'
        & docker compose '-f' $compose '-p' 'pulseflow-test' 'up' '-d'
        if ($LASTEXITCODE -ne 0) { throw 'Test dependency compose startup failed.' }
    }

    $contract = Join-Path $runRoot 'contract-check.json'
    Invoke-PythonScript -ScriptPath (Join-Path $PSScriptRoot 'verify_contract.py') -Arguments @('--output', $contract)

    if ($RunMaven) {
        $env:PULSEFLOW_TEST_DOCKER = 'true'
        Set-TestcontainersDockerHost
        Push-Location (Join-Path $RepoRoot 'pulseflow')
        try {
            & mvn '-q' 'clean' 'verify'
            if ($LASTEXITCODE -ne 0) { throw 'Maven baseline/integration tests failed.' }
        } finally {
            Pop-Location
        }
    }

    $generator = Join-Path $PSScriptRoot 'generate.py'
    $generatedRoot = Join-Path $TestingRoot 'data\generated'
    foreach ($generatorScenario in (Get-GeneratorScenarios -SelectedScenario $Scenario)) {
        Invoke-PythonScript -ScriptPath $generator -Arguments @(
            '--scale', $Scale.ToUpperInvariant(), '--scenario', $generatorScenario,
            '--seed', $Seed.ToString(), '--output-dir', $generatedRoot
        )
    }

    $plan = Get-ScenarioPlan -SelectedScenario $Scenario -SelectedScale $Scale
    $caseResults = @()
    foreach ($case in $plan) {
        $caseDir = Join-Path $runRoot $case.name
        New-Item -ItemType Directory -Path $caseDir -Force | Out-Null
        $caseDataRoot = if ($case.name -eq 'fixture') {
            Join-Path $TestingRoot 'data\fixtures'
        } else {
            $generatedRoot
        }
        $dataset = Join-Path $caseDataRoot "$($case.dataset).jsonl"
        $manifest = Join-Path $caseDataRoot "$($case.dataset).manifest.json"
        if (-not (Test-Path -LiteralPath $dataset) -or -not (Test-Path -LiteralPath $manifest)) {
            throw "Generated dataset or Manifest is missing for scenario $($case.name)."
        }
        $manifestObject = Get-Content -Raw -LiteralPath $manifest | ConvertFrom-Json
        $limitations = @()
        $scenarioDetailsProperty = $manifestObject.PSObject.Properties['scenarioDetails']
        $sourceLimitationProperty = if ($scenarioDetailsProperty) {
            $scenarioDetailsProperty.Value.PSObject.Properties['sourceLimitation']
        }
        if ($sourceLimitationProperty -and $sourceLimitationProperty.Value) {
            $limitations += [string]$sourceLimitationProperty.Value
        }

        if ($case.name -eq 'campaign' -and -not $DryRun) {
            if ($Seed -ne 20260827) {
                throw 'The Campaign fixture is keyed to seed 20260827; update campaign-fixture.sql before using another seed.'
            }
            Prepare-CampaignFixture -MysqlDatabase $env:PULSEFLOW_TEST_MYSQL_DATABASE
        }

        $replayArgs = @(
            '--dataset', $dataset, '--base-url', $BaseUrl, '--report-dir', $caseDir,
            '--run-id', "$runId-$($case.name)", '--pacing-ms', '1'
        )
        if ($case.name -in @('hot-user', 'concurrency')) {
            $replayArgs += @('--concurrency', $Concurrency.ToString())
        } else {
            $replayArgs += @('--concurrency', '1')
        }
        if ($RebaseEventTime -or $case.name -in @('out-of-order', 'late', 'campaign', 'concurrency')) {
            $replayArgs += '--rebase-event-time'
        }
        if ($DryRun) { $replayArgs += '--dry-run' }
        & (Resolve-PythonCommand) (Join-Path $PSScriptRoot 'replay.py') @replayArgs
        $replayExit = $LASTEXITCODE

        $caseWaitSeconds = if ($case.name -eq 'campaign') { $CampaignWaitSeconds } else { $WaitSeconds }
        $validateArgs = @(
            '--manifest', $manifest, '--run-dir', $caseDir, '--report-dir', $caseDir,
            '--wait-seconds', $caseWaitSeconds.ToString()
        )
        if ($DryRun -or $case.name -eq 'invalid') { $validateArgs += '--http-only' }
        if ($SkipMySql) { $validateArgs += '--skip-mysql' }
        if ($SkipRedis) { $validateArgs += '--skip-redis' }
        & (Resolve-PythonCommand) (Join-Path $PSScriptRoot 'validate.py') @validateArgs
        $validateExit = $LASTEXITCODE

        $replayStatus = Get-StatusFromExitCode -ExitCode $replayExit
        $validateStatus = Get-StatusFromExitCode -ExitCode $validateExit
        $caseStatus = if ($replayStatus -eq 'FAIL' -or $validateStatus -eq 'FAIL') {
            'FAIL'
        } elseif ($replayStatus -eq 'NOT_RUN' -or $validateStatus -eq 'NOT_RUN') {
            'NOT_RUN'
        } else {
            'PASS'
        }
        $caseResults += [ordered]@{
            scenario = $case.name
            dataset = $case.dataset
            status = $caseStatus
            replayStatus = $replayStatus
            validationStatus = $validateStatus
            concurrency = if ($case.name -in @('hot-user', 'concurrency')) { $Concurrency } else { 1 }
            limitations = $limitations
            reportDir = $caseDir
        }
    }

    $overallStatus = if ($caseResults.status -contains 'FAIL') { 'FAIL' }
    elseif ($caseResults.status -contains 'NOT_RUN') { 'NOT_RUN' }
    else { 'PASS' }
    $scenarioStatuses = [ordered]@{}
    foreach ($result in $caseResults) { $scenarioStatuses[$result.scenario] = $result.status }
    $report = [ordered]@{
        status = $overallStatus
        runId = $runId
        scale = $Scale
        seed = $Seed
        baseUrl = $BaseUrl
        replay = 'Functional Replay with deterministic datasets and bounded concurrency'
        performance = 'Not run here; use testing/performance/run.ps1'
        scenarios = $scenarioStatuses
        details = $caseResults
    }
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $runRoot 'functional-report.json') -Encoding UTF8
    $markdown = @(
        '# PulseFlow Functional Validation',
        '',
        "Status: **$overallStatus**",
        "Run: $runId  Scale: $Scale  Seed: $Seed",
        '',
        '| Scenario | Status | Replay | Validator | Concurrency |',
        '|---|---|---|---|---:|'
    )
    foreach ($result in $caseResults) {
        $markdown += "| $($result.scenario) | **$($result.status)** | $($result.replayStatus) | $($result.validationStatus) | $($result.concurrency) |"
    }
    $markdown | Set-Content -LiteralPath (Join-Path $runRoot 'functional-report.md') -Encoding UTF8
    Write-Host "functional validation ${overallStatus}: scenarios=$($caseResults.Count) report=$runRoot"
    if ($overallStatus -eq 'PASS') { exit 0 }
    if ($overallStatus -eq 'NOT_RUN') { exit 2 }
    exit 1
} catch {
    Write-Error $_
    exit 1
}
