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

$script:PerformanceStatusLabels = @{
    PASS = '✅ PASS（通过）'
    FAIL = '❌ FAIL（失败）'
    NOT_RUN = '⚠️ NOT_RUN（未执行）'
}

function Get-PerformanceStatusLabel {
    param([string]$Status)
    if ($script:PerformanceStatusLabels.ContainsKey($Status)) { return $script:PerformanceStatusLabels[$Status] }
    return $Status
}

function Get-JsonPropertyValue {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($property) { return $property.Value }
    return $null
}

function Get-K6MetricField {
    param([object]$Metrics, [string]$MetricName, [string]$Field)
    $metric = Get-JsonPropertyValue -Object $Metrics -Name $MetricName
    return Get-JsonPropertyValue -Object $metric -Name $Field
}

function Get-K6Metrics {
    param([Parameter(Mandatory = $true)][string]$SummaryPath)
    if (-not (Test-Path -LiteralPath $SummaryPath)) { return $null }
    try {
        $summary = Get-Content -Raw -LiteralPath $SummaryPath | ConvertFrom-Json
        if ($null -eq $summary.metrics) { return $null }
        $metrics = $summary.metrics
        return [pscustomobject][ordered]@{
            requests = Get-K6MetricField -Metrics $metrics -MetricName 'http_reqs' -Field 'count'
            rps = Get-K6MetricField -Metrics $metrics -MetricName 'http_reqs' -Field 'rate'
            avgMs = Get-K6MetricField -Metrics $metrics -MetricName 'http_req_duration' -Field 'avg'
            p95Ms = Get-K6MetricField -Metrics $metrics -MetricName 'http_req_duration' -Field 'p(95)'
            p99Ms = Get-K6MetricField -Metrics $metrics -MetricName 'http_req_duration' -Field 'p(99)'
            errorRate = Get-K6MetricField -Metrics $metrics -MetricName 'http_req_failed' -Field 'value'
            apiErrorRate = Get-K6MetricField -Metrics $metrics -MetricName 'pulseflow_api_failures' -Field 'value'
            vus = Get-K6MetricField -Metrics $metrics -MetricName 'vus_max' -Field 'value'
        }
    } catch {
        return $null
    }
}

function Format-PerformanceMetric {
    param([object]$Value, [string]$Unit = '', [switch]$Integer)
    if ($null -eq $Value) { return '—' }
    try {
        if ($Integer) { return ('{0:N0}{1}' -f [double]$Value, $Unit) }
        return ('{0:N2}{1}' -f [double]$Value, $Unit)
    } catch {
        return ([string]$Value) + $Unit
    }
}

function Format-PerformancePercent {
    param([object]$Value)
    if ($null -eq $Value) { return '—' }
    try { return '{0:P2}' -f [double]$Value } catch { return ([string]$Value) }
}

function Get-PerformanceScenarioLabel {
    param([string]$Scenario)
    switch ($Scenario) {
        'smoke' { return 'Smoke（冒烟性能测试）' }
        'load' { return 'Load（负载测试）' }
        'stress' { return 'Stress（压力测试）' }
        default { return $Scenario }
    }
}

function Write-PerformanceMarkdown {
    param(
        [Parameter(Mandatory = $true)][string]$ReportDir,
        [Parameter(Mandatory = $true)][string]$Scenario,
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$Status,
        [string]$SummaryPath = '',
        [object]$Metrics,
        [int]$Vus = 0,
        [string]$Duration = '',
        [string]$Reason = ''
    )
    try {
        $statusLabel = Get-PerformanceStatusLabel -Status $Status
        $scenarioLabel = Get-PerformanceScenarioLabel -Scenario $Scenario
        $lines = @(
            '# PulseFlow 性能验收报告',
            '',
            '## 测试结论',
            '',
            "总体结果：$statusLabel",
            "测试类型：$scenarioLabel",
            "目标地址：$([char]96)$BaseUrl$([char]96)",
            ''
        )
        if ($Status -eq 'NOT_RUN') {
            $lines += '## 未执行原因'
            $lines += ''
            $lines += if ($Reason) { $Reason } else { '未满足 Performance 执行条件。' }
            $lines += ''
            $lines += '原始报告仍保存在 `performance-report.json` 和 `k6-summary.json`。'
        } else {
            if ($Scenario -eq 'smoke') {
                $lines += "虚拟用户：$([char]96)$Vus$([char]96)"
                $lines += "持续时间：$([char]96)$Duration$([char]96)"
                $lines += ''
            }
            $apiErrorRate = if ($null -ne $Metrics) { $Metrics.apiErrorRate } else { $null }
            $errorRate = if ($null -ne $apiErrorRate) { $apiErrorRate } elseif ($null -ne $Metrics) { $Metrics.errorRate } else { $null }
            $lines += @(
                '## 核心性能指标',
                '',
                '| 指标 | 结果 | 含义 |',
                '|---|---:|---|',
                "| 请求总数 | $(Format-PerformanceMetric -Value $(if ($Metrics) { $Metrics.requests }) -Integer) | 本次发送的 HTTP 请求数量 |",
                "| QPS / RPS | $(Format-PerformanceMetric -Value $(if ($Metrics) { $Metrics.rps })) | 每秒请求数 |",
                "| 平均响应时间 | $(Format-PerformanceMetric -Value $(if ($Metrics) { $Metrics.avgMs }) -Unit ' ms') | 请求平均响应耗时 |",
                "| P95 | $(Format-PerformanceMetric -Value $(if ($Metrics) { $Metrics.p95Ms }) -Unit ' ms') | 95% 请求在此时间内完成 |",
                "| P99 | $(Format-PerformanceMetric -Value $(if ($Metrics) { $Metrics.p99Ms }) -Unit ' ms') | 99% 请求在此时间内完成 |",
                "| 错误率 | $(Format-PerformancePercent -Value $errorRate) | HTTP/API 请求失败比例 |"
            )
            if ($null -eq $Metrics -or $null -eq $Metrics.p99Ms) {
                $lines += ''
                $lines += '注：k6 Summary 未提供 P99，本报告显示为 `—`，未进行推算。'
            }
            $lines += @('', '## 结果说明', '')
            if ($Status -eq 'PASS') {
                $lines += '- ✅ k6 阈值检查通过。'
                $lines += '- ✅ 本次 HTTP 接入请求稳定。'
                $lines += '- ℹ️ Smoke 结果只代表基础稳定性，不代表系统最大 QPS。'
            } else {
                $lines += '- ❌ 至少一个 k6 阈值未通过，详见 `k6-summary.json`。'
            }
        }
        $lines += @('', '## 原始数据', '', '指标来自 `k6-summary.json`；Performance 不调用 Functional Validator。')
        $lines | Set-Content -LiteralPath (Join-Path $ReportDir 'performance-report.md') -Encoding UTF8
    } catch {
        Write-Warning "Performance Markdown report generation failed; JSON reports are preserved: $($_.Exception.Message)"
    }
}

