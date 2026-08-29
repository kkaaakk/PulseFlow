param(
    [ValidateSet('small', 'medium', 'large')][string]$Scale = 'small',
    [int]$Seed = 20260827,
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$Concurrency = 8,
    [int]$WaitSeconds = 120,
    [int]$CampaignWaitSeconds = 360,
    [switch]$PrepareDependencies,
    [switch]$RunMaven,
    [switch]$RebaseEventTime,
    [ValidateSet('none', 'smoke', 'load', 'stress')][string]$Performance = 'none',
    [switch]$AllowStress
)

. (Join-Path $PSScriptRoot 'common.ps1')

function Stage-Status {
    param([int]$ExitCode)
    if ($ExitCode -eq 0) { return 'PASS' }
    if ($ExitCode -eq 2) { return 'NOT_RUN' }
    return 'FAIL'
}

try {
    $runId = New-TestRunId
    $runRoot = Get-TestReportDirectory -RunId $runId
    $functionalDir = Join-Path $runRoot 'functional'
    & (Join-Path $PSScriptRoot 'functional\run.ps1') `
        -Scale $Scale -Seed $Seed -BaseUrl $BaseUrl -Concurrency $Concurrency `
        -WaitSeconds $WaitSeconds -CampaignWaitSeconds $CampaignWaitSeconds `
        -RunId $runId -ReportDir $functionalDir `
        -PrepareDependencies:$PrepareDependencies -RunMaven:$RunMaven `
        -RebaseEventTime:$RebaseEventTime
    $functionalExit = $LASTEXITCODE

    $performanceStatus = 'NOT_RUN'
    $performanceExit = 2
    if ($Performance -ne 'none') {
        $performanceDir = Join-Path $runRoot 'performance'
        $performanceArgs = @('-Scenario', $Performance, '-BaseUrl', $BaseUrl, '-ReportDir', $performanceDir)
        if ($Performance -eq 'stress' -and $AllowStress) { $performanceArgs += '-AllowStress' }
        & (Join-Path $PSScriptRoot 'performance\run.ps1') @performanceArgs
        $performanceExit = $LASTEXITCODE
        $performanceStatus = Stage-Status -ExitCode $performanceExit
    }

    $functionalStatus = Stage-Status -ExitCode $functionalExit
    $overallStatus = if ($functionalStatus -eq 'FAIL' -or $performanceStatus -eq 'FAIL') { 'FAIL' }
    elseif ($functionalStatus -eq 'NOT_RUN' -or $performanceStatus -eq 'NOT_RUN') {
        if ($Performance -eq 'none' -and $functionalStatus -eq 'PASS') { 'PASS' } else { 'NOT_RUN' }
    } else { 'PASS' }
    $report = [ordered]@{
        status = $overallStatus
        runId = $runId
        functionalStatus = $functionalStatus
        performanceStatus = $performanceStatus
        performanceScenario = $Performance
        functionalReport = Join-Path $functionalDir 'functional-report.json'
        performanceReport = if ($Performance -eq 'none') { $null } else { Join-Path (Join-Path $runRoot 'performance') 'performance-report.json' }
        exitCodes = [ordered]@{ functional = $functionalExit; performance = $performanceExit }
    }
    $report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $runRoot 'run-all-report.json') -Encoding UTF8
    Write-Host "run-all ${overallStatus}: functional=${functionalStatus} performance=${performanceStatus} report=${runRoot}"
    if ($overallStatus -eq 'PASS') { exit 0 }
    if ($overallStatus -eq 'NOT_RUN') { exit 2 }
    exit 1
} catch {
    Write-Error $_
    exit 1
}
