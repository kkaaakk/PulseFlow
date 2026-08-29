param(
    [ValidateSet('smoke', 'load', 'stress')][string]$Scenario = 'smoke',
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ReportDir = '',
    [int]$Vus = 10,
    [string]$Duration = '30s',
    [string]$PaceSeconds = '',
    [switch]$AllowStress
)

. (Join-Path $PSScriptRoot '..\common.ps1')

try {
    Assert-LoopbackUrl -Url $BaseUrl
    if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
        if (-not $ReportDir) {
            $ReportDir = Join-Path (Get-TestReportDirectory -RunId (New-TestRunId)) 'performance'
        }
        New-Item -ItemType Directory -Path $ReportDir -Force | Out-Null
        $notRun = [ordered]@{
            status = 'NOT_RUN'
            scenario = $Scenario
            baseUrl = $BaseUrl
            reason = 'k6 was not found on PATH'
            checkedAt = (Get-Date).ToUniversalTime().ToString('o')
        }
        $notRun | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ReportDir 'performance-report.json') -Encoding UTF8
        $notRun | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ReportDir 'k6-summary.json') -Encoding UTF8
        Write-Warning 'k6 was not found; performance validation is NOT_RUN.'
        exit 2
    }
    if ($Scenario -eq 'stress' -and -not $AllowStress) {
        throw 'Stress is manual only. Re-run with -AllowStress after confirming the target is disposable.'
    }
    try {
        Assert-TcpEndpoint -Url $BaseUrl
    } catch {
        if (-not $ReportDir) {
            $ReportDir = Join-Path (Get-TestReportDirectory -RunId (New-TestRunId)) 'performance'
        }
        New-Item -ItemType Directory -Path $ReportDir -Force | Out-Null
        $notRun = [ordered]@{
            status = 'NOT_RUN'
            scenario = $Scenario
            baseUrl = $BaseUrl
            reason = "application endpoint is not reachable: $($_.Exception.Message)"
            checkedAt = (Get-Date).ToUniversalTime().ToString('o')
        }
        $notRun | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ReportDir 'performance-report.json') -Encoding UTF8
        $notRun | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ReportDir 'k6-summary.json') -Encoding UTF8
        Write-Warning 'application endpoint is not reachable; performance validation is NOT_RUN.'
        exit 2
    }
    if (-not $ReportDir) {
        $runId = New-TestRunId
        $ReportDir = Join-Path (Get-TestReportDirectory -RunId $runId) 'performance'
    }
    New-Item -ItemType Directory -Path $ReportDir -Force | Out-Null
    $script = Join-Path $PSScriptRoot "$Scenario.js"
    $summaryPath = Join-Path $ReportDir 'k6-summary.json'
    $arguments = @('run', '-e', "BASE_URL=$BaseUrl", '-e', "RUN_ID=$(New-TestRunId)")
    if ($Scenario -eq 'smoke') {
        $arguments += @('-e', "VUS=$Vus", '-e', "DURATION=$Duration")
    }
    if ($PaceSeconds) { $arguments += @('-e', "PACE_SECONDS=$PaceSeconds") }
    if ($Scenario -eq 'stress') { $arguments += @('-e', 'ALLOW_STRESS=true') }
    $arguments += @('--summary-export', $summaryPath, $script)
    & k6 @arguments
    $k6Exit = $LASTEXITCODE
    $status = if ($k6Exit -eq 0) { 'PASS' } else { 'FAIL' }
    $report = [ordered]@{
        status = $status
        scenario = $Scenario
        baseUrl = $BaseUrl
        summary = $summaryPath
        validator = 'Not run: k6 owns performance thresholds only'
        exitCode = $k6Exit
        checkedAt = (Get-Date).ToUniversalTime().ToString('o')
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ReportDir 'performance-report.json') -Encoding UTF8
    if ($k6Exit -ne 0) { exit $k6Exit }
} catch {
    Write-Error $_
    exit 1
}