try {
    Assert-LoopbackUrl -Url $BaseUrl
    if ($Scenario -eq 'stress' -and -not $AllowStress) {
        if (-not $ReportDir) {
            $ReportDir = Join-Path (Get-TestReportDirectory -RunId (New-TestRunId)) 'performance'
        }
        New-Item -ItemType Directory -Path $ReportDir -Force | Out-Null
        $reason = 'Stress is manual only. Re-run with -AllowStress after confirming the target is disposable.'
        $notRun = [ordered]@{
            status = 'NOT_RUN'
            scenario = $Scenario
            baseUrl = $BaseUrl
            reason = $reason
            checkedAt = (Get-Date).ToUniversalTime().ToString('o')
        }
        $notRun | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ReportDir 'performance-report.json') -Encoding UTF8
        $notRun | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ReportDir 'k6-summary.json') -Encoding UTF8
        Write-PerformanceMarkdown -ReportDir $ReportDir -Scenario $Scenario -BaseUrl $BaseUrl `
            -Status 'NOT_RUN' -SummaryPath (Join-Path $ReportDir 'k6-summary.json') -Reason $reason
        Write-Warning $reason
        exit 2
    }
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
        Write-PerformanceMarkdown -ReportDir $ReportDir -Scenario $Scenario -BaseUrl $BaseUrl `
            -Status 'NOT_RUN' -SummaryPath (Join-Path $ReportDir 'k6-summary.json') -Reason 'k6 未在 PATH 中找到。'
        Write-Warning 'k6 was not found; performance validation is NOT_RUN.'
        exit 2
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
        Write-PerformanceMarkdown -ReportDir $ReportDir -Scenario $Scenario -BaseUrl $BaseUrl `
            -Status 'NOT_RUN' -SummaryPath (Join-Path $ReportDir 'k6-summary.json') -Reason "应用地址不可达：$($_.Exception.Message)"
        Write-Warning 'application endpoint is not reachable; performance validation is NOT_RUN.'
        exit 2
    }
    if (-not $ReportDir) {
        $runId = New-TestRunId
        $ReportDir = Join-Path (Get-TestReportDirectory -RunId $runId) 'performance'
    }
    New-Item -ItemType Directory -Path $ReportDir -Force | Out-Null
    $script = Join-Path $PSScriptRoot 'performance.js'
    $summaryPath = Join-Path $ReportDir 'k6-summary.json'
    $arguments = @('run', '-e', "BASE_URL=$BaseUrl", '-e', "RUN_ID=$(New-TestRunId)", '-e', "SCENARIO=$Scenario")
    if ($Scenario -eq 'smoke') {
        $arguments += @('-e', "VUS=$Vus", '-e', "DURATION=$Duration")
    }
    if ($PaceSeconds) { $arguments += @('-e', "PACE_SECONDS=$PaceSeconds") }
    if ($Scenario -eq 'stress') { $arguments += @('-e', 'ALLOW_STRESS=true') }
    $arguments += @('--summary-export', $summaryPath, $script)
    & k6 @arguments
    $k6Exit = $LASTEXITCODE
    $status = if ($k6Exit -eq 0) { 'PASS' } else { 'FAIL' }
    $metrics = Get-K6Metrics -SummaryPath $summaryPath
    $report = [ordered]@{
        status = $status
        scenario = $Scenario
        baseUrl = $BaseUrl
        summary = $summaryPath
        metrics = $metrics
        validator = 'Not run: k6 owns performance thresholds only'
        exitCode = $k6Exit
        checkedAt = (Get-Date).ToUniversalTime().ToString('o')
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ReportDir 'performance-report.json') -Encoding UTF8
    Write-PerformanceMarkdown -ReportDir $ReportDir -Scenario $Scenario -BaseUrl $BaseUrl `
        -Status $status -SummaryPath $summaryPath -Metrics $metrics -Vus $Vus -Duration $Duration
    if ($k6Exit -ne 0) { exit $k6Exit }
} catch {
    Write-Error $_
    exit 1
}
