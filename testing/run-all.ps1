param(
    [ValidateSet('small', 'medium', 'large')][string]$Scale = 'small',
    [int]$Seed = 20260827,
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$Concurrency = 8,
    [int]$WaitSeconds = 120,
    [int]$CampaignWaitSeconds = 600,
    [switch]$PrepareDependencies,
    [switch]$RebaseEventTime,
    [switch]$JobsTriggered,
    [ValidateSet('none', 'smoke', 'load', 'stress')][string]$Performance = 'none',
    [switch]$AllowStress
)

. (Join-Path $PSScriptRoot 'common.ps1')

function Convert-ExitCodeToStatus {
    param([int]$ExitCode)
    if ($ExitCode -eq 0) { return 'PASS' }
    if ($ExitCode -eq 2) { return 'NOT_RUN' }
    return 'FAIL'
}

function Read-ReportStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Fallback
    )
    if (Test-Path -LiteralPath $Path) {
        try {
            $report = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
            $property = $report.PSObject.Properties['status']
            if ($property -and [string]$property.Value -in @('PASS', 'FAIL', 'NOT_RUN')) {
                return [string]$property.Value
            }
        } catch {
            Write-Warning "Unable to read report status from $Path`: $($_.Exception.Message)"
        }
    }
    return $Fallback
}

function Get-JsonProperty {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($property) { return $property.Value }
    return $null
}

function Format-SummaryMetric {
    param([object]$Value, [string]$Unit = '', [switch]$Integer)
    if ($null -eq $Value) { return '—' }
    try {
        if ($Integer) { return ('{0:N0}{1}' -f [double]$Value, $Unit) }
        return ('{0:N2}{1}' -f [double]$Value, $Unit)
    } catch {
        return ([string]$Value) + $Unit
    }
}

function Format-SummaryPercent {
    param([object]$Value)
    if ($null -eq $Value) { return '—' }
    try { return '{0:P2}' -f [double]$Value } catch { return [string]$Value }
}

function Write-RunAllSummary {
    param(
        [Parameter(Mandatory = $true)][string]$RunRoot,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][string]$FunctionalStatus,
        [Parameter(Mandatory = $true)][string]$PerformanceStatus,
        [Parameter(Mandatory = $true)][string]$OverallStatus,
        [Parameter(Mandatory = $true)][string]$PerformanceScenario,
        [object]$PerformanceReport
    )

    $lines = @(
        '# PulseFlow Test Summary',
        '',
        "Functional: $FunctionalStatus",
        "Performance: $PerformanceStatus",
        "Overall: $OverallStatus",
        '',
        "Run ID: ``$RunId``",
        '',
        'Functional report: `functional/functional-report.md`'
    )

    if ($PerformanceScenario -eq 'none') {
        $lines += 'Performance report: not selected.'
    } else {
        $lines += 'Performance report: `performance/performance-report.md`'
        $metrics = Get-JsonProperty -Object $PerformanceReport -Name 'metrics'
        if ($null -ne $metrics) {
            $apiErrorRate = Get-JsonProperty -Object $metrics -Name 'apiErrorRate'
            $errorRate = if ($null -ne $apiErrorRate) { $apiErrorRate } else {
                Get-JsonProperty -Object $metrics -Name 'errorRate'
            }
            $lines += @(
                '',
                "Performance metrics ($($PerformanceScenario.ToUpperInvariant())):",
                '',
                '| Metric | Value |',
                '|---|---:|',
                "| Requests | $(Format-SummaryMetric -Value (Get-JsonProperty -Object $metrics -Name 'requests') -Integer) |",
                "| RPS | $(Format-SummaryMetric -Value (Get-JsonProperty -Object $metrics -Name 'rps')) |",
                "| P95 | $(Format-SummaryMetric -Value (Get-JsonProperty -Object $metrics -Name 'p95Ms') -Unit ' ms') |",
                "| P99 | $(Format-SummaryMetric -Value (Get-JsonProperty -Object $metrics -Name 'p99Ms') -Unit ' ms') |",
                "| Error rate | $(Format-SummaryPercent -Value $errorRate) |"
            )
        }
    }

    $lines += @('', 'Detailed scenario and failure information remains in the Functional and Performance reports.')
    $lines | Set-Content -LiteralPath (Join-Path $RunRoot 'run-all-report.md') -Encoding UTF8
}

try {
    $runId = New-TestRunId
    $runRoot = Get-TestReportDirectory -RunId $runId
    $functionalDir = Join-Path $runRoot 'functional'
    $functionalScript = Join-Path $PSScriptRoot 'functional\run.ps1'

    & $functionalScript `
        -Scale $Scale -Seed $Seed -BaseUrl $BaseUrl -Concurrency $Concurrency `
        -WaitSeconds $WaitSeconds -CampaignWaitSeconds $CampaignWaitSeconds `
        -RunId $runId -ReportDir $functionalDir `
        -PrepareDependencies:$PrepareDependencies `
        -RebaseEventTime:$RebaseEventTime -JobsTriggered:$JobsTriggered
    $functionalExit = [int]$LASTEXITCODE
    $functionalReportPath = Join-Path $functionalDir 'functional-report.json'
    $functionalStatus = Read-ReportStatus -Path $functionalReportPath `
        -Fallback (Convert-ExitCodeToStatus -ExitCode $functionalExit)

    $performanceExit = 2
    $performanceStatus = 'NOT_RUN'
    $performanceReportPath = $null
    $performanceReport = $null
    if ($Performance -ne 'none') {
        $performanceDir = Join-Path $runRoot 'performance'
        $performanceScript = Join-Path $PSScriptRoot 'performance\run.ps1'
        & $performanceScript `
            -Scenario $Performance -BaseUrl $BaseUrl -ReportDir $performanceDir `
            -AllowStress:$AllowStress
        $performanceExit = [int]$LASTEXITCODE
        $performanceReportPath = Join-Path $performanceDir 'performance-report.json'
        $performanceStatus = Read-ReportStatus -Path $performanceReportPath `
            -Fallback (Convert-ExitCodeToStatus -ExitCode $performanceExit)
        if (Test-Path -LiteralPath $performanceReportPath) {
            try { $performanceReport = Get-Content -Raw -LiteralPath $performanceReportPath | ConvertFrom-Json } catch { }
        }
    }

    if ($functionalStatus -eq 'FAIL' -or $performanceStatus -eq 'FAIL') {
        $overallStatus = 'FAIL'
    } elseif ($Performance -ne 'none' -and ($functionalStatus -eq 'NOT_RUN' -or $performanceStatus -eq 'NOT_RUN')) {
        $overallStatus = 'NOT_RUN'
    } else {
        $overallStatus = $functionalStatus
    }

    $report = [ordered]@{
        status = $overallStatus
        runId = $runId
        functionalStatus = $functionalStatus
        performanceStatus = $performanceStatus
        performanceScenario = $Performance
        functionalReport = $functionalReportPath
        performanceReport = $performanceReportPath
        exitCodes = [ordered]@{
            functional = $functionalExit
            performance = $performanceExit
        }
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runRoot 'run-all-report.json') -Encoding UTF8
    Write-RunAllSummary -RunRoot $runRoot -RunId $runId -FunctionalStatus $functionalStatus `
        -PerformanceStatus $performanceStatus -OverallStatus $overallStatus `
        -PerformanceScenario $Performance -PerformanceReport $performanceReport
    Write-Host "run-all ${overallStatus}: functional=${functionalStatus} performance=${performanceStatus} report=${runRoot}"

    if ($overallStatus -eq 'PASS') { exit 0 }
    if ($overallStatus -eq 'NOT_RUN') { exit 2 }
    exit 1
} catch {
    Write-Error $_
    exit 1
}
